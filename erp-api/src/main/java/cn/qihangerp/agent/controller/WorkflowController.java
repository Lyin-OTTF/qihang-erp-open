package cn.qihangerp.agent.controller;

import cn.qihangerp.agent.approval.ApprovalService;
import cn.qihangerp.agent.approval.ApprovalRequest;
import cn.qihangerp.agent.workflow.AgentWorkflowService;
import cn.qihangerp.agent.workflow.WorkflowResult;
import cn.qihangerp.common.AjaxResult;
import cn.qihangerp.security.common.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Agent工作流控制器。
 *
 * <p>暴露DAG工作流的执行、恢复、状态查询接口。</p>
 *
 * @author 回溯开发-Week7-10
 * @since 2026-10-13
 */
@Slf4j
@RestController
@RequestMapping("/api/agent/workflow")
public class WorkflowController {

    private final AgentWorkflowService workflowService;
    private final cn.qihangerp.agent.tenant.TenantAuthorizer tenantAuthorizer;
    private final ApprovalService approvalService;

    public WorkflowController(AgentWorkflowService workflowService,
                              cn.qihangerp.agent.tenant.TenantAuthorizer tenantAuthorizer,
                              ApprovalService approvalService) {
        this.workflowService = workflowService;
        this.tenantAuthorizer = tenantAuthorizer;
        this.approvalService = approvalService;
    }

    /**
     * 创建售后工作流执行审批单。
     */
    @PostMapping("/after-sales/request")
    public AjaxResult requestAfterSales(@RequestBody Map<String, Object> body) {
        Long orderId = toLong(body.get("orderId"));
        Long refundId = toLong(body.get("refundId"));
        if (orderId == null || refundId == null) {
            return AjaxResult.error("orderId和refundId必填");
        }
        Long merchantId = tenantAuthorizer.requireTenant(toLong(body.get("merchantId")));
        String traceId = "workflow-" + UUID.randomUUID();
        Long approvalId = approvalService.createApproval(
                traceId,
                AgentWorkflowService.WORKFLOW_APPROVAL_TOOL,
                "执行订单售后工作流",
                merchantId,
                SecurityUtils.getUserId(),
                new Object[]{orderId, refundId});
        return AjaxResult.success(Map.of(
                "approvalId", approvalId,
                "traceId", traceId,
                "status", "PENDING"
        ));
    }

    /**
     * 执行订单售后处理工作流。
     *
     * @param body 包含 orderId, refundId, approvalId, merchantId
     */
    @PostMapping("/after-sales/execute")
    public AjaxResult executeAfterSales(@RequestBody Map<String, Object> body) {
        Long orderId = toLong(body.get("orderId"));
        Long refundId = toLong(body.get("refundId"));
        Long approvalId = toLong(body.get("approvalId"));
        Long merchantId = tenantAuthorizer.requireTenant(toLong(body.get("merchantId")));

        if (orderId == null || refundId == null) {
            return AjaxResult.error("orderId和refundId必填");
        }
        if (approvalId == null) {
            return AjaxResult.error("approvalId必填，请先申请并完成审批");
        }

        Object[] approvalArgs = new Object[]{orderId, refundId};
        boolean claimed = approvalService.claimApproved(
                approvalId,
                AgentWorkflowService.WORKFLOW_APPROVAL_TOOL,
                merchantId,
                approvalArgs);
        if (!claimed) {
            return AjaxResult.error("审批未通过、已过期或参数不匹配");
        }

        try {
            WorkflowResult result = workflowService.executeAfterSalesWorkflow(
                    orderId, refundId, merchantId, approvalId);
            if (result.isSuccess()) {
                approvalService.markExecuted(approvalId, result.getSummary());
            } else {
                approvalService.markFailed(approvalId, result.getSummary());
            }
            return AjaxResult.success(Map.of(
                    "executionId", result.getExecutionId(),
                    "success", result.isSuccess(),
                    "summary", result.getSummary(),
                    "nodeStatuses", result.getNodeStatuses()
            ));
        } catch (Exception e) {
            approvalService.markFailed(approvalId, e.getMessage());
            log.error("售后工作流执行失败: approvalId={}", approvalId, e);
            return AjaxResult.error("售后工作流执行失败: " + e.getMessage());
        }
    }

    /**
     * 从Checkpoint快照恢复工作流执行。
     */
    @PostMapping("/resume/{executionId}")
    public AjaxResult resumeWorkflow(@PathVariable String executionId,
                                     @RequestBody(required = false) Map<String, Object> body) {
        Long effectiveTenant = tenantAuthorizer.authorize(null);
        Long approvalId = body != null ? toLong(body.get("approvalId")) : null;
        if (approvalId == null) {
            return AjaxResult.error("approvalId必填");
        }
        ApprovalRequest approval = approvalService.getById(approvalId, effectiveTenant);
        if (approval == null
                || !AgentWorkflowService.WORKFLOW_APPROVAL_TOOL.equals(approval.getToolName())
                || !"EXECUTED".equals(approval.getStatus())) {
            return AjaxResult.error("审批单未领取、已失效或不属于当前租户");
        }
        WorkflowResult result = workflowService.resumeWorkflow(
                executionId, effectiveTenant, approvalId);
        return AjaxResult.success(Map.of(
                "executionId", result.getExecutionId(),
                "success", result.isSuccess(),
                "summary", result.getSummary()
        ));
    }

    /**
     * 查询工作流执行状态。
     */
    @GetMapping("/status/{executionId}")
    public AjaxResult getStatus(@PathVariable String executionId) {
        Long effectiveTenant = tenantAuthorizer.authorize(null);
        WorkflowResult result = workflowService.getWorkflowStatus(executionId, effectiveTenant);
        return AjaxResult.success(Map.of(
                "executionId", result.getExecutionId(),
                "success", result.isSuccess(),
                "summary", result.getSummary()
        ));
    }

    private Long toLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).longValue();
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
