package cn.qihangerp.service.statemachine;

import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 售后状态机
 * 定义售后状态的合法迁移路径，禁止非法跳转
 */
@Component
public class RefundStateMachine {

    /**
     * 状态迁移表：key为当前状态，value为可迁移到的目标状态列表
     */
    private final Map<RefundState, List<StateTransition<RefundState>>> transitionMap;

    public RefundStateMachine() {
        this.transitionMap = new HashMap<>();
        initTransitions();
    }

    /**
     * 初始化状态迁移规则
     */
    private void initTransitions() {
        // 售后申请(0) -> 售后关闭(1), 售后处理中(2), 待用户处理(5)
        addTransition(RefundState.APPLIED, RefundState.CLOSED, "拒绝售后", true); // 需要审批
        addTransition(RefundState.APPLIED, RefundState.PROCESSING, "接受售后", false);
        addTransition(RefundState.APPLIED, RefundState.PENDING_USER, "待用户补充信息", false);

        // 待用户处理(5) -> 售后处理中(2), 售后关闭(1), 待买家发货(6)
        addTransition(RefundState.PENDING_USER, RefundState.PROCESSING, "用户已处理", false);
        addTransition(RefundState.PENDING_USER, RefundState.CLOSED, "用户取消", false);
        addTransition(RefundState.PENDING_USER, RefundState.PENDING_BUYER_SHIP, "等待买家发货", false);

        // 待买家发货(6) -> 售后处理中(2), 售后关闭(1)
        addTransition(RefundState.PENDING_BUYER_SHIP, RefundState.PROCESSING, "买家已发货", false);
        addTransition(RefundState.PENDING_BUYER_SHIP, RefundState.CLOSED, "取消售后", false);

        // 售后处理中(2) -> 退款中(3), 售后关闭(1), 平台处理中(8), 待买家发货(6)
        addTransition(RefundState.PROCESSING, RefundState.REFUNDING, "同意退款", true); // 需要审批
        addTransition(RefundState.PROCESSING, RefundState.CLOSED, "拒绝退款", true); // 需要审批
        addTransition(RefundState.PROCESSING, RefundState.PLATFORM_PROCESSING, "提交平台", false);
        addTransition(RefundState.PROCESSING, RefundState.PENDING_BUYER_SHIP, "要求退货", false);

        // 平台处理中(8) -> 退款中(3), 售后成功(4), 售后关闭(1)
        addTransition(RefundState.PLATFORM_PROCESSING, RefundState.REFUNDING, "平台同意退款", false);
        addTransition(RefundState.PLATFORM_PROCESSING, RefundState.SUCCESS, "平台处理完成", false);
        addTransition(RefundState.PLATFORM_PROCESSING, RefundState.CLOSED, "平台拒绝", false);

        // 退款中(3) -> 售后成功(4), 售后关闭(1)
        addTransition(RefundState.REFUNDING, RefundState.SUCCESS, "退款成功", false);
        addTransition(RefundState.REFUNDING, RefundState.CLOSED, "退款失败", false);
    }

    /**
     * 添加状态迁移规则
     */
    private void addTransition(RefundState from, RefundState to, String action, boolean requiresApproval) {
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
        RefundState from = RefundState.fromCode(currentState);
        RefundState to = RefundState.fromCode(targetState);

        if (from == null || to == null) {
            return false;
        }

        List<StateTransition<RefundState>> transitions = transitionMap.get(from);
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
    public StateTransition<RefundState> getTransition(Integer currentState, Integer targetState) {
        RefundState from = RefundState.fromCode(currentState);
        RefundState to = RefundState.fromCode(targetState);

        if (from == null || to == null) {
            return null;
        }

        List<StateTransition<RefundState>> transitions = transitionMap.get(from);
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
    public List<RefundState> getAvailableTransitions(Integer currentState) {
        RefundState from = RefundState.fromCode(currentState);
        if (from == null) {
            return Collections.emptyList();
        }

        List<StateTransition<RefundState>> transitions = transitionMap.get(from);
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
        StateTransition<RefundState> transition = getTransition(currentState, targetState);
        return transition != null && transition.isRequiresApproval();
    }
}
