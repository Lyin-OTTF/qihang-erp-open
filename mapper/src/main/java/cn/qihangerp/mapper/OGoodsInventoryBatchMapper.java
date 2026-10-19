package cn.qihangerp.mapper;

import cn.qihangerp.model.entity.OGoodsInventoryBatch;
import cn.qihangerp.model.vo.InventoryBatchSum;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Collection;
import java.util.List;

/**
 * 主系统商品库存批次 Mapper
 *
 * @author qihang
 * @description 针对表【o_goods_inventory_batch(主系统商品库存批次明细)】的数据库操作Mapper
 */
public interface OGoodsInventoryBatchMapper extends BaseMapper<OGoodsInventoryBatch> {

    /**
     * 原子条件扣减批次库存。
     *
     * <p>原实现是"getById 判断 current_qty 足够 → updateById 写入新值"，两步之间存在
     * 竞态窗口，并发出库时同一批次会被重复扣减（超卖）。改为一条带条件的 UPDATE，
     * 由 InnoDB 行锁保证判断与扣减的原子性。</p>
     *
     * @return 受影响行数；0 表示批次不足或不存在
     */
    @Update("UPDATE o_goods_inventory_batch " +
            "SET current_qty = current_qty - #{quantity} " +
            "WHERE id = #{batchId} AND current_qty >= #{quantity}")
    int deductQtyAtomic(@Param("batchId") Long batchId, @Param("quantity") Integer quantity);

    /**
     * 按库存维度聚合批次当前数量，用于对账校验"批次合计 == 总库存"。
     */
    @Select("<script>" +
            "SELECT inventory_id AS inventoryId, SUM(current_qty) AS batchQty, COUNT(*) AS batchCount " +
            "FROM o_goods_inventory_batch " +
            "<where>" +
            "  <if test='inventoryIds != null and inventoryIds.size() > 0'>" +
            "    inventory_id IN <foreach collection='inventoryIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            "  </if>" +
            "</where>" +
            "GROUP BY inventory_id" +
            "</script>")
    List<InventoryBatchSum> sumCurrentQtyByInventory(@Param("inventoryIds") Collection<Long> inventoryIds);
}
