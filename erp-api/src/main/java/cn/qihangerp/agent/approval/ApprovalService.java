package cn.qihangerp.agent.approval;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 审批管理服务（ApprovalManager）。
 *
 * <p>管理 AI Agent 高风险操作的人工审批流程：
 * <ol>
 *   <li>createApproval：ToolGateway 检测到 ESCALATE 时创建审批记录</li>
 *   <li>approve/reject：人工审批操作</li>
 *   <li>markExecuted/markFailed：执行后更新状态</li>
 *   <li>查询待审批列表、按 traceId 查询</li>
 * </ol>
 * </p>
 *
 * @author 回溯开发-搁置项攻关
 * @since 2026-10-12
 */
@Slf4j
@Service
public class ApprovalService {

    private final ApprovalRequestMapper approvalMapper;
    private final ObjectMapper objectMapper;

    /** 审批默认超时时间（秒） */
    private static final long DEFAULT_APPROVAL_TIMEOUT_SECONDS = 300; // 5分钟

    public ApprovalService(ApprovalRequestMapper approvalMapper, ObjectMapper objectMapper) {
        this.approvalMapper = approvalMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建审批请求
     *
     * @param traceId 追踪ID
     * @param toolName 工具名称
     * @param toolDescription 工具描述
     * @param merchantId 商户ID
     * @param userId 用户ID
     * @param args 调用参数
     * @return 审批记录ID
     */
    public Long createApproval(String traceId, String toolName, String toolDescription,
                               Long merchantId, Long userId, Object[] args) {
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
     * 批准审批
     */
    public boolean approve(Long approvalId, Long approverId, String comment) {
        ApprovalRequest approval = approvalMapper.selectById(approvalId);
        if (approval == null || !ApprovalRequest.Status.PENDING.name().equals(approval.getStatus())) {
            log.warn("审批不存在或状态非PENDING: id={}", approvalId);
            return false;
        }
        approval.setStatus(ApprovalRequest.Status.APPROVED.name());
        approval.setApproverId(approverId);
        approval.setApproveComment(comment);
        approval.setApprovedAt(LocalDateTime.now());
        approvalMapper.updateById(approval);
        log.info("审批通过: id={}, approver={}", approvalId, approverId);
        return true;
    }

    /**
     * 拒绝审批
     */
    public boolean reject(Long approvalId, Long approverId, String comment) {
        ApprovalRequest approval = approvalMapper.selectById(approvalId);
        if (approval == null || !ApprovalRequest.Status.PENDING.name().equals(approval.getStatus())) {
            return false;
        }
        approval.setStatus(ApprovalRequest.Status.REJECTED.name());
        approval.setApproverId(approverId);
        approval.setApproveComment(comment);
        approval.setApprovedAt(LocalDateTime.now());
        approvalMapper.updateById(approval);
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
     * 标记执行失败
     */
    public void markFailed(Long approvalId, String errorMessage) {
        ApprovalRequest approval = new ApprovalRequest();
        approval.setId(approvalId);
        approval.setStatus(ApprovalRequest.Status.PENDING.name()); // 回到待审批，允许重试
        approval.setErrorMessage(errorMessage);
        approvalMapper.updateById(approval);
    }

    /**
     * 检查审批是否已批准（用于 ToolGateway 执行前检查）
     */
    public boolean isApproved(String traceId, String toolName) {
        LambdaQueryWrapper<ApprovalRequest> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApprovalRequest::getTraceId, traceId)
                .eq(ApprovalRequest::getToolName, toolName)
                .eq(ApprovalRequest::getStatus, ApprovalRequest.Status.APPROVED.name())
                .orderByDesc(ApprovalRequest::getCreatedAt)
                .last("LIMIT 1");
        ApprovalRequest approval = approvalMapper.selectOne(wrapper);
        return approval != null;
    }

    /**
     * 获取已批准的审批记录（用于执行后标记）
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
     * 查询待审批列表（分页）
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
     * 按 traceId 查询审批记录
     */
    public List<ApprovalRequest> listByTraceId(String traceId) {
        LambdaQueryWrapper<ApprovalRequest> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApprovalRequest::getTraceId, traceId)
                .orderByDesc(ApprovalRequest::getCreatedAt);
        return approvalMapper.selectList(wrapper);
    }

    /**
     * 清理过期审批（定时任务调用）
     */
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
