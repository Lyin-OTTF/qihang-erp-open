package cn.qihangerp.service.statemachine;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 状态迁移规则
 * 定义从一个状态到另一个状态的合法迁移
 */
@Data
@AllArgsConstructor
public class StateTransition<T> {
    /**
     * 起始状态
     */
    private T fromState;

    /**
     * 目标状态
     */
    private T toState;

    /**
     * 迁移动作描述
     */
    private String action;

    /**
     * 是否需要人工审批
     */
    private boolean requiresApproval;

    public StateTransition(T fromState, T toState, String action) {
        this.fromState = fromState;
        this.toState = toState;
        this.action = action;
        this.requiresApproval = false;
    }
}
