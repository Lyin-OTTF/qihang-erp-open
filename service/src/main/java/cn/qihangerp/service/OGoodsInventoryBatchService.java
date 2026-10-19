package cn.qihangerp.service;

import cn.qihangerp.model.entity.OGoodsInventoryBatch;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * 主系统商品库存批次 Service 接口
 *
 * @author qihang
 */
public interface OGoodsInventoryBatchService extends IService<OGoodsInventoryBatch> {

    /**
     * 根据库存记录ID查询批次列表
     */
    List<OGoodsInventoryBatch> listByInventoryId(Long inventoryId);

    /**
     * 扣减批次库存（原子条件更新）
     *
     * @param batchId  批次ID
     * @param quantity 扣减数量
     * @return 是否成功；false 表示批次不足（不会发生部分扣减）
     */
    boolean deductBatchStock(Long batchId, Integer quantity);

    /**
     * 调整批次库存（补偿用，delta 可为负）
     *
     * @return 是否成功；false 表示调整后为负
     */
    boolean adjustBatchStock(Long batchId, Integer delta);

    /**
     * 根据条码或SKU编码查询可用批次（currentQty > 0）
     *
     * @param keyword 条码或SKU编码
     * @param warehouseId 仓库ID（可选，为null时查所有仓库）
     * @return 匹配的批次列表
     */
    List<OGoodsInventoryBatch> searchByKeyword(String keyword, Long warehouseId);
}
