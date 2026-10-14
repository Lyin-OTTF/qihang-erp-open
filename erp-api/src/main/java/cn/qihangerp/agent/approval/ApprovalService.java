package cn.qihangerp.agent.approval;

import cn.qihangerp.agent.audit.ActionLedgerRequest;
import cn.qihangerp.agent.audit.ActionLedgerService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 审批管理服务（ApprovalManager）。
 *
 * <p>管理 AI Agent 高风险操作的人工审批流程。
 * 第三期加固：createApproval 去重、approve/reject 事务+条件更新、
 * markFailed 清理审批人、expireOldApprovals 定时调度、审批通过落 ActionLedger。</p>
 */
@Slf4j
@Service
public class ApprovalService {

    private final ApprovalRequestMapper approvalMapper;
    private final ObjectMapper objectMapper;
    private final ActionLedgerService actionLedgerService;

    /** 审批默认超时时间（秒） */
    private static final long DEFAULT_APPROVAL_TIMEOUT_SECONDS = 300; // 5分钟

    public ApprovalService(ApprovalRequestMapper approvalMapper,
                           ObjectMapper objectMapper,
                           ActionLedgerService actionLedgerService) {
        this.approvalMapper = approvalMapper;
        this.objectMapper = objectMapper;
        this.actionLedgerService = actionLedgerService;
    }

    /**
     * 创建审批请求（去重：同一 traceId+toolName 已有 PENDING 审批则返回已有 ID）。
     */
    public Long createApproval(String traceId, String toolName, String toolDescription,
                               Long merchantId, Long userId, Object[] args) {
        // 去重检查：同一 traceId + toolName 的 PENDING 审批不重复创建
        LambdaQueryWrapper<ApprovalRequest> dupWrapper = new LambdaQueryWrapper<>();
        dupWrapper.eq(ApprovalRequest::getTraceId, traceId)
                .eq(ApprovalRequest::getToolName, toolName)
                .eq(ApprovalRequest::getStatus, ApprovalRequest.Status.PENDING.name())
                .last("LIMIT 1");
        ApprovalRequest existing = approvalMapper.selectOne(dupWrapper);
        if (existing != null) {
            log.info("审批已存在（去重跳过）: id={}, tool={}, traceId={}", existing.getId(), toolName, traceId);
            return existing.getId();
        }

        ApprovalRequest approval = new ApprovalRequest();
        approval.setTraceId(traceId);
        approval.setToolName(toolName);
        approval.setToolDescription(toolDescription);
        approval.setMerchantId(merchantId);
        approval.setUserId(userId);
        approval.setStatus(ApprovalRequest.Status.PENDING.name());
        approval.setExpiresAt(LocalDateTime.now().plusSeconds(DEFAULT_APPROVAL_TIMEOUT_SECONDS));
        try {
            approval.setInputParams(objectMapper.writeValueAsString(args));
        } catch (JsonProcessingException e) {
            log.warn("序列化审批参数失败: {}", e.getMessage());
            approval.setInputParams("[]");
        }
        approvalMapper.insert(approval);
        log.info("创建审批请求: id={}, tool={}, traceId={}", approval.getId(), toolName, traceId);
        return approval.getId();
    }

