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
     * 原子条件扣减库存：仅当 quantity >= 扣减量时才执行，避免超卖。
     * 一条SQL完成"判断+扣减"，消除先查后写的竞态窗口。
     *
     * @param id         库存ID
     * @param quantity   扣减数量（正数）
     * @return 受影响行数；0表示库存不足或记录不存在
     */
    @Update("UPDATE o_goods_inventory " +
            "SET quantity = quantity - #{quantity}, " +
            "    available_quantity = available_quantity - #{quantity} " +
            "WHERE id = #{id} AND quantity >= #{quantity}")
    int deductStockAtomic(@Param("id") Long id, @Param("quantity") Integer quantity);

    /**
     * 原子增加库存。
     */
    @Update("UPDATE o_goods_inventory " +
            "SET quantity = quantity + #{quantity}, " +
            "    available_quantity = available_quantity + #{quantity} " +
            "WHERE id = #{id}")
    int addStockAtomic(@Param("id") Long id, @Param("quantity") Integer quantity);
}