package cn.qihangerp.agent.audit;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ActionLedger审计日志服务
 *
 * @author AI Code Generator
 * @since 2026-10-12
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActionLedgerService {

    private final ActionLedgerMapper actionLedgerMapper;
    private final ObjectMapper objectMapper;

    /**
     * 记录Tool调用审计日志
     *
     * @param request 审计日志请求
     * @return 审计日志ID
     */
    public Long recordAction(ActionLedgerRequest request) {
        try {
            ActionLedger ledger = ActionLedger.builder()
                    .traceId(request.getTraceId())
                    .toolName(request.getToolName())
                    .toolScope(request.getToolScope())
                    .autonomyLevel(request.getAutonomyLevel())
                    .merchantId(request.getMerchantId())
                    .userId(request.getUserId())
                    .sessionId(request.getSessionId())
                    .inputParams(serializeToJson(request.getInputParams()))
                    .outputResult(serializeToJson(request.getOutputResult()))
                    .success(request.getSuccess())
                    .errorMessage(request.getErrorMessage())
                    .errorType(request.getErrorType())
                    .startTime(request.getStartTime())
                    .endTime(request.getEndTime())
                    .durationMs(request.getDurationMs())
                    .isWriteOperation(request.getIsWriteOperation())
                    .requiresApproval(request.getRequiresApproval())
                    .approved(request.getApproved())
                    .ipAddress(request.getIpAddress())
                    .userAgent(request.getUserAgent())
                    .createTime(LocalDateTime.now())
                    .build();

            actionLedgerMapper.insert(ledger);
            log.debug("审计日志记录成功: id={}, toolName={}, traceId={}",
                    ledger.getId(), ledger.getToolName(), ledger.getTraceId());
            return ledger.getId();

        } catch (Exception e) {
            log.error("记录审计日志失败: toolName={}, error={}",
                    request.getToolName(), e.getMessage(), e);
            // 审计日志失败不应影响业务，因此不抛出异常
            return null;
        }
    }

    /**
     * 根据traceId查询审计日志
     *
     * @param traceId 链路追踪ID
     * @return 审计日志列表
     */
    public List<ActionLedger> getByTraceId(String traceId) {
        return actionLedgerMapper.selectList(
                new LambdaQueryWrapper<ActionLedger>()
                        .eq(ActionLedger::getTraceId, traceId)
                        .orderByAsc(ActionLedger::getStartTime)
        );
    }

    /**
     * 根据租户ID分页查询审计日志
     *
     * @param merchantId 租户ID
     * @param pageNum 页码
     * @param pageSize 每页大小
     * @return 分页结果
     */
    public Page<ActionLedger> getByMerchantId(Long merchantId, int pageNum, int pageSize) {
        Page<ActionLedger> page = new Page<>(pageNum, pageSize);
        return actionLedgerMapper.selectPage(page,
                new LambdaQueryWrapper<ActionLedger>()
                        .eq(ActionLedger::getMerchantId, merchantId)
                        .orderByDesc(ActionLedger::getCreateTime)
        );
    }

    /**
     * 根据用户ID分页查询审计日志
     *
     * @param userId 用户ID
     * @param pageNum 页码
     * @param pageSize 每页大小
     * @return 分页结果
     */
    public Page<ActionLedger> getByUserId(Long userId, int pageNum, int pageSize) {
        Page<ActionLedger> page = new Page<>(pageNum, pageSize);
        return actionLedgerMapper.selectPage(page,
                new LambdaQueryWrapper<ActionLedger>()
                        .eq(ActionLedger::getUserId, userId)
                        .orderByDesc(ActionLedger::getCreateTime)
        );
    }

    /**
     * 查询失败的审计日志
     *
     * @param merchantId 租户ID（可选）
     * @param pageNum 页码
     * @param pageSize 每页大小
     * @return 分页结果
     */
    public Page<ActionLedger> getFailedActions(Long merchantId, int pageNum, int pageSize) {
        Page<ActionLedger> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<ActionLedger> wrapper = new LambdaQueryWrapper<ActionLedger>()
                .eq(ActionLedger::getSuccess, false)
                .orderByDesc(ActionLedger::getCreateTime);

        if (merchantId != null) {
            wrapper.eq(ActionLedger::getMerchantId, merchantId);
        }

        return actionLedgerMapper.selectPage(page, wrapper);
    }

    /**
     * 查询需要审批的操作
     *
     * @param merchantId 租户ID
     * @param approved 审批状态（null=待审批，true=已批准，false=已拒绝）
     * @param pageNum 页码
     * @param pageSize 每页大小
     * @return 分页结果
     */
    public Page<ActionLedger> getApprovalActions(Long merchantId, Boolean approved,
                                                   int pageNum, int pageSize) {
        Page<ActionLedger> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<ActionLedger> wrapper = new LambdaQueryWrapper<ActionLedger>()
                .eq(ActionLedger::getRequiresApproval, true)
                .orderByDesc(ActionLedger::getCreateTime);

        if (merchantId != null) {
            wrapper.eq(ActionLedger::getMerchantId, merchantId);
        }

        if (approved == null) {
            wrapper.isNull(ActionLedger::getApproved);
        } else {
            wrapper.eq(ActionLedger::getApproved, approved);
        }

        return actionLedgerMapper.selectPage(page, wrapper);
    }

    /**
     * 统计Tool调用成功率
     *
     * @param toolName Tool名称（可选）
     * @param merchantId 租户ID（可选）
     * @return 成功率（0-1之间）
     */
    public double calculateSuccessRate(String toolName, Long merchantId) {
        LambdaQueryWrapper<ActionLedger> wrapper = new LambdaQueryWrapper<>();

        if (toolName != null && !toolName.isEmpty()) {
            wrapper.eq(ActionLedger::getToolName, toolName);
        }
        if (merchantId != null) {
            wrapper.eq(ActionLedger::getMerchantId, merchantId);
        }

        Long total = actionLedgerMapper.selectCount(wrapper);
        if (total == 0) {
            return 0.0;
        }

        wrapper.eq(ActionLedger::getSuccess, true);
        Long successCount = actionLedgerMapper.selectCount(wrapper);

        return (double) successCount / total;
    }

    /**
     * 将对象序列化为JSON字符串
     */
    private String serializeToJson(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.warn("序列化JSON失败: {}", e.getMessage());
            return obj.toString();
        }
    }
}
