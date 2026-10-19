package cn.qihangerp.service;

import cn.qihangerp.common.PageQuery;
import cn.qihangerp.common.PageResult;
import cn.qihangerp.model.bo.InventoryChangeCommand;
import cn.qihangerp.model.entity.OGoodsInventory;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * 主系统商品库存 Service 接口
 *
 * @author qihang
 */
public interface OGoodsInventoryService extends IService<OGoodsInventory> {

    /**
     * 分页查询库存列表
     */
    PageResult<OGoodsInventory> queryPageList(OGoodsInventory bo, PageQuery pageQuery);

    /**
     * 执行一次库存变动：原子条件更新 + 同事务写流水。
     *
     * <p>这是库存变动的唯一入口。带业务上下文的调用方应使用本方法，
     * 以便流水可追溯到业务单据并具备幂等能力。</p>
     *
     * @param cmd 变动指令
     * @return true=变动成功；false=条件不满足（库存不足/可用量不足/记录不存在）
     */
    boolean changeStock(InventoryChangeCommand cmd);

    /**
     * 扣减库存（出库时调用）
     *
     * @param inventoryId 库存记录ID
     * @param quantity    扣减数量
     * @return 是否成功
     */
    boolean deductStock(Long inventoryId, Integer quantity);

    /**
     * 增加库存（入库时调用）
     *
     * @param inventoryId 库存记录ID
     * @param quantity    增加数量
     * @return 是否成功
     */
    boolean addStock(Long inventoryId, Integer quantity);

    /**
     * 锁定/解锁库存
     *
     * @param inventoryId 库存记录ID
     * @param quantity    锁定数量（正数锁定，负数解锁）
     * @return 是否成功
     */
    boolean lockStock(Long inventoryId, Integer quantity);
}
