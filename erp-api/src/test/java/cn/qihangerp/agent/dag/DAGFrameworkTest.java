package cn.qihangerp.agent.dag;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DAG框架核心能力测试：并行执行、失败传播、补偿回滚、Checkpoint恢复。
 *
 * <p>使用lambda模拟节点，不依赖Spring/DB。</p>
 *
 * @author 回溯开发-Week7-10
 * @since 2026-10-13
 */
class DAGFrameworkTest {

    /**
     * 验证用例1：3个无依赖节点实际并行执行。
     * 每个节点sleep 150ms，串行需450ms，并行应<350ms。
     */
    @Test
    void testParallelExecution() {
        DAGScheduler scheduler = new DAGScheduler(4);
        ExecutionContext context = new ExecutionContext("test-parallel", "exec-1");

        List<DAGNode> nodes = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            final int idx = i;
            nodes.add(DAGNode.builder("node_" + idx)
                    .name("并行节点" + idx)
                    .executor(ctx -> {
                        try { Thread.sleep(150); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                        return "result_" + idx;
                    })
                    .build());
        }

        long start = System.currentTimeMillis();
        scheduler.execute(nodes, context);
        long elapsed = System.currentTimeMillis() - start;

        for (int i = 1; i <= 3; i++) {
            assertEquals(NodeStatus.SUCCESS, context.getNodeStatus("node_" + i),
                    "node_" + i + " 应成功");
        }
        // 串行需450ms，并行应远小于此
        assertTrue(elapsed < 400, "并行执行应<400ms，实际: " + elapsed + "ms");
        scheduler.shutdown();
    }

    /**
     * 验证用例2：节点A失败后，依赖它的B/C被取消，独立的D继续。
     *
     * DAG结构：
     *   A(失败) ──> B ──> C
     *   D(独立)
     */
    @Test
    void testFailurePropagation() {
        DAGScheduler scheduler = new DAGScheduler(4);
        ExecutionContext context = new ExecutionContext("test-failure", "exec-2");

        List<DAGNode> nodes = List.of(
                DAGNode.builder("A").name("失败节点")
                        .executor(ctx -> { throw new RuntimeException("模拟失败"); })
                        .build(),
                DAGNode.builder("B").name("依赖A").dependsOn("A")
                        .executor(ctx -> "B_result")
                        .build(),
                DAGNode.builder("C").name("依赖B").dependsOn("B")
                        .executor(ctx -> "C_result")
                        .build(),
                DAGNode.builder("D").name("独立节点")
                        .executor(ctx -> "D_result")
                        .build()
        );

        scheduler.execute(nodes, context);

        assertEquals(NodeStatus.FAILED, context.getNodeStatus("A"), "A应失败");
        assertEquals(NodeStatus.CANCELLED, context.getNodeStatus("B"), "B应被取消");
        assertEquals(NodeStatus.CANCELLED, context.getNodeStatus("C"), "C应被取消");
        assertEquals(NodeStatus.SUCCESS, context.getNodeStatus("D"), "D应独立成功");
        assertNotNull(context.getNodeError("B"), "B应有错误信息");
        assertTrue(context.getNodeError("B").contains("A"), "B的错误应提及A");
        scheduler.shutdown();
    }

    /**
     * 验证用例3：补偿机制——后续节点失败时，已成功节点的compensator被调用。
     *
     * DAG结构：A(成功,带补偿) ──> B(失败)
     */
    @Test
    void testCompensation() {
        DAGScheduler scheduler = new DAGScheduler(2);
        ExecutionContext context = new ExecutionContext("test-compensate", "exec-3");
        AtomicInteger compensateCount = new AtomicInteger(0);

        List<DAGNode> nodes = List.of(
                DAGNode.builder("A").name("写操作节点")
                        .executor(ctx -> {
                            ctx.put("A_wrote", true);
                            return "A_done";
                        })
                        .compensator(ctx -> {
                            compensateCount.incrementAndGet();
                            ctx.put("A_rolled_back", true);
                            return null;
                        })
                        .build(),
                DAGNode.builder("B").name("后续失败节点").dependsOn("A")
                        .executor(ctx -> { throw new RuntimeException("B失败触发补偿"); })
                        .build()
        );

        scheduler.execute(nodes, context);

        assertEquals(NodeStatus.SUCCESS, context.getNodeStatus("A"));
        assertEquals(NodeStatus.FAILED, context.getNodeStatus("B"));
        assertEquals(1, compensateCount.get(), "A的compensator应被调用1次");
        assertTrue(Boolean.TRUE.equals(context.get("A_rolled_back", Boolean.class)),
                "补偿应标记回滚");
        scheduler.shutdown();
    }