    /**
     * 批准审批（事务 + 状态条件更新，防并发覆盖）。
     * 批准成功后落 ActionLedger 审计记录。
     */
    @Transactional
    public boolean approve(Long approvalId, Long approverId, String comment) {
        // 条件更新：仅当状态为 PENDING 时才更新为 APPROVED，防止并发审批覆盖
        LambdaUpdateWrapper<ApprovalRequest> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(ApprovalRequest::getId, approvalId)
                .eq(ApprovalRequest::getStatus, ApprovalRequest.Status.PENDING.name())
                .set(ApprovalRequest::getStatus, ApprovalRequest.Status.APPROVED.name())
                .set(ApprovalRequest::getApproverId, approverId)
                .set(ApprovalRequest::getApproveComment, comment)
                .set(ApprovalRequest::getApprovedAt, LocalDateTime.now());
        int updated = approvalMapper.update(null, wrapper);
        if (updated == 0) {
            log.warn("审批不存在或状态非PENDING: id={}", approvalId);
            return false;
        }
        log.info("审批通过: id={}, approver={}", approvalId, approverId);

        // 审批通过落审计 ledger
        try {
            ApprovalRequest approved = approvalMapper.selectById(approvalId);
            if (approved != null && actionLedgerService != null) {
                ActionLedgerRequest ledger = ActionLedgerRequest.builder()
                        .traceId(approved.getTraceId())
                        .toolName(approved.getToolName())
                        .toolScope("审批")
                        .autonomyLevel("APPROVAL")
                        .merchantId(approved.getMerchantId())
                        .userId(approverId)
                        .sessionId(null)
                        .inputParams(new Object[]{approved.getInputParams()})
                        .outputResult("审批通过: " + (comment != null ? comment : ""))
                        .success(true)
                        .errorMessage(null)
                        .errorType(null)
                        .startTime(LocalDateTime.now())
                        .endTime(LocalDateTime.now())
                        .durationMs(0)
                        .isWriteOperation(false)
                        .requiresApproval(true)
                        .approved(true)
                        .ipAddress(null)
                        .userAgent("approval-ui")
                        .build();
                actionLedgerService.recordAction(ledger);
            }
        } catch (Exception e) {
            log.warn("审批落审计ledger失败（不影响审批结果）: {}", e.getMessage());
        }
        return true;
    }

    /**
     * 拒绝审批（事务 + 状态条件更新）。
     */
    @Transactional
    public boolean reject(Long approvalId, Long approverId, String comment) {
        LambdaUpdateWrapper<ApprovalRequest> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(ApprovalRequest::getId, approvalId)
                .eq(ApprovalRequest::getStatus, ApprovalRequest.Status.PENDING.name())
                .set(ApprovalRequest::getStatus, ApprovalRequest.Status.REJECTED.name())
                .set(ApprovalRequest::getApproverId, approverId)
                .set(ApprovalRequest::getApproveComment, comment)
                .set(ApprovalRequest::getApprovedAt, LocalDateTime.now());
        int updated = approvalMapper.update(null, wrapper);
        if (updated == 0) {
            log.warn("审批拒绝失败（状态非PENDING）: id={}", approvalId);
            return false;
        }
        log.info("审批拒绝: id={}, approver={}", approvalId, approverId);
        return true;
    }

    /**
     * 标记已执行
     */
    public void markExecuted(Long approvalId, String resultSummary) {
        ApprovalRequest approval = new ApprovalRequest();
        approval.setId(approvalId);
        approval.setStatus(ApprovalRequest.Status.EXECUTED.name());
        approval.setExecutedAt(LocalDateTime.now());
        approval.setExecutionResult(resultSummary);
        approvalMapper.updateById(approval);
    }

    /**
     * 标记执行失败（回到 PENDING 允许重试，同时清理 approverId/approvedAt，
     * 避免"已被某人批准但执行失败"的残留状态误导）。
     */
    public void markFailed(Long approvalId, String errorMessage) {
        LambdaUpdateWrapper<ApprovalRequest> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(ApprovalRequest::getId, approvalId)
                .set(ApprovalRequest::getStatus, ApprovalRequest.Status.PENDING.name())
                .set(ApprovalRequest::getApproverId, null)
                .set(ApprovalRequest::getApprovedAt, null)
                .set(ApprovalRequest::getApproveComment, null)
                .set(ApprovalRequest::getErrorMessage, errorMessage);
        approvalMapper.update(null, wrapper);
    }

    /**
     * 检查审批是否已批准（用于 ToolGateway 执行前检查）。
     * 仅匹配 traceId+toolName，不校验参数（兼容旧调用）。
     * 已加入过期判断：expiresAt 已过的审批不算已批准。
     */
    public boolean isApproved(String traceId, String toolName) {
        return isApproved(traceId, toolName, null);
    }

