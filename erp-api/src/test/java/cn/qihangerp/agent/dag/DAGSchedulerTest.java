package cn.qihangerp.agent.dag;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DAG调度器测试（阶段4-任务24/25/28）。
 *
 * <p>验证：无依赖节点并行执行、依赖顺序、失败传播、重试、补偿机制。</p>
 *
 * @author 回溯开发-阶段4
 * @since 2026-10-12
 */
class DAGSchedulerTest {

    private DAGScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new DAGScheduler(4);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdown();
    }

    private ExecutionContext newContext() {
        return new ExecutionContext("test-workflow", "exec-" + System.nanoTime());
    }

    // ==================== 基本执行 ====================

    @Test
    void execute_singleNode_succeeds() {
        DAGNode node = DAGNode.builder("A")
                .name("节点A")
                .executor(ctx -> {
                    ctx.put("result", "A-done");
                    return "A-output";
                })
                .build();

        ExecutionContext ctx = newContext();
        scheduler.execute(List.of(node), ctx);

        assertEquals(NodeStatus.SUCCESS, ctx.getNodeStatus("A"));
        assertEquals("A-output", ctx.getNodeOutput("A"));
        assertEquals("A-done", ctx.get("result"));
    }

    @Test
    void execute_linearChain_respectsOrder() {
        List<String> executionOrder = new ArrayList<>();

        DAGNode a = DAGNode.builder("A").executor(ctx -> {
            executionOrder.add("A");
            return "A";
        }).build();
        DAGNode b = DAGNode.builder("B").dependsOn("A").executor(ctx -> {
            executionOrder.add("B");
            return "B";
        }).build();
        DAGNode c = DAGNode.builder("C").dependsOn("B").executor(ctx -> {
            executionOrder.add("C");
            return "C";
        }).build();

        ExecutionContext ctx = newContext();
        scheduler.execute(List.of(a, b, c), ctx);

        assertEquals(NodeStatus.SUCCESS, ctx.getNodeStatus("A"));
        assertEquals(NodeStatus.SUCCESS, ctx.getNodeStatus("B"));
        assertEquals(NodeStatus.SUCCESS, ctx.getNodeStatus("C"));
        assertEquals(List.of("A", "B", "C"), executionOrder);
    }

    @Test
    void execute_independentNodes_runInParallel() throws InterruptedException {
        AtomicInteger concurrentCount = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);

        DAGNode a = DAGNode.builder("A").executor(ctx -> {
            int cur = concurrentCount.incrementAndGet();
            maxConcurrent.set(Math.max(maxConcurrent.get(), cur));
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            concurrentCount.decrementAndGet();
            return "A";
        }).build();
        DAGNode b = DAGNode.builder("B").executor(ctx -> {
            int cur = concurrentCount.incrementAndGet();
            maxConcurrent.set(Math.max(maxConcurrent.get(), cur));
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            concurrentCount.decrementAndGet();
            return "B";
        }).build();
        DAGNode c = DAGNode.builder("C").executor(ctx -> {
            int cur = concurrentCount.incrementAndGet();
            maxConcurrent.set(Math.max(maxConcurrent.get(), cur));
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            concurrentCount.decrementAndGet();
            return "C";
        }).build();

        ExecutionContext ctx = newContext();
        long start = System.currentTimeMillis();
        scheduler.execute(List.of(a, b, c), ctx);
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(NodeStatus.SUCCESS, ctx.getNodeStatus("A"));
        assertEquals(NodeStatus.SUCCESS, ctx.getNodeStatus("B"));
        assertEquals(NodeStatus.SUCCESS, ctx.getNodeStatus("C"));
        assertTrue(maxConcurrent.get() >= 2, "应至少有2个节点并行执行，实际最大并发: " + maxConcurrent.get());
        assertTrue(elapsed < 250, "3个并行节点应在~100ms完成，实际: " + elapsed + "ms");
    }

    // ==================== 失败传播 ====================

    @Test
    void execute_failureCancelsDependentNodes() {
        DAGNode a = DAGNode.builder("A").executor(ctx -> {
            throw new RuntimeException("节点A失败");
        }).build();
        DAGNode b = DAGNode.builder("B").dependsOn("A").executor(ctx -> "B").build();
        DAGNode c = DAGNode.builder("C").dependsOn("B").executor(ctx -> "C").build();
        DAGNode d = DAGNode.builder("D").executor(ctx -> "D").build(); // 独立节点

        ExecutionContext ctx = newContext();
        scheduler.execute(List.of(a, b, c, d), ctx);

        assertEquals(NodeStatus.FAILED, ctx.getNodeStatus("A"));
        assertEquals(NodeStatus.CANCELLED, ctx.getNodeStatus("B"), "B依赖A，应被取消");
        assertEquals(NodeStatus.CANCELLED, ctx.getNodeStatus("C"), "C依赖B（已取消），应被取消");
        assertEquals(NodeStatus.SUCCESS, ctx.getNodeStatus("D"), "D独立，应正常执行");
        assertNotNull(ctx.getNodeError("A"));
        assertTrue(ctx.getNodeError("B").contains("A"));
    }

    @Test
    void execute_failureInBranch_doesNotAffectIndependentBranch() {
        // A失败 → B被取消；C独立 → D依赖C正常执行
        DAGNode a = DAGNode.builder("A").executor(ctx -> {
            throw new RuntimeException("A失败");
        }).build();
        DAGNode b = DAGNode.builder("B").dependsOn("A").executor(ctx -> "B").build();
        DAGNode c = DAGNode.builder("C").executor(ctx -> "C").build();
        DAGNode d = DAGNode.builder("D").dependsOn("C").executor(ctx -> "D").build();

        ExecutionContext ctx = newContext();
        scheduler.execute(List.of(a, b, c, d), ctx);

        assertEquals(NodeStatus.FAILED, ctx.getNodeStatus("A"));
        assertEquals(NodeStatus.CANCELLED, ctx.getNodeStatus("B"));
        assertEquals(NodeStatus.SUCCESS, ctx.getNodeStatus("C"));
        assertEquals(NodeStatus.SUCCESS, ctx.getNodeStatus("D"));
    }

    // ==================== 重试 ====================

    @Test
    void execute_nodeWithRetry_eventuallySucceeds() {
        AtomicInteger attempts = new AtomicInteger(0);

        DAGNode flaky = DAGNode.builder("flaky")
                .maxRetries(2)
                .executor(ctx -> {
                    int attempt = attempts.incrementAndGet();
                    if (attempt < 3) {
                        throw new RuntimeException("第" + attempt + "次失败");
                    }
                    return "success-on-3rd";
                }).build();

        ExecutionContext ctx = newContext();
        scheduler.execute(List.of(flaky), ctx);

        assertEquals(NodeStatus.SUCCESS, ctx.getNodeStatus("flaky"));
        assertEquals(3, attempts.get(), "应重试到第3次成功");
        assertEquals("success-on-3rd", ctx.getNodeOutput("flaky"));
    }

    @Test
    void execute_nodeWithRetry_exhaustsRetries() {
        AtomicInteger attempts = new AtomicInteger(0);

        DAGNode alwaysFail = DAGNode.builder("alwaysFail")
                .maxRetries(2)
                .executor(ctx -> {
                    attempts.incrementAndGet();
                    throw new RuntimeException("总是失败");
                }).build();

        ExecutionContext ctx = newContext();
        scheduler.execute(List.of(alwaysFail), ctx);

        assertEquals(NodeStatus.FAILED, ctx.getNodeStatus("alwaysFail"));
        assertEquals(3, attempts.get(), "应执行1次+重试2次=3次");
    }

    // ==================== 补偿机制 ====================

    @Test
    void execute_failureTriggersCompensation() {
        List<String> compensationLog = new ArrayList<>();

        DAGNode a = DAGNode.builder("A")
                .executor(ctx -> {
                    ctx.put("A-data", "created");
                    return "A";
                })
                .compensator(ctx -> {
                    compensationLog.add("rollback-A");
                    return null;
                })
                .build();
        DAGNode b = DAGNode.builder("B")
                .dependsOn("A")
                .executor(ctx -> {
                    throw new RuntimeException("B失败");
                })
                .compensator(ctx -> {
                    compensationLog.add("rollback-B");
                    return null;
                })
                .build();

        ExecutionContext ctx = newContext();
        scheduler.execute(List.of(a, b), ctx);

        assertEquals(NodeStatus.SUCCESS, ctx.getNodeStatus("A"));
        assertEquals(NodeStatus.FAILED, ctx.getNodeStatus("B"));
        // A成功了且有compensator，B失败后应触发A的补偿
        assertTrue(compensationLog.contains("rollback-A"), "应触发A的补偿回滚");
    }

    // ==================== 环检测 ====================

    @Test
    void execute_cyclicGraph_throwsException() {
        DAGNode a = DAGNode.builder("A").dependsOn("B").executor(ctx -> "A").build();
        DAGNode b = DAGNode.builder("B").dependsOn("A").executor(ctx -> "B").build();

        ExecutionContext ctx = newContext();
        assertThrows(IllegalArgumentException.class, () -> scheduler.execute(List.of(a, b), ctx));
    }

    // ==================== 依赖数据传递 ====================

    @Test
    void execute_nodeReadsDependencyOutput() {
        DAGNode a = DAGNode.builder("A").executor(ctx -> 42).build();
        DAGNode b = DAGNode.builder("B").dependsOn("A").executor(ctx -> {
            Integer aOutput = ctx.getNodeOutput("A", Integer.class);
            return aOutput * 2;
        }).build();

        ExecutionContext ctx = newContext();
        scheduler.execute(List.of(a, b), ctx);

        assertEquals(NodeStatus.SUCCESS, ctx.getNodeStatus("B"));
        assertEquals(84, ctx.getNodeOutput("B"));
    }
}
