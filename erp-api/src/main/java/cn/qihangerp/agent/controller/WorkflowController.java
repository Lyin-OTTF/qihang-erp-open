package cn.qihangerp.agent.controller;

import cn.qihangerp.agent.workflow.AgentWorkflowService;
import cn.qihangerp.agent.workflow.WorkflowResult;
import cn.qihangerp.common.AjaxResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

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

    public WorkflowController(AgentWorkflowService workflowService,
                              cn.qihangerp.agent.tenant.TenantAuthorizer tenantAuthorizer) {
        this.workflowService = workflowService;
        this.tenantAuthorizer = tenantAuthorizer;
    }

    /**
     * 执行订单售后处理工作流。
     *
     * @param body 包含 orderId, refundId, merchantId
     */
    @PostMapping("/after-sales/execute")
    public AjaxResult executeAfterSales(@RequestBody Map<String, Object> body) {
        Long orderId = toLong(body.get("orderId"));
        Long refundId = toLong(body.get("refundId"));
        Long merchantId = tenantAuthorizer.requireTenant(toLong(body.get("merchantId")));

        if (orderId == null || refundId == null) {
            return AjaxResult.error("orderId和refundId必填");
        }

        WorkflowResult result = workflowService.executeAfterSalesWorkflow(orderId, refundId, merchantId);
        return AjaxResult.success(Map.of(
                "executionId", result.getExecutionId(),
                "success", result.isSuccess(),
                "summary", result.getSummary(),
                "nodeStatuses", result.getNodeStatuses()
        ));
    }

    /**
     * 从Checkpoint快照恢复工作流执行。
     */
    @PostMapping("/resume/{executionId}")
    public AjaxResult resumeWorkflow(@PathVariable String executionId) {
        WorkflowResult result = workflowService.resumeWorkflow(executionId);
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
        WorkflowResult result = workflowService.getWorkflowStatus(executionId);
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
