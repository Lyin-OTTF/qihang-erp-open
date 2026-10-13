package cn.qihangerp.agent.dag;

import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 确定性并行DAG调度器。
 *
 * <p>核心特性：
 * <ul>
 *   <li>有界线程池并行执行无依赖节点</li>
 *   <li>依赖节点按拓扑顺序执行</li>
 *   <li>失败自动传播，取消受影响分支</li>
 *   <li>失败节点支持重试（maxRetries）</li>
 *   <li>支持补偿机制（compensator回滚）</li>
 *   <li>执行完成后可触发CheckpointManager保存快照</li>
 * </ul>
 * </p>
 *
 * @author 回溯开发-阶段4
 * @since 2026-10-12
 */
@Slf4j
public class DAGScheduler {

    private final ExecutorService executor;
    private final FailurePropagator failurePropagator;
    private final int maxParallelism;

    /**
     * 创建调度器
     *
     * @param maxParallelism 最大并行度
     */
    public DAGScheduler(int maxParallelism) {
        this.maxParallelism = maxParallelism;
        this.executor = Executors.newFixedThreadPool(maxParallelism, r -> {
            Thread t = new Thread(r, "dag-worker");
            t.setDaemon(true);
            return t;
        });
        this.failurePropagator = new FailurePropagator();
    }

    /**
     * 执行DAG工作流。
     *
     * @param nodes 所有节点
     * @param context 执行上下文
     * @return 执行结果（所有节点状态）
     */
    public ExecutionContext execute(List<DAGNode> nodes, ExecutionContext context) {
        Map<String, DAGNode> nodeMap = new LinkedHashMap<>();
        for (DAGNode node : nodes) {
            nodeMap.put(node.getId(), node);
            // 恢复模式：若context中已有该节点状态（从Checkpoint恢复），不覆盖；
            // 否则初始化为PENDING
            if (!context.getNodeStatuses().containsKey(node.getId())) {
                context.setNodeStatus(node.getId(), NodeStatus.PENDING);
            }
        }

        // 验证DAG无环
        validateAcyclic(nodeMap);

        log.info("DAG调度开始: workflow={}, execution={}, 节点数={}, 最大并行度={}",
                context.getWorkflowId(), context.getExecutionId(), nodes.size(), maxParallelism);

        // 主调度循环
        while (!context.isAllCompleted()) {
            // 找出可执行的节点（依赖全部完成且状态为PENDING）
            List<DAGNode> readyNodes = new ArrayList<>();
            for (DAGNode node : nodeMap.values()) {
                if (context.getNodeStatus(node.getId()) != NodeStatus.PENDING) continue;

                // 检查是否应被取消（依赖失败）
                if (failurePropagator.shouldBeCancelled(node, context)) {
                    context.setNodeStatus(node.getId(), NodeStatus.CANCELLED);
                    context.setNodeError(node.getId(), "依赖节点失败，自动取消");
                    continue;
                }

                // 检查依赖是否满足
                if (failurePropagator.areDependenciesSatisfied(node, context)) {
                    readyNodes.add(node);
                }
            }

            if (readyNodes.isEmpty()) {
                // 没有可执行节点，检查是否死锁
                if (hasRunningNodes(context)) {
                    // 有节点在运行，等待
                    sleepQuietly(50);
                    continue;
                }
                // 没有运行中的节点也没有就绪节点，说明剩余都是被阻塞的PENDING
                // （理论上不应发生，因为shouldBeCancelled会处理）
                break;
            }

            // 并行执行就绪节点
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (DAGNode node : readyNodes) {
                context.setNodeStatus(node.getId(), NodeStatus.RUNNING);
                CompletableFuture<Void> future = CompletableFuture.runAsync(
                        () -> executeNodeWithRetry(node, context, nodeMap), executor);
                futures.add(future);
            }

            // 等待当前批次完成
            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            } catch (Exception e) {
                log.error("DAG批次执行异常", e);
            }
        }

        // 执行补偿：对已成功但有失败后续的节点执行rollback
        executeCompensations(nodeMap, context);

