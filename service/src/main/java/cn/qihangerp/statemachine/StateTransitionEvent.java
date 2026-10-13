package cn.qihangerp.statemachine;

import org.springframework.context.ApplicationEvent;

/**
 * 状态迁移事件。
 *
 * <p>由 {@link TransitionValidator} 在每次校验订单/售后状态迁移时发布，
 * 由上层（erp-api）监听并写入 agent_trace 表，实现状态机与监视环节的打通，
 * 防止状态变更跑偏。</p>
 *
 * <p>采用事件解耦：service 模块作为底层不依赖 erp-api 的 Trace 框架，
 * 但通过事件把状态变更"上报"给监视层。</p>
 *
 * @author 状态机-监视接入
 * @since 2026-10-13
 */
public class StateTransitionEvent extends ApplicationEvent {

    /** 实体类型：ORDER / REFUND */
    private final String entityType;
    /** 实体ID（订单号/售后单号） */
    private final String entityId;
    /** 迁移前状态 */
    private final Integer fromState;
    /** 迁移后状态 */
    private final Integer toState;
    /** 迁移前状态名 */
    private final String fromStateName;
    /** 迁移后状态名 */
    private final String toStateName;
    /** 是否校验通过 */
    private final boolean allowed;
    /** 操作来源 */
    private final String source_;

    public StateTransitionEvent(Object source, String entityType, String entityId,
                                 Integer fromState, Integer toState,
                                 String fromStateName, String toStateName,
                                 boolean allowed, String source_) {
        super(source);
        this.entityType = entityType;
        this.entityId = entityId;
        this.fromState = fromState;
        this.toState = toState;
        this.fromStateName = fromStateName;
        this.toStateName = toStateName;
        this.allowed = allowed;
        this.source_ = source_;
    }

    public String getEntityType() { return entityType; }
    public String getEntityId() { return entityId; }
    public Integer getFromState() { return fromState; }
    public Integer getToState() { return toState; }
    public String getFromStateName() { return fromStateName; }
    public String getToStateName() { return toStateName; }
    public boolean isAllowed() { return allowed; }
    public String getSource_() { return source_; }
}
