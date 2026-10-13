package cn.qihangerp.agent.graph;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * 图状态（可序列化）。
 *
 * <p>保存工作流执行过程中的完整状态，包括：当前节点、节点输出、
 * 全局上下文、激活分支、循环次数等。用于Checkpoint快照和恢复。</p>
 *
 * @author 回溯开发-阶段4
 * @since 2026-10-12
 */
public class GraphState implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 当前节点ID */
    private String currentNodeId;

    /** 节点输出：nodeId -> output（JSON字符串） */
    private Map<String, String> nodeOutputs = new HashMap<>();

    /** 全局上下文变量 */
    private Map<String, String> globalContext = new HashMap<>();

    /** 激活分支（条件边路由结果） */
    private String activeBranch;

    /** 循环次数 */
    private int loopCount;

    /** 重试次数 */
    private int retryCount;

    /** 执行状态 */
    private String status = "RUNNING";

    public GraphState() {}

    public String getCurrentNodeId() { return currentNodeId; }
    public void setCurrentNodeId(String currentNodeId) { this.currentNodeId = currentNodeId; }

    public Map<String, String> getNodeOutputs() { return nodeOutputs; }
    public void setNodeOutputs(Map<String, String> nodeOutputs) { this.nodeOutputs = nodeOutputs; }

    public Map<String, String> getGlobalContext() { return globalContext; }
    public void setGlobalContext(Map<String, String> globalContext) { this.globalContext = globalContext; }

    public String getActiveBranch() { return activeBranch; }
    public void setActiveBranch(String activeBranch) { this.activeBranch = activeBranch; }

    public int getLoopCount() { return loopCount; }
    public void setLoopCount(int loopCount) { this.loopCount = loopCount; }

    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    /**
     * 深拷贝当前状态
     */
    public GraphState copy() {
        GraphState copy = new GraphState();
        copy.currentNodeId = this.currentNodeId;
        copy.nodeOutputs = new HashMap<>(this.nodeOutputs);
        copy.globalContext = new HashMap<>(this.globalContext);
        copy.activeBranch = this.activeBranch;
        copy.loopCount = this.loopCount;
        copy.retryCount = this.retryCount;
        copy.status = this.status;
        return copy;
    }
}
