package cn.qihangerp.agent.graph;

import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.function.Function;

/**
 * 图构建器（GraphBuilder）。
 *
 * <p>构建包含条件边的工作流图，支持：
 * <ul>
 *   <li>添加节点（带执行函数）</li>
 *   <li>添加线性边（A→B）</li>
 *   <li>添加条件边（A→if/else→B/C）</li>
 *   <li>设置入口节点和结束节点</li>
 *   <li>execute：按图结构执行，支持Checkpoint</li>
 * </ul>
 * </p>
 *
 * <p>使用示例：
 * <pre>
 * GraphBuilder builder = GraphBuilder.create("order-workflow")
 *     .addNode("validate", ctx -> "valid")
 *     .addNode("process", ctx -> "processed")
 *     .addNode("notify", ctx -> "notified")
 *     .addNode("reject", ctx -> "rejected")
 *     .addEdge("validate", "decision")
 *     .addConditionalEdge("decision",
 *         ConditionalEdge.builder("check")
 *             .when(s -> "valid".equals(s.getGlobalContext().get("result")), "process")
 *             .otherwise("reject")
 *             .build())
 *     .addEdge("process", "notify")
 *     .setEntry("validate")
 *     .setTerminal("notify", "reject");
 * GraphState result = builder.execute(initialState);
 * </pre>
 * </p>
 *
 * @author 回溯开发-阶段4
 * @since 2026-10-12
 */
@Slf4j
public class GraphBuilder {

    private final String workflowId;
    private final Map<String, Function<GraphState, String>> nodes = new LinkedHashMap<>();
    private final Map<String, String> linearEdges = new HashMap<>(); // from -> to
    private final Map<String, ConditionalEdge> conditionalEdges = new HashMap<>(); // from -> conditionalEdge
    private String entryNode;
    private final Set<String> terminalNodes = new HashSet<>();
    private CheckpointManager checkpointManager;
    private int checkpointInterval = 0; // 0=不自动保存

    private GraphBuilder(String workflowId) {
        this.workflowId = workflowId;
    }

    public static GraphBuilder create(String workflowId) {
        return new GraphBuilder(workflowId);
    }

    public GraphBuilder addNode(String nodeId, Function<GraphState, String> executor) {
        nodes.put(nodeId, executor);
        return this;
    }

    public GraphBuilder addEdge(String from, String to) {
        linearEdges.put(from, to);
        return this;
    }

    public GraphBuilder addConditionalEdge(String from, ConditionalEdge edge) {
        conditionalEdges.put(from, edge);
        return this;
    }

    public GraphBuilder setEntry(String nodeId) {
        this.entryNode = nodeId;
        return this;
    }

    public GraphBuilder setTerminal(String... nodeIds) {
        terminalNodes.addAll(Arrays.asList(nodeIds));
        return this;
    }

    public GraphBuilder withCheckpoint(CheckpointManager manager, int interval) {
        this.checkpointManager = manager;
        this.checkpointInterval = interval;
        return this;
    }

    /**
     * 执行工作流图。
     *
     * @param initialState 初始状态
     * @return 最终状态
     */
    public GraphState execute(GraphState initialState) {
        if (entryNode == null) {
            throw new IllegalStateException("必须设置入口节点");
        }

        String executionId = workflowId + "-" + System.currentTimeMillis();
        GraphState state = initialState != null ? initialState : new GraphState();
        String currentNode = entryNode;
        int steps = 0;
        int maxSteps = 1000; // 防死循环

        log.info("工作流开始: workflow={}, execution={}, entry={}", workflowId, executionId, entryNode);

        while (currentNode != null && steps < maxSteps) {
            if (!nodes.containsKey(currentNode)) {
                throw new IllegalStateException("节点不存在: " + currentNode);
            }

            // 执行当前节点
            state.setCurrentNodeId(currentNode);
            log.debug("执行节点: {}", currentNode);
            String result = nodes.get(currentNode).apply(state);
            state.getNodeOutputs().put(currentNode, result);
            state.getGlobalContext().put("lastResult", result);

            // 如果是终止节点，执行后停止
            if (terminalNodes.contains(currentNode)) {
                log.info("到达终止节点: {}", currentNode);
                break;
            }

            // 自动保存检查点
            if (checkpointManager != null && checkpointInterval > 0 && steps % checkpointInterval == 0) {
                checkpointManager.saveCheckpoint(workflowId, executionId, state);
            }

            // 路由到下一个节点
            if (conditionalEdges.containsKey(currentNode)) {
                ConditionalEdge edge = conditionalEdges.get(currentNode);
                currentNode = edge.route(state);
                state.setActiveBranch(currentNode);
                log.debug("条件路由: {} -> {}", edge.getName(), currentNode);
            } else if (linearEdges.containsKey(currentNode)) {
                currentNode = linearEdges.get(currentNode);
            } else {
                // 没有出边，结束
                log.debug("节点 {} 无出边，工作流结束", currentNode);
                break;
            }

            steps++;
        }

        if (steps >= maxSteps) {
            log.warn("工作流达到最大步数限制: {}", maxSteps);
            state.setStatus("FAILED");
        } else {
            state.setStatus("COMPLETED");
        }

        // 最终保存检查点
        if (checkpointManager != null) {
            checkpointManager.saveCheckpoint(workflowId, executionId, state);
        }

        log.info("工作流完成: workflow={}, 步数={}, 状态={}", workflowId, steps, state.getStatus());
        return state;
    }

    /**
     * 从检查点恢复执行。
     *
     * @param executionId 执行ID
     * @return 恢复后的状态（不自动继续执行，需调用方决定）
     */
    public GraphState resumeFromCheckpoint(String executionId) {
        if (checkpointManager == null) {
            throw new IllegalStateException("未配置CheckpointManager");
        }
        GraphState state = checkpointManager.loadLatest(executionId);
        if (state == null) {
            throw new IllegalStateException("未找到执行快照: " + executionId);
        }
        log.info("从检查点恢复: execution={}, node={}", executionId, state.getCurrentNodeId());
        return state;
    }

    /**
     * 验证图结构完整性。
     */
    public void validate() {
        if (entryNode == null) {
            throw new IllegalStateException("未设置入口节点");
        }
        if (!nodes.containsKey(entryNode)) {
            throw new IllegalStateException("入口节点不存在: " + entryNode);
        }
        // 验证所有边的目标节点存在
        for (Map.Entry<String, String> edge : linearEdges.entrySet()) {
            if (!nodes.containsKey(edge.getValue())) {
                throw new IllegalStateException("边 " + edge.getKey() + " -> " + edge.getValue() + " 的目标节点不存在");
            }
        }
        for (Map.Entry<String, ConditionalEdge> edge : conditionalEdges.entrySet()) {
            for (String target : edge.getValue().getAllTargets()) {
                if (!nodes.containsKey(target)) {
                    throw new IllegalStateException("条件边 " + edge.getKey() + " 的目标节点不存在: " + target);
                }
            }
        }
        log.info("图结构验证通过: workflow={}, 节点数={}", workflowId, nodes.size());
    }

    public String getWorkflowId() { return workflowId; }
    public Set<String> getNodeIds() { return nodes.keySet(); }
}