    /**
     * 验证用例4：Checkpoint恢复——已完成节点不重复执行，从PENDING节点继续。
     *
     * 模拟：节点1、2已完成（从快照恢复），节点3、4待执行。
     */
    @Test
    void testCheckpointResume() {
        DAGScheduler scheduler = new DAGScheduler(2);
        ExecutionContext context = new ExecutionContext("test-resume", "exec-4");

        AtomicInteger node1Calls = new AtomicInteger(0);
        AtomicInteger node2Calls = new AtomicInteger(0);
        AtomicInteger node3Calls = new AtomicInteger(0);
        AtomicInteger node4Calls = new AtomicInteger(0);

        // 模拟从Checkpoint恢复：节点1、2已SUCCESS，有输出
        context.setNodeStatus("node1", NodeStatus.SUCCESS);
        context.setNodeOutput("node1", "cached_result_1");
        context.setNodeStatus("node2", NodeStatus.SUCCESS);
        context.setNodeOutput("node2", "cached_result_2");

        List<DAGNode> nodes = List.of(
                DAGNode.builder("node1").name("已完成1")
                        .executor(ctx -> { node1Calls.incrementAndGet(); return "r1"; })
                        .build(),
                DAGNode.builder("node2").name("已完成2").dependsOn("node1")
                        .executor(ctx -> { node2Calls.incrementAndGet(); return "r2"; })
                        .build(),
                DAGNode.builder("node3").name("待执行3").dependsOn("node2")
                        .executor(ctx -> { node3Calls.incrementAndGet(); return "r3"; })
                        .build(),
                DAGNode.builder("node4").name("待执行4").dependsOn("node3")
                        .executor(ctx -> { node4Calls.incrementAndGet(); return "r4"; })
                        .build()
        );

        scheduler.execute(nodes, context);

        // 已完成节点不应重新执行
        assertEquals(0, node1Calls.get(), "node1不应重复执行");
        assertEquals(0, node2Calls.get(), "node2不应重复执行");
        // 待执行节点应执行
        assertEquals(1, node3Calls.get(), "node3应执行1次");
        assertEquals(1, node4Calls.get(), "node4应执行1次");
        // 所有节点最终SUCCESS
        assertEquals(NodeStatus.SUCCESS, context.getNodeStatus("node1"));
        assertEquals(NodeStatus.SUCCESS, context.getNodeStatus("node4"));
        // 恢复的输出保留
        assertEquals("cached_result_1", context.getNodeOutput("node1"));
        scheduler.shutdown();
    }

    /**
     * 验证：DAG存在环时抛出异常。
     */
    @Test
    void testCycleDetection() {
        DAGScheduler scheduler = new DAGScheduler(2);
        ExecutionContext context = new ExecutionContext("test-cycle", "exec-5");

        List<DAGNode> nodes = List.of(
                DAGNode.builder("A").dependsOn("C").executor(ctx -> "A").build(),
                DAGNode.builder("B").dependsOn("A").executor(ctx -> "B").build(),
                DAGNode.builder("C").dependsOn("B").executor(ctx -> "C").build()
        );

        assertThrows(IllegalArgumentException.class, () -> scheduler.execute(nodes, context),
                "循环依赖应抛出异常");
        scheduler.shutdown();
    }
}