    /**
     * 检查审批是否已批准（带参数摘要匹配 + 过期判断）。
     * 同一会话里"批准取消订单#1"之后，再取消订单#2不会直接执行。
     *
     * @param args 当前执行的工具参数，为 null 时只做 traceId+toolName+过期判断
     */
    public boolean isApproved(String traceId, String toolName, Object[] args) {
        LambdaQueryWrapper<ApprovalRequest> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApprovalRequest::getTraceId, traceId)
                .eq(ApprovalRequest::getToolName, toolName)
                .eq(ApprovalRequest::getStatus, ApprovalRequest.Status.APPROVED.name())
                .gt(ApprovalRequest::getExpiresAt, LocalDateTime.now())
                .orderByDesc(ApprovalRequest::getCreatedAt)
                .last("LIMIT 1");
        ApprovalRequest approval = approvalMapper.selectOne(wrapper);
        if (approval == null) {
            return false;
        }
        // 参数摘要匹配：如果当前调用带了 args，且审批记录里存了 inputParams，则比对
        if (args != null && args.length > 0 && approval.getInputParams() != null) {
            try {
                String currentArgsJson = objectMapper.writeValueAsString(args);
                if (!currentArgsJson.equals(approval.getInputParams())) {
                    log.info("审批参数不匹配，需重新审批: tool={}, traceId={}, 已批准参数={}, 当前参数={}",
                            toolName, traceId, approval.getInputParams(), currentArgsJson);
                    return false;
                }
            } catch (JsonProcessingException e) {
                log.warn("参数摘要比对失败，保守起见要求重新审批: {}", e.getMessage());
                return false;
            }
        }
        return true;
    }

    /**
     * 获取已批准的审批记录（用于执行后标记）。
     */
    public ApprovalRequest getApprovedApproval(String traceId, String toolName) {
        LambdaQueryWrapper<ApprovalRequest> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApprovalRequest::getTraceId, traceId)
                .eq(ApprovalRequest::getToolName, toolName)
                .eq(ApprovalRequest::getStatus, ApprovalRequest.Status.APPROVED.name())
                .orderByDesc(ApprovalRequest::getCreatedAt)
                .last("LIMIT 1");
        return approvalMapper.selectOne(wrapper);
    }

    /**
     * 查询待审批列表（分页）。
     */
    public Page<ApprovalRequest> listPending(Long merchantId, int page, int size) {
        LambdaQueryWrapper<ApprovalRequest> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApprovalRequest::getStatus, ApprovalRequest.Status.PENDING.name());
        if (merchantId != null) {
            wrapper.eq(ApprovalRequest::getMerchantId, merchantId);
        }
        wrapper.orderByDesc(ApprovalRequest::getCreatedAt);
        return approvalMapper.selectPage(new Page<>(page, size), wrapper);
    }

    /**
     * 按 traceId 查询审批记录。
     */
    public List<ApprovalRequest> listByTraceId(String traceId) {
        LambdaQueryWrapper<ApprovalRequest> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApprovalRequest::getTraceId, traceId)
                .orderByDesc(ApprovalRequest::getCreatedAt);
        return approvalMapper.selectList(wrapper);
    }

    /**
     * 按 ID 查询审批记录。
     */
    public ApprovalRequest getById(Long id) {
        return approvalMapper.selectById(id);
    }

    /**
     * 清理过期审批（每 60 秒执行一次）。
     */
    @Scheduled(fixedDelay = 60_000)
    public int expireOldApprovals() {
        LambdaQueryWrapper<ApprovalRequest> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApprovalRequest::getStatus, ApprovalRequest.Status.PENDING.name())
                .lt(ApprovalRequest::getExpiresAt, LocalDateTime.now());
        List<ApprovalRequest> expired = approvalMapper.selectList(wrapper);
        int count = 0;
        for (ApprovalRequest a : expired) {
            a.setStatus(ApprovalRequest.Status.EXPIRED.name());
            approvalMapper.updateById(a);
            count++;
        }
        if (count > 0) {
            log.info("清理过期审批: {} 条", count);
        }
        return count;
    }
}
