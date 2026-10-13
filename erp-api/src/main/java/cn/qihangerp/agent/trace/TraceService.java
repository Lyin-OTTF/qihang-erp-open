package cn.qihangerp.agent.trace;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Agent Trace集中存储服务。
 *
 * <p>提供工具调用链路的记录、查询和统计能力。
 * 与ToolGateway集成，在每次工具调用前后自动记录trace。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TraceService {

    private final TraceMapper traceMapper;

    /**
     * 生成新的spanId。
     */
    public String newSpanId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /**
     * 记录工具调用开始。
     */
    public TraceEntry recordStart(String traceId, String toolName, String toolCategory,
                                   Long merchantId, Long userId, String inputParams,
                                   String autonomyLevel, String idempotencyKey, String lockKey) {
        TraceEntry entry = new TraceEntry();
        entry.setTraceId(traceId);
        entry.setSpanId(newSpanId());
        entry.setMerchantId(merchantId);
        entry.setUserId(userId);
        entry.setToolName(toolName);
        entry.setToolCategory(toolCategory);
        entry.setAction("EXECUTE");
        entry.setInputParams(truncate(inputParams, 2000));
        entry.setStatus("RUNNING");
        entry.setAutonomyLevel(autonomyLevel);
        entry.setIdempotencyKey(idempotencyKey);
        entry.setLockKey(lockKey);
        entry.setCreatedAt(LocalDateTime.now());
        entry.setUpdatedAt(LocalDateTime.now());

        try {
            traceMapper.insert(entry);
        } catch (Exception e) {
            log.warn("Trace记录开始失败: {}", e.getMessage());
        }
        return entry;
    }

    /**
     * 记录工具调用成功。
     */
    public void recordSuccess(TraceEntry entry, String outputResult, long durationMs) {
        if (entry == null || entry.getId() == null) return;
        entry.setStatus("SUCCESS");
        entry.setAction("SUCCESS");
        entry.setOutputResult(truncate(outputResult, 2000));
        entry.setDurationMs(durationMs);
        entry.setUpdatedAt(LocalDateTime.now());
        try {
            traceMapper.updateById(entry);
        } catch (Exception e) {
            log.warn("Trace记录成功失败: {}", e.getMessage());
        }
    }

    /**
     * 记录工具调用失败。
     */
    public void recordFailure(TraceEntry entry, String errorType, String errorMessage, long durationMs) {
        if (entry == null || entry.getId() == null) return;
        entry.setStatus("FAILURE");
        entry.setAction("FAILURE");
        entry.setErrorType(errorType);
        entry.setErrorMessage(truncate(errorMessage, 2000));
        entry.setDurationMs(durationMs);
        entry.setUpdatedAt(LocalDateTime.now());
        try {
            traceMapper.updateById(entry);
        } catch (Exception e) {
            log.warn("Trace记录失败: {}", e.getMessage());
        }
    }

    /**
     * 记录需要审批的工具调用。
     */
    public void recordApprovalRequired(TraceEntry entry, String reason) {
        if (entry == null || entry.getId() == null) return;
        entry.setStatus("APPROVAL_REQUIRED");
        entry.setAction("APPROVAL");
        entry.setErrorMessage(truncate(reason, 500));
        entry.setUpdatedAt(LocalDateTime.now());
        try {
            traceMapper.updateById(entry);
        } catch (Exception e) {
            log.warn("Trace记录审批失败: {}", e.getMessage());
        }
    }

    /**
     * 按traceId查询完整调用链。
     */
    public List<TraceEntry> getTraceChain(String traceId) {
        return traceMapper.findByTraceId(traceId);
    }

    /**
     * 按商户查询最近的trace。
     */
    public List<TraceEntry> getRecentTraces(Long merchantId, int limit) {
        return traceMapper.findRecentByMerchant(merchantId, limit);
    }

    /**
     * 统计指定traceId的调用次数。
     */
    public int countTraceSpans(String traceId) {
        return traceMapper.countByTraceId(traceId);
    }

    /**
     * 查询失败的调用。
     */
    public List<TraceEntry> getFailedTraces(Long merchantId, int limit) {
        LambdaQueryWrapper<TraceEntry> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TraceEntry::getStatus, "FAILURE")
                .eq(merchantId != null, TraceEntry::getMerchantId, merchantId)
                .orderByDesc(TraceEntry::getCreatedAt)
                .last("LIMIT " + limit);
        return traceMapper.selectList(wrapper);
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() > maxLen ? s.substring(0, maxLen) + "...(truncated)" : s;
    }
}
