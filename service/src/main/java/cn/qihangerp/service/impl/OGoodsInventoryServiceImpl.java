package cn.qihangerp.service.impl;

import cn.qihangerp.common.PageQuery;
import cn.qihangerp.common.PageResult;
import cn.qihangerp.common.ServiceException;
import cn.qihangerp.mapper.OGoodsInventoryMapper;
import cn.qihangerp.mapper.OGoodsInventoryOperationMapper;
import cn.qihangerp.model.bo.InventoryChangeCommand;
import cn.qihangerp.model.entity.OGoodsInventory;
import cn.qihangerp.model.entity.OGoodsInventoryOperation;
import cn.qihangerp.service.OGoodsInventoryService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 主系统商品库存 Service 实现
 *
 * <p>库存变动统一走 {@link #changeStock(InventoryChangeCommand)}：</p>
 * <ol>
 *   <li><b>幂等前置校验</b>：幂等键已存在则直接返回成功，不重复扣减；</li>
 *   <li><b>条件原子更新</b>：一条 SQL 完成"判断充足 + 扣减 + 版本递增"，
 *       消除"先查后写"的竞态窗口，从根本上防止超卖；</li>
 *   <li><b>同事务写流水</b>：UPDATE 持有行锁，同事务内回读即为本次写入后的余额，
 *       因此变动前后余额可精确记录，无需额外快照；流水写入失败则整笔回滚。</li>
 * </ol>
 *
 * @author qihang
 */
@Slf4j
@AllArgsConstructor
@Service
public class OGoodsInventoryServiceImpl
        extends ServiceImpl<OGoodsInventoryMapper, OGoodsInventory>
        implements OGoodsInventoryService {

    private final OGoodsInventoryOperationMapper operationMapper;

    @Override
    public PageResult<OGoodsInventory> queryPageList(OGoodsInventory bo, PageQuery pageQuery) {
        LambdaQueryWrapper<OGoodsInventory> qw = new LambdaQueryWrapper<OGoodsInventory>()
                .eq(bo.getIsDelete() != null, OGoodsInventory::getIsDelete, bo.getIsDelete())
                .eq(bo.getWarehouseId() != null, OGoodsInventory::getWarehouseId, bo.getWarehouseId())
                .eq(bo.getMerchantId() != null, OGoodsInventory::getMerchantId, bo.getMerchantId())
                .eq(bo.getSkuId() != null, OGoodsInventory::getSkuId, bo.getSkuId())
                .eq(bo.getGoodsId() != null, OGoodsInventory::getGoodsId, bo.getGoodsId())
                .eq(bo.getSkuCode() != null && !bo.getSkuCode().isEmpty(), OGoodsInventory::getSkuCode, bo.getSkuCode())
                .like(bo.getGoodsName() != null, OGoodsInventory::getGoodsName, bo.getGoodsName());
        // 默认只查未删除的
        if (bo.getIsDelete() == null) {
            qw.eq(OGoodsInventory::getIsDelete, 0);
        }
        IPage<OGoodsInventory> page = this.baseMapper.selectPage(pageQuery.build(), qw);
        return PageResult.build(page);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean changeStock(InventoryChangeCommand cmd) {
        String invalid = cmd.validate();
        if (invalid != null) {
            throw new ServiceException(invalid);
        }

        Long inventoryId = cmd.getInventoryId();
        int quantity = cmd.getQuantity();

        // 1. 幂等前置校验：同一业务事件重复提交时，库存只应变动一次
        if (cmd.getIdempotencyKey() != null && !cmd.getIdempotencyKey().isEmpty()) {
            Long existing = operationMapper.selectCount(new LambdaQueryWrapper<OGoodsInventoryOperation>()
                    .eq(OGoodsInventoryOperation::getIdempotencyKey, cmd.getIdempotencyKey()));
            if (existing != null && existing > 0) {
                log.info("库存变动幂等命中，跳过执行: key={}, inventoryId={}", cmd.getIdempotencyKey(), inventoryId);
                return true;
            }
        }

        // 2. 条件原子更新：判断与写入在同一条 SQL 内完成
        int affected = switch (cmd.getType()) {
            case InventoryChangeCommand.TYPE_IN -> baseMapper.addStockAtomic(inventoryId, quantity);
            case InventoryChangeCommand.TYPE_OUT -> baseMapper.deductStockAtomic(inventoryId, quantity);
            case InventoryChangeCommand.TYPE_LOCK -> baseMapper.lockStockAtomic(inventoryId, quantity);
            case InventoryChangeCommand.TYPE_UNLOCK -> baseMapper.unlockStockAtomic(inventoryId, quantity);
            case InventoryChangeCommand.TYPE_ADJUST -> baseMapper.adjustQtyAtomic(inventoryId, quantity);
            default -> 0;
        };
        if (affected == 0) {
            // 库存不足 / 可用量不足 / 记录不存在，交由调用方决定是回滚还是降级
            return false;
        }

        // 3. 回读变动后余额。UPDATE 已持有该行排他锁且事务未提交，
        //    其他事务无法修改该行，因此这里读到的一定是本事务写入后的值。
        OGoodsInventory inv = this.getById(inventoryId);
        if (inv == null) {
            throw new ServiceException("库存记录不存在：" + inventoryId);
        }

        // 4. 同事务写流水
        int delta = switch (cmd.getType()) {
            case InventoryChangeCommand.TYPE_IN -> quantity;
            case InventoryChangeCommand.TYPE_OUT -> -quantity;
            case InventoryChangeCommand.TYPE_ADJUST -> quantity;
            default -> 0; // 锁定/解锁不改总库存
        };
        try {
            writeOperation(cmd, inv, inv.getQuantity() - delta, inv.getQuantity());
        } catch (DuplicateKeyException e) {
            // 并发下两个事务带同一幂等键：先提交者已记账，本事务整体回滚，保证不重复扣减
            throw new ServiceException("库存流水幂等键冲突，本次操作已回滚：" + cmd.getIdempotencyKey());
        }
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deductStock(Long inventoryId, Integer quantity) {
        return changeStock(InventoryChangeCommand.of(inventoryId, quantity, InventoryChangeCommand.TYPE_OUT));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean addStock(Long inventoryId, Integer quantity) {
        return changeStock(InventoryChangeCommand.of(inventoryId, quantity, InventoryChangeCommand.TYPE_IN));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean lockStock(Long inventoryId, Integer quantity) {
        if (quantity == null || quantity == 0) {
            return false;
        }
        // 旧接口用负数表示解锁，这里归一化为"正数 + 明确类型"
        return changeStock(InventoryChangeCommand.of(inventoryId, Math.abs(quantity),
                quantity > 0 ? InventoryChangeCommand.TYPE_LOCK : InventoryChangeCommand.TYPE_UNLOCK));
    }

    /**
     * 写入库存操作流水。与库存更新在同一事务内，失败整体回滚。
     *
     * @param inv       变动后的库存记录
     * @param beforeQty 变动前总库存
     * @param afterQty  变动后总库存
     */
    private void writeOperation(InventoryChangeCommand cmd, OGoodsInventory inv,
                                Integer beforeQty, Integer afterQty) {
        OGoodsInventoryOperation op = new OGoodsInventoryOperation();
        op.setMerchantId(cmd.getMerchantId() != null ? cmd.getMerchantId()
                : (inv.getMerchantId() != null ? inv.getMerchantId() : 0L));
        op.setGoodsId(inv.getGoodsId() != null ? inv.getGoodsId() : 0L);
        op.setGoodsNum(inv.getGoodsNum());
        op.setSkuId(inv.getSkuId() != null ? inv.getSkuId() : 0L);
        op.setSkuCode(inv.getSkuCode() != null ? inv.getSkuCode() : "");
        op.setBatchId(cmd.getBatchId() != null ? cmd.getBatchId() : 0L);
        op.setBatchNum(cmd.getBatchNum() != null ? cmd.getBatchNum() : "");
        op.setType(cmd.getType());
        op.setInventoryDetailId(inv.getId());
        op.setQuantity(cmd.getQuantity());
        op.setBeforeQty(beforeQty);
        op.setAfterQty(afterQty);
        op.setIdempotencyKey(cmd.getIdempotencyKey());
        // 锁定量字段只对锁定/解锁有意义
        op.setLockedQuantity(cmd.getType() == InventoryChangeCommand.TYPE_LOCK
                || cmd.getType() == InventoryChangeCommand.TYPE_UNLOCK ? Math.abs(cmd.getQuantity()) : 0);
        op.setPrice(cmd.getPrice() != null ? cmd.getPrice() : 0.0);
        op.setBizType(cmd.getBizType() != null ? cmd.getBizType() : InventoryChangeCommand.BIZ_OTHER);
        op.setBizId(cmd.getBizId() != null ? cmd.getBizId() : 0L);
        op.setBizNum(cmd.getBizNum() != null ? cmd.getBizNum() : "");
        op.setBizItemId(cmd.getBizItemId() != null ? cmd.getBizItemId() : 0L);
        op.setStatus(1); // 1=成功
        op.setRemark(cmd.getRemark());
        op.setWarehouseId(cmd.getWarehouseId() != null ? cmd.getWarehouseId()
                : (inv.getWarehouseId() != null ? inv.getWarehouseId() : 0L));
        op.setPositionId(cmd.getPositionId() != null ? cmd.getPositionId() : 0L);
        op.setCreateBy(cmd.getOperator());
        op.setUpdateBy(cmd.getOperator());
        op.setCreateTime(LocalDateTime.now());
        op.setUpdateTime(LocalDateTime.now());
        operationMapper.insert(op);
    }
}
