package cn.qihangerp.erp.serviceImpl.ai;

import cn.qihangerp.agent.policy.AutonomyLevel;
import cn.qihangerp.agent.policy.ToolPolicy;
import cn.qihangerp.statemachine.TransitionValidator;
import cn.qihangerp.statemachine.IllegalTransitionException;
import cn.qihangerp.agent.tenant.TenantContext;
import cn.qihangerp.model.entity.ShopOrder;
import cn.qihangerp.service.ShopOrderService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 订单写操作工具（受控写操作）。
 *
 * <p>与只读的 OrderTools 分离，所有方法均为 ESCALATE 级别，
 * 必须经过 ToolGateway 人工确认后才能执行。</p>
 *
 * <p>当前审批流（暂停-弹窗-确认-重试）尚未完整实现，
 * 因此这些工具在 AI 自动调用时会被网关拦截返回"需要人工确认"，
 * 不会实际执行写操作。审批流完善后即可真正执行。</p>
 *
 * @author 回溯开发-阶段2
 * @since 2026-10-12
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderActionTools {

    private final ShopOrderService orderService;
    private final TransitionValidator transitionValidator;

    /**
     * 修改订单商家备注（seller_memo）。
     *
     * <p>安全措施：
     * <ul>
     *   <li>ESCALATE 级别，需人工确认</li>
     *   <li>先按 租户ID+订单ID 查询验证所有权，防止跨租户修改</li>
     *   <li>只更新 sellerMemo 字段（MyBatis-Plus updateById 只更新非null字段）</li>
     *   <li>不影响订单状态、库存、金额</li>
     * </ul>
     * </p>
     */
    @Tool(description = "修改指定订单的商家备注(seller_memo)。仅修改备注文本，不影响订单状态、库存和金额。需要人工确认后执行。")
    @ToolPolicy(level = AutonomyLevel.ESCALATE, category = "订单操作")
    public String updateOrderSellerMemo(
            @ToolParam(description = "订单ID(oms_shop_order表的主键id)") Long orderId,
            @ToolParam(description = "新的商家备注内容") String memo) {

        Long merchantId = TenantContext.getTenantId();

        // 先验证订单存在且属于当前租户（读时租户过滤）
        LambdaQueryWrapper<ShopOrder> checkWrapper = new LambdaQueryWrapper<ShopOrder>()
                .eq(ShopOrder::getId, orderId);
        if (merchantId != null) {
            checkWrapper.eq(ShopOrder::getMerchantId, merchantId);
        }
        ShopOrder existing = orderService.getOne(checkWrapper);
        if (existing == null) {
            return "操作失败：订单不存在或不属于当前租户（orderId=" + orderId + "）";
        }

        // 只更新备注字段，构造仅含 id + sellerMemo 的实体
        ShopOrder update = new ShopOrder();
        update.setId(orderId);
        update.setSellerMemo(memo);
        boolean success = orderService.updateById(update);

        if (success) {
            log.info("订单商家备注已更新: orderId={}, merchantId={}, memo长度={}",
                    orderId, merchantId, memo != null ? memo.length() : 0);
            return "订单备注已更新（orderId=" + orderId + "）";
        } else {
            return "订单备注更新失败（orderId=" + orderId + "）";
        }
    }

    /**
     * 取消订单（将订单状态置为"已取消"=11）。
     *
     * <p>安全措施：
     * <ul>
     *   <li>ESCALATE 级别，需人工确认</li>
     *   <li>先按 租户ID+订单ID 查询验证所有权</li>
     *   <li>通过 TransitionValidator 校验状态迁移合法性（如已发货/已完成订单不可直接取消）</li>
     *   <li>只更新 orderStatus 字段，不影响其他数据</li>
     * </ul>
     * </p>
     */
    @Tool(description = "取消指定订单，将订单状态改为已取消。仅允许新订单、待发货、待付款状态的订单取消；已发货、已完成、退款中的订单不能直接取消。需要人工确认后执行。")
    @ToolPolicy(level = AutonomyLevel.ESCALATE, category = "订单操作")
    public String cancelOrder(
            @ToolParam(description = "订单ID(oms_shop_order表的主键id)") Long orderId,
            @ToolParam(description = "取消原因") String reason) {

        Long merchantId = TenantContext.getTenantId();

        // 1. 查询当前订单，验证租户所有权
        LambdaQueryWrapper<ShopOrder> checkWrapper = new LambdaQueryWrapper<ShopOrder>()
                .eq(ShopOrder::getId, orderId);
        if (merchantId != null) {
            checkWrapper.eq(ShopOrder::getMerchantId, merchantId);
        }
        ShopOrder existing = orderService.getOne(checkWrapper);
        if (existing == null) {
            return "操作失败：订单不存在或不属于当前租户（orderId=" + orderId + "）";
        }

        // 2. 状态机校验：当前状态 → 已取消(11)
        Integer currentStatus = existing.getOrderStatus();
        try {
            transitionValidator.validateOrderTransition(currentStatus, 11, String.valueOf(orderId));
        } catch (IllegalTransitionException e) {
            return "操作失败：" + e.getMessage() + "。该订单当前状态不允许取消，请走退款流程或联系管理员。";
        }

        // 3. 执行取消（只更新状态字段）
        ShopOrder update = new ShopOrder();
        update.setId(orderId);
        update.setOrderStatus(11);
        boolean success = orderService.updateById(update);

        if (success) {
            log.info("订单已取消: orderId={}, merchantId={}, 原状态={}, 原因={}",
                    orderId, merchantId, currentStatus, reason);
            return "订单已取消（orderId=" + orderId + "，原状态=" + currentStatus + "）";
        } else {
            return "订单取消失败（orderId=" + orderId + "）";
        }
    }
}
