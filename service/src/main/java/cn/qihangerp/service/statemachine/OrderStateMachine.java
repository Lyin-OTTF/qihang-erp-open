package cn.qihangerp.service.statemachine;

import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 订单状态机
 * 定义订单状态的合法迁移路径，禁止非法跳转
 */
@Component
public class OrderStateMachine {

    /**
     * 状态迁移表：key为当前状态，value为可迁移到的目标状态列表
     */
    private final Map<OrderState, List<StateTransition<OrderState>>> transitionMap;

    public OrderStateMachine() {
        this.transitionMap = new HashMap<>();
        initTransitions();
    }

    /**
     * 初始化状态迁移规则
     */
    private void initTransitions() {
        // 待付款(21) -> 新订单(0), 已取消(11), 已关闭(13)
        addTransition(OrderState.PENDING_PAYMENT, OrderState.NEW, "支付成功", false);
        addTransition(OrderState.PENDING_PAYMENT, OrderState.CANCELLED, "取消订单", false);
        addTransition(OrderState.PENDING_PAYMENT, OrderState.CLOSED, "超时关闭", false);

        // 新订单(0) -> 待发货(1), 已取消(11), 退款中(12)
        addTransition(OrderState.NEW, OrderState.PENDING_SHIPMENT, "确认订单", false);
        addTransition(OrderState.NEW, OrderState.CANCELLED, "取消订单", false);
        addTransition(OrderState.NEW, OrderState.REFUNDING, "申请退款", false);

        // 待发货(1) -> 已发货(2), 部分发货(101), 已取消(11), 退款中(12)
        addTransition(OrderState.PENDING_SHIPMENT, OrderState.SHIPPED, "发货", false);
        addTransition(OrderState.PENDING_SHIPMENT, OrderState.PARTIAL_SHIPPED, "部分发货", false);
        addTransition(OrderState.PENDING_SHIPMENT, OrderState.CANCELLED, "取消订单", true); // 需要审批
        addTransition(OrderState.PENDING_SHIPMENT, OrderState.REFUNDING, "申请退款", false);

        // 部分发货(101) -> 已发货(2), 退款中(12)
        addTransition(OrderState.PARTIAL_SHIPPED, OrderState.SHIPPED, "全部发货", false);
        addTransition(OrderState.PARTIAL_SHIPPED, OrderState.REFUNDING, "申请退款", false);

        // 已发货(2) -> 已完成(3), 退款中(12)
        addTransition(OrderState.SHIPPED, OrderState.COMPLETED, "确认收货", false);
        addTransition(OrderState.SHIPPED, OrderState.REFUNDING, "申请退款", false);

        // 退款中(12) -> 已关闭(13), 待发货(1), 已发货(2)
        addTransition(OrderState.REFUNDING, OrderState.CLOSED, "退款成功", false);
        addTransition(OrderState.REFUNDING, OrderState.PENDING_SHIPMENT, "退款拒绝", false);
        addTransition(OrderState.REFUNDING, OrderState.SHIPPED, "退款拒绝(已发货)", false);

        // 已完成(3) -> 退款中(12) (售后退款)
        addTransition(OrderState.COMPLETED, OrderState.REFUNDING, "售后退款", false);

        // 锁定(22) -> 待发货(1), 已取消(11)
        addTransition(OrderState.LOCKED, OrderState.PENDING_SHIPMENT, "解锁订单", false);
        addTransition(OrderState.LOCKED, OrderState.CANCELLED, "取消锁定订单", true);
    }

    /**
     * 添加状态迁移规则
     */
    private void addTransition(OrderState from, OrderState to, String action, boolean requiresApproval) {
        transitionMap.computeIfAbsent(from, k -> new ArrayList<>())
                .add(new StateTransition<>(from, to, action, requiresApproval));
    }

    /**
     * 验证状态迁移是否合法
     *
     * @param currentState 当前状态码
     * @param targetState  目标状态码
     * @return 是否允许迁移
     */
    public boolean canTransition(Integer currentState, Integer targetState) {
        OrderState from = OrderState.fromCode(currentState);
        OrderState to = OrderState.fromCode(targetState);

        if (from == null || to == null) {
            return false;
        }

        List<StateTransition<OrderState>> transitions = transitionMap.get(from);
        if (transitions == null) {
            return false;
        }

        return transitions.stream()
                .anyMatch(t -> t.getToState() == to);
    }

    /**
     * 获取指定状态迁移规则
     *
     * @param currentState 当前状态码
     * @param targetState  目标状态码
     * @return 迁移规则，不存在返回null
     */
    public StateTransition<OrderState> getTransition(Integer currentState, Integer targetState) {
        OrderState from = OrderState.fromCode(currentState);
        OrderState to = OrderState.fromCode(targetState);

        if (from == null || to == null) {
            return null;
        }

        List<StateTransition<OrderState>> transitions = transitionMap.get(from);
        if (transitions == null) {
            return null;
        }

        return transitions.stream()
                .filter(t -> t.getToState() == to)
                .findFirst()
                .orElse(null);
    }

    /**
     * 获取当前状态可迁移到的所有目标状态
     *
     * @param currentState 当前状态码
     * @return 可迁移的目标状态列表
     */
    public List<OrderState> getAvailableTransitions(Integer currentState) {
        OrderState from = OrderState.fromCode(currentState);
        if (from == null) {
            return Collections.emptyList();
        }

        List<StateTransition<OrderState>> transitions = transitionMap.get(from);
        if (transitions == null) {
            return Collections.emptyList();
        }

        return transitions.stream()
                .map(StateTransition::getToState)
                .toList();
    }

    /**
     * 检查状态迁移是否需要人工审批
     *
     * @param currentState 当前状态码
     * @param targetState  目标状态码
     * @return 是否需要审批
     */
    public boolean requiresApproval(Integer currentState, Integer targetState) {
        StateTransition<OrderState> transition = getTransition(currentState, targetState);
        return transition != null && transition.isRequiresApproval();
    }
}
