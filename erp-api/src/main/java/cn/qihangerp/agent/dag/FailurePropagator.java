package cn.qihangerp.agent.dag;

import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * 失败传播处理器。
 *
 * <p>当某个节点失败时，自动取消所有依赖该节点（直接或间接）的后续节点，
 * 但不影响与失败节点无依赖关系的独立分支。</p>
 *
 * @author 回溯开发-阶段4
 * @since 2026-10-12
 */
@Slf4j
public class FailurePropagator {

    /**
     * 传播失败：标记所有依赖失败节点的节点为CANCELLED。
     *
     * @param failedNodeId 失败的节点ID
     * @param allNodes 所有节点（id -> DAGNode）
     * @param context 执行上下文
     * @return 被取消的节点ID列表
     */
    public List<String> propagateFailure(String failedNodeId,
                                          Map<String, DAGNode> allNodes,
                                          ExecutionContext context) {
        List<String> cancelled = new ArrayList<>();
        Set<String> affected = new HashSet<>();
        affected.add(failedNodeId);

        // BFS/迭代：找出所有直接或间接依赖失败节点的节点
        boolean changed = true;
        while (changed) {
            changed = false;
            for (DAGNode node : allNodes.values()) {
                if (affected.contains(node.getId())) continue;
                // 如果该节点的任何依赖在affected中，则它也受影响
                for (String dep : node.getDependencies()) {
                    if (affected.contains(dep)) {
                        affected.add(node.getId());
                        changed = true;
                        break;
                    }
                }
            }
        }

        // 标记受影响的未完成节点为CANCELLED（排除失败节点本身）
        for (String nodeId : affected) {
            if (nodeId.equals(failedNodeId)) continue;
            NodeStatus current = context.getNodeStatus(nodeId);
            if (current == NodeStatus.PENDING || current == NodeStatus.RUNNING) {
                context.setNodeStatus(nodeId, NodeStatus.CANCELLED);
                context.setNodeError(nodeId, "依赖节点 " + failedNodeId + " 失败，自动取消");
                cancelled.add(nodeId);
                log.info("失败传播: 节点 {} 因依赖 {} 失败而被取消", nodeId, failedNodeId);
            }
        }

        return cancelled;
    }

    /**
     * 检查节点的所有依赖是否都已成功完成。
     *
     * @param node 待检查节点
     * @param context 执行上下文
     * @return true=所有依赖成功，false=有依赖未成功（失败/取消/未完成）
     */
    public boolean areDependenciesSatisfied(DAGNode node, ExecutionContext context) {
        for (String depId : node.getDependencies()) {
            NodeStatus depStatus = context.getNodeStatus(depId);
            if (depStatus != NodeStatus.SUCCESS && depStatus != NodeStatus.SKIPPED) {
                return false;
            }
        }
        return true;
    }

    /**
     * 检查节点是否因依赖失败而应被取消。
     */
    public boolean shouldBeCancelled(DAGNode node, ExecutionContext context) {
        for (String depId : node.getDependencies()) {
            NodeStatus depStatus = context.getNodeStatus(depId);
            if (depStatus == NodeStatus.FAILED || depStatus == NodeStatus.CANCELLED) {
                return true;
            }
        }
        return false;
    }
}
