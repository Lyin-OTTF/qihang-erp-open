package cn.qihangerp.service.impl;

import cn.qihangerp.mapper.OGoodsInventoryBatchMapper;
import cn.qihangerp.model.entity.OGoodsInventoryBatch;
import cn.qihangerp.service.OGoodsInventoryBatchService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 主系统商品库存批次 Service 实现
 *
 * <p>批次扣减同样采用条件原子更新（UPDATE ... WHERE current_qty >= ?），
 * 避免"先查后写"在并发出库时把同一批次重复扣减。</p>
 *
 * @author qihang
 */
@AllArgsConstructor
@Service
public class OGoodsInventoryBatchServiceImpl
        extends ServiceImpl<OGoodsInventoryBatchMapper, OGoodsInventoryBatch>
        implements OGoodsInventoryBatchService {

    @Override
    public List<OGoodsInventoryBatch> listByInventoryId(Long inventoryId) {
        return this.list(new LambdaQueryWrapper<OGoodsInventoryBatch>()
                .eq(OGoodsInventoryBatch::getInventoryId, inventoryId));
    }

    @Override
    public boolean deductBatchStock(Long batchId, Integer quantity) {
        if (batchId == null || quantity == null || quantity <= 0) {
            return false;
        }
        return baseMapper.deductQtyAtomic(batchId, quantity) > 0;
    }

    @Override
    public boolean adjustBatchStock(Long batchId, Integer delta) {
        if (batchId == null || delta == null || delta == 0) {
            return false;
        }
        // deductQtyAtomic 的条件是 current_qty >= 传入数量：
        // 传 -delta 即在 current_qty + delta >= 0 的前提下把 delta 加到批次上，正负皆可。
        return baseMapper.deductQtyAtomic(batchId, -delta) > 0;
    }

    @Override
    public List<OGoodsInventoryBatch> searchByKeyword(String keyword, Long warehouseId) {
        LambdaQueryWrapper<OGoodsInventoryBatch> wrapper = new LambdaQueryWrapper<OGoodsInventoryBatch>()
                .and(w -> w
                        .like(OGoodsInventoryBatch::getBarcode, keyword)
                        .or()
                        .like(OGoodsInventoryBatch::getSkuCode, keyword)
                        .or()
                        .like(OGoodsInventoryBatch::getBatchNum, keyword))
                .gt(OGoodsInventoryBatch::getCurrentQty, 0);
        if (warehouseId != null) {
            wrapper.eq(OGoodsInventoryBatch::getWarehouseId, warehouseId);
        }
        wrapper.orderByAsc(OGoodsInventoryBatch::getCreateTime);
        return this.list(wrapper);
    }
}
