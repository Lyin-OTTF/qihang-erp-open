package cn.qihangerp.erp.serviceImpl.ai;

import cn.qihangerp.agent.policy.AutonomyLevel;
import cn.qihangerp.agent.policy.ToolPolicy;
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
}
