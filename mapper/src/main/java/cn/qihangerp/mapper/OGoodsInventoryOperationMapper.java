package cn.qihangerp.mapper;

import cn.qihangerp.model.entity.OGoodsInventoryOperation;
import cn.qihangerp.model.vo.InventoryFlowStat;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

/**
 * 库存操作流水 Mapper
 */
public interface OGoodsInventoryOperationMapper extends BaseMapper<OGoodsInventoryOperation> {

    /**
     * 按库存维度聚合流水指标（净变动量、首笔变动前余额、末笔变动后余额）。
     *
     * <p>使用窗口函数一次取回，避免逐库存查流水（N+1）。
     * 仅统计 status=1（成功）的流水。</p>
     *
     * @param merchantId   商户ID，null 表示不限
     * @param inventoryIds 库存ID集合，null/空表示不限
     * @return 每个库存一行
     */
    List<InventoryFlowStat> selectFlowStats(@Param("merchantId") Long merchantId,
                                            @Param("inventoryIds") Collection<Long> inventoryIds);

    /**
     * 统计某个商户下待处理差异涉及库存的流水条数（对账自检用，便于报告展示）。
     */
    long countByInventory(@Param("inventoryId") Long inventoryId);
}
