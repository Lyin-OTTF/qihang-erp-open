package cn.qihangerp.mapper;

import cn.qihangerp.model.entity.OGoodsInventory;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 主系统商品库存 Mapper
 *
 * @author qihang
 * @description 针对表【o_goods_inventory(主系统商品库存)】的数据库操作Mapper
 */
public interface OGoodsInventoryMapper extends BaseMapper<OGoodsInventory> {

    /**
     * 原子条件扣减库存：总库存和可用库存都充足时才执行，避免超卖。
     * 一条SQL完成"判断+扣减+版本递增"，消除先查后写的竞态窗口。
     *
     * @param id         库存ID
     * @param quantity   扣减数量（正数）
     * @return 受影响行数；0表示库存不足或记录不存在
     */
    @Update("UPDATE o_goods_inventory " +
            "SET quantity = quantity - #{quantity}, " +
            "    available_quantity = available_quantity - #{quantity}, " +
            "    version = version + 1 " +
            "WHERE id = #{id} " +
            "  AND is_delete = 0 " +
            "  AND quantity >= #{quantity} " +
            "  AND available_quantity >= #{quantity}")
    int deductStockAtomic(@Param("id") Long id, @Param("quantity") Integer quantity);

    /**
     * 原子增加库存。
     */
    @Update("UPDATE o_goods_inventory " +
            "SET quantity = quantity + #{quantity}, " +
            "    available_quantity = available_quantity + #{quantity}, " +
            "    version = version + 1 " +
            "WHERE id = #{id} AND is_delete = 0")
    int addStockAtomic(@Param("id") Long id, @Param("quantity") Integer quantity);

    /**
     * 原子锁定库存：可用库存充足时同时增加锁定量、减少可用量。
     */
    @Update("UPDATE o_goods_inventory " +
            "SET locked_quantity = locked_quantity + #{quantity}, " +
            "    available_quantity = available_quantity - #{quantity}, " +
            "    version = version + 1 " +
            "WHERE id = #{id} " +
            "  AND is_delete = 0 " +
            "  AND available_quantity >= #{quantity}")
    int lockStockAtomic(@Param("id") Long id, @Param("quantity") Integer quantity);

    /**
     * 原子解锁库存：已锁定量充足时同时减少锁定量、增加可用量。
     */
    @Update("UPDATE o_goods_inventory " +
            "SET locked_quantity = locked_quantity - #{quantity}, " +
            "    available_quantity = available_quantity + #{quantity}, " +
            "    version = version + 1 " +
            "WHERE id = #{id} " +
            "  AND is_delete = 0 " +
            "  AND locked_quantity >= #{quantity}")
    int unlockStockAtomic(@Param("id") Long id, @Param("quantity") Integer quantity);
}
