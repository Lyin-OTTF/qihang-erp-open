package cn.qihangerp.agent.workflow;

import cn.qihangerp.agent.dag.ExecutionContext;
import cn.qihangerp.agent.dag.NodeStatus;
import cn.qihangerp.agent.graph.GraphState;

import java.util.Map;

/**
 * 工作流执行结果。
 *
 * @author 回溯开发-Week7-10
 * @since 2026-10-13
 */
public class WorkflowResult {

    private final String executionId;
    private final Map<String, NodeStatus> nodeStatuses;
    private final Map<String, Object> nodeOutputs;
    private final boolean success;
    private final String summary;

    public WorkflowResult(String executionId, ExecutionContext context) {
        this.executionId = executionId;
        this.nodeStatuses = context.getNodeStatuses();
        this.nodeOutputs = context.getNodeOutputs();
        this.success = !context.hasFailures();
        this.summary = buildSummary(context.getNodeStatuses());
    }

    public WorkflowResult(String executionId, GraphState state) {
        this.executionId = executionId;
        this.nodeStatuses = null;
        this.nodeOutputs = null;
        this.success = "COMPLETED".equals(state.getStatus());
        this.summary = "status=" + state.getStatus() + ", currentNode=" + state.getCurrentNodeId();
    }

    public String getExecutionId() { return executionId; }
    public Map<String, NodeStatus> getNodeStatuses() { return nodeStatuses; }
    public Map<String, Object> getNodeOutputs() { return nodeOutputs; }
    public boolean isSuccess() { return success; }
    public String getSummary() { return summary; }

    private String buildSummary(Map<String, NodeStatus> statuses) {
        long success = statuses.values().stream().filter(s -> s == NodeStatus.SUCCESS).count();
        long failed = statuses.values().stream().filter(s -> s == NodeStatus.FAILED).count();
        long cancelled = statuses.values().stream().filter(s -> s == NodeStatus.CANCELLED).count();
        return String.format("total=%d, success=%d, failed=%d, cancelled=%d",
                statuses.size(), success, failed, cancelled);
    }

    @Override
    public String toString() {
        return "WorkflowResult{executionId='" + executionId + "', success=" + success
                + ", " + summary + "}";
    }
}
