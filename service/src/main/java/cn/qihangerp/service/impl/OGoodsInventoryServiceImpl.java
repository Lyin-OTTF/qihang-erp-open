package cn.qihangerp.service.impl;

import cn.qihangerp.common.PageQuery;
import cn.qihangerp.common.PageResult;
import cn.qihangerp.mapper.OGoodsInventoryMapper;
import cn.qihangerp.mapper.OGoodsInventoryOperationMapper;
import cn.qihangerp.model.entity.OGoodsInventory;
import cn.qihangerp.model.entity.OGoodsInventoryOperation;
import cn.qihangerp.service.OGoodsInventoryService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 主系统商品库存 Service 实现
 *
 * <p>库存扣减采用原子条件更新（UPDATE ... WHERE quantity/available_quantity >= ?），
 * 消除"先查后写"的竞态窗口，从根本上防止超卖。
 * 扣减与流水记录在同一事务内，失败整体回滚。</p>
 *
 * @author qihang
 */
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

    /**
     * 原子扣减库存：一条SQL完成"判断库存充足+扣减"，消除竞态。
     * 扣减成功后同事务写入流水记录。
     *
     * @param inventoryId 库存ID
     * @param quantity    扣减数量（正数）
     * @return true=扣减成功；false=库存不足或记录不存在
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deductStock(Long inventoryId, Integer quantity) {
        if (inventoryId == null || quantity == null || quantity <= 0) {
            return false;
        }
        // 原子条件更新：总库存和可用库存都充足时才执行
        int affected = baseMapper.deductStockAtomic(inventoryId, quantity);
        if (affected == 0) {
            return false; // 库存不足或不存在
        }
        // 查询扣减后的库存信息用于流水
        OGoodsInventory inv = this.getById(inventoryId);
        if (inv != null) {
            writeOperation(inv, quantity, 2, "扣减库存");
        }
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean addStock(Long inventoryId, Integer quantity) {
        if (inventoryId == null || quantity == null || quantity <= 0) {
            return false;
        }
        int affected = baseMapper.addStockAtomic(inventoryId, quantity);
        if (affected == 0) {
            return false;
        }
        OGoodsInventory inv = this.getById(inventoryId);
        if (inv != null) {
            writeOperation(inv, quantity, 1, "增加库存");
        }
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean lockStock(Long inventoryId, Integer quantity) {
        if (inventoryId == null || quantity == null || quantity == 0) {
            return false;
        }
        int affected = quantity > 0
                ? baseMapper.lockStockAtomic(inventoryId, quantity)
                : baseMapper.unlockStockAtomic(inventoryId, Math.abs(quantity));
        if (affected > 0) {
            OGoodsInventory inv = this.getById(inventoryId);
            int opType = quantity > 0 ? 3 : 4;
            if (inv != null) {
                writeOperation(inv, Math.abs(quantity), opType, quantity > 0 ? "锁定库存" : "解锁库存");
            }
            return true;
        }
        return false;
    }

    /**
     * 写入库存操作流水。与库存更新在同一事务内。
     */
    private void writeOperation(OGoodsInventory inv, Integer quantity, Integer type, String remark) {
        OGoodsInventoryOperation op = new OGoodsInventoryOperation();
        op.setGoodsId(inv.getGoodsId() != null ? inv.getGoodsId() : 0L);
        op.setGoodsNum(inv.getGoodsNum());
        op.setSkuId(inv.getSkuId() != null ? inv.getSkuId() : 0L);
        op.setSkuCode(inv.getSkuCode() != null ? inv.getSkuCode() : "");
        op.setBatchId(0L);
        op.setBatchNum("");
        op.setType(type);
        op.setInventoryDetailId(inv.getId());
        op.setQuantity(quantity);
        op.setLockedQuantity(0);
        op.setPrice(0.0);
        op.setBizType(4); // 其他
        op.setBizId(0L);
        op.setBizNum("");
        op.setBizItemId(0L);
        op.setStatus(1); // 成功
        op.setRemark(remark);
        op.setWarehouseId(inv.getWarehouseId() != null ? inv.getWarehouseId() : 0L);
        op.setPositionId(0L);
        op.setCreateTime(LocalDateTime.now());
        op.setUpdateTime(LocalDateTime.now());
        operationMapper.insert(op);
    }
}
