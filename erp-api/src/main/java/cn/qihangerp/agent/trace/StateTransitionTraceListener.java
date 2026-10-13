package cn.qihangerp.agent.trace;

import cn.qihangerp.statemachine.StateTransitionEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 状态机迁移 Trace 监听器。
 *
 * <p>监听 service 层 {@link StateTransitionEvent}，把每次订单/售后状态迁移
 * （校验通过、非法拦截）写入 agent_trace 表，实现状态机与监视环节的打通，
 * 防止状态变更跑偏。</p>
 *
 * <p>使用 {@code REQUIRES_NEW} 独立事务：即使被拦截的业务方法回滚，
 * 拦截记录仍可落库，便于审计追溯。</p>
 *
 * @author 状态机-监视接入
 * @since 2026-10-13
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StateTransitionTraceListener {

    private final TraceMapper traceMapper;

    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onStateTransition(StateTransitionEvent event) {
        TraceEntry entry = new TraceEntry();
        String traceId = "SM-" + event.getEntityType() + "-" + event.getEntityId();
        entry.setTraceId(traceId);
        entry.setSpanId(UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        entry.setToolName("STATE_MACHINE");
        entry.setToolCategory(event.getEntityType());
        entry.setAction(event.isAllowed() ? "TRANSITION" : "BLOCKED");
        entry.setStatus(event.isAllowed() ? "SUCCESS" : "BLOCKED");

        // 记录迁移前后状态
        String input = "{\"entityId\":\"" + event.getEntityId() + "\","
                + "\"from\":\"" + event.getFromStateName() + "(" + event.getFromState() + ")\","
                + "\"to\":\"" + event.getToStateName() + "(" + event.getToState() + ")\"}";
        entry.setInputParams(input);
        if (!event.isAllowed()) {
            entry.setErrorType("ILLEGAL_TRANSITION");
            entry.setErrorMessage(event.getFromStateName() + "→" + event.getToStateName()
                    + " 非法迁移，已被拦截");
        }
        entry.setCreatedAt(LocalDateTime.now());
        entry.setUpdatedAt(LocalDateTime.now());
        try {
            traceMapper.insert(entry);
            log.debug("状态迁移trace已记录: {} {} {}→{}",
                    event.getEntityType(), event.getEntityId(),
                    event.getFromStateName(), event.getToStateName());
        } catch (Exception e) {
            log.warn("状态迁移trace写入失败: {}", e.getMessage());
        }
    }
}