        log.info("DAG调度完成: workflow={}, 成功={}, 失败={}, 取消={}",
                context.getWorkflowId(),
                countByStatus(context, NodeStatus.SUCCESS),
                countByStatus(context, NodeStatus.FAILED),
                countByStatus(context, NodeStatus.CANCELLED));

        return context;
    }

    /**
     * 执行单个节点（含重试逻辑）
     */
    private void executeNodeWithRetry(DAGNode node, ExecutionContext context,
                                       Map<String, DAGNode> nodeMap) {
        int attempts = 0;
        int maxAttempts = node.getMaxRetries() + 1;

        while (attempts < maxAttempts) {
            attempts++;
            try {
                log.debug("执行节点: {} (尝试 {}/{})", node.getId(), attempts, maxAttempts);
                Object result = node.getExecutor().apply(context);
                context.setNodeOutput(node.getId(), result);
                context.setNodeStatus(node.getId(), NodeStatus.SUCCESS);
                log.debug("节点执行成功: {}", node.getId());
                return;
            } catch (Exception e) {
                log.warn("节点执行失败: {} (尝试 {}/{}): {}", node.getId(), attempts, maxAttempts, e.getMessage());
                if (attempts >= maxAttempts) {
                    context.setNodeStatus(node.getId(), NodeStatus.FAILED);
                    context.setNodeError(node.getId(), e.getMessage());
                    // 传播失败
                    failurePropagator.propagateFailure(node.getId(), nodeMap, context);
                } else {
                    // 重试前短暂等待
                    sleepQuietly(100L * attempts);
                }
            }
        }
    }

    /**
     * 执行补偿：如果有节点失败，对已成功且有compensator的节点执行回滚
     */
    private void executeCompensations(Map<String, DAGNode> nodeMap, ExecutionContext context) {
        if (!context.hasFailures()) return;

        log.info("检测到失败节点，执行补偿回滚...");
        // 按逆拓扑顺序执行补偿（后执行的先回滚）
        List<DAGNode> reversed = new ArrayList<>(nodeMap.values());
        Collections.reverse(reversed);

        for (DAGNode node : reversed) {
            if (context.getNodeStatus(node.getId()) == NodeStatus.SUCCESS && node.hasCompensator()) {
                try {
                    log.info("执行补偿: {}", node.getId());
                    node.getCompensator().apply(context);
                } catch (Exception e) {
                    log.error("补偿执行失败: {} - {}", node.getId(), e.getMessage());
                }
            }
        }
    }

    /**
     * 验证DAG无环（拓扑排序检测）
     */
    private void validateAcyclic(Map<String, DAGNode> nodeMap) {
        Set<String> visited = new HashSet<>();
        Set<String> recStack = new HashSet<>();

        for (String nodeId : nodeMap.keySet()) {
            if (hasCycle(nodeId, nodeMap, visited, recStack)) {
                throw new IllegalArgumentException("DAG存在循环依赖，涉及节点: " + nodeId);
            }
        }
    }

    private boolean hasCycle(String nodeId, Map<String, DAGNode> nodeMap,
                              Set<String> visited, Set<String> recStack) {
        if (recStack.contains(nodeId)) return true;
        if (visited.contains(nodeId)) return false;

        visited.add(nodeId);
        recStack.add(nodeId);

        DAGNode node = nodeMap.get(nodeId);
        if (node != null) {
            for (String dep : node.getDependencies()) {
                if (nodeMap.containsKey(dep) && hasCycle(dep, nodeMap, visited, recStack)) {
                    return true;
                }
            }
        }
        recStack.remove(nodeId);
        return false;
    }

    private boolean hasRunningNodes(ExecutionContext context) {
        return context.getNodeStatuses().values().stream()
                .anyMatch(s -> s == NodeStatus.RUNNING);
    }

    private long countByStatus(ExecutionContext context, NodeStatus status) {
        return context.getNodeStatuses().values().stream().filter(s -> s == status).count();
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 关闭调度器（释放线程池）
     */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
