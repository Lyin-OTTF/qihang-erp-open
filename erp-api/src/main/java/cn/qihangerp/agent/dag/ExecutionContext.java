package cn.qihangerp.agent.dag;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DAG执行上下文。
 *
 * <p>在节点间共享数据，包含：全局上下文变量、各节点输出结果、
 * 节点状态追踪。线程安全，支持并行节点读写。</p>
 *
 * @author 回溯开发-阶段4
 * @since 2026-10-12
 */
public class ExecutionContext {

    /** 全局上下文变量（节点间共享） */
    private final Map<String, Object> globalContext = new ConcurrentHashMap<>();

    /** 各节点输出结果：nodeId -> output */
    private final Map<String, Object> nodeOutputs = new ConcurrentHashMap<>();

    /** 节点状态：nodeId -> NodeStatus */
    private final Map<String, NodeStatus> nodeStatuses = new ConcurrentHashMap<>();

    /** 节点错误信息：nodeId -> errorMessage */
    private final Map<String, String> nodeErrors = new ConcurrentHashMap<>();

    /** 工作流ID */
    private final String workflowId;

    /** 执行ID */
    private final String executionId;

    public ExecutionContext(String workflowId, String executionId) {
        this.workflowId = workflowId;
        this.executionId = executionId;
    }

    public String getWorkflowId() { return workflowId; }
    public String getExecutionId() { return executionId; }

    // ==================== 全局上下文 ====================

    public void put(String key, Object value) {
        globalContext.put(key, value);
    }

    public Object get(String key) {
        return globalContext.get(key);
    }

    public <T> T get(String key, Class<T> type) {
        Object value = globalContext.get(key);
        return type.isInstance(value) ? type.cast(value) : null;
    }

    public Map<String, Object> getGlobalContext() {
        return new ConcurrentHashMap<>(globalContext);
    }

    // ==================== 节点输出 ====================

    public void setNodeOutput(String nodeId, Object output) {
        nodeOutputs.put(nodeId, output);
    }

    public Object getNodeOutput(String nodeId) {
        return nodeOutputs.get(nodeId);
    }

    public <T> T getNodeOutput(String nodeId, Class<T> type) {
        Object output = nodeOutputs.get(nodeId);
        return type.isInstance(output) ? type.cast(output) : null;
    }

    public Map<String, Object> getNodeOutputs() {
        return new ConcurrentHashMap<>(nodeOutputs);
    }

    // ==================== 节点状态 ====================

    public void setNodeStatus(String nodeId, NodeStatus status) {
        nodeStatuses.put(nodeId, status);
    }

    public NodeStatus getNodeStatus(String nodeId) {
        return nodeStatuses.getOrDefault(nodeId, NodeStatus.PENDING);
    }

    public Map<String, NodeStatus> getNodeStatuses() {
        return new ConcurrentHashMap<>(nodeStatuses);
    }

    public void setNodeError(String nodeId, String error) {
        nodeErrors.put(nodeId, error);
    }

    public String getNodeError(String nodeId) {
        return nodeErrors.get(nodeId);
    }

    // ==================== 便捷方法 ====================

    /** 是否所有节点都已完成（成功或跳过或取消） */
    public boolean isAllCompleted() {
        return nodeStatuses.values().stream()
                .allMatch(s -> s == NodeStatus.SUCCESS
                        || s == NodeStatus.SKIPPED
                        || s == NodeStatus.CANCELLED
                        || s == NodeStatus.FAILED);
    }

    /** 是否有节点失败 */
    public boolean hasFailures() {
        return nodeStatuses.values().stream().anyMatch(s -> s == NodeStatus.FAILED);
    }

    /** 获取失败节点列表 */
    public java.util.List<String> getFailedNodes() {
        return nodeStatuses.entrySet().stream()
                .filter(e -> e.getValue() == NodeStatus.FAILED)
                .map(Map.Entry::getKey)
                .toList();
    }
}
