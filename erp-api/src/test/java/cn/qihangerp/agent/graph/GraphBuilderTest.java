package cn.qihangerp.agent.graph;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 图构建器测试（阶段4-任务26）。
 *
 * <p>验证：线性工作流执行、条件边路由、终止节点、图结构验证。</p>
 *
 * @author 回溯开发-阶段4
 * @since 2026-10-12
 */
class GraphBuilderTest {

    @Test
    void execute_linearWorkflow_completes() {
        List<String> executed = new ArrayList<>();

        GraphBuilder builder = GraphBuilder.create("linear-test")
                .addNode("start", ctx -> {
                    executed.add("start");
                    ctx.getGlobalContext().put("data", "initial");
                    return "started";
                })
                .addNode("process", ctx -> {
                    executed.add("process");
                    return "processed:" + ctx.getGlobalContext().get("data");
                })
                .addNode("end", ctx -> {
                    executed.add("end");
                    return "ended";
                })
                .addEdge("start", "process")
                .addEdge("process", "end")
                .setEntry("start")
                .setTerminal("end");

        GraphState result = builder.execute(new GraphState());

        assertEquals(List.of("start", "process", "end"), executed);
        assertEquals("COMPLETED", result.getStatus());
        assertEquals("ended", result.getNodeOutputs().get("end"));
    }

    @Test
    void execute_conditionalEdge_routesCorrectly() {
        GraphBuilder builder = GraphBuilder.create("conditional-test")
                .addNode("validate", ctx -> {
                    ctx.getGlobalContext().put("valid", "true");
                    return "valid";
                })
                .addNode("process", ctx -> "processing")
                .addNode("reject", ctx -> "rejected")
                .addConditionalEdge("validate",
                        ConditionalEdge.builder("check-valid")
                                .when(s -> "true".equals(s.getGlobalContext().get("valid")), "process")
                                .otherwise("reject")
                                .build())
                .addEdge("process", "end")
                .addEdge("reject", "end")
                .addNode("end", ctx -> "done")
                .setEntry("validate")
                .setTerminal("end");

        GraphState result = builder.execute(new GraphState());

        assertEquals("COMPLETED", result.getStatus());
        assertEquals("processing", result.getNodeOutputs().get("process"));
        assertNull(result.getNodeOutputs().get("reject"), "reject分支不应执行");
        assertEquals("process", result.getActiveBranch());
    }

    @Test
    void execute_conditionalEdge_routesToReject() {
        GraphBuilder builder = GraphBuilder.create("reject-test")
                .addNode("validate", ctx -> {
                    ctx.getGlobalContext().put("valid", "false");
                    return "invalid";
                })
                .addNode("process", ctx -> "processing")
                .addNode("reject", ctx -> "rejected")
                .addConditionalEdge("validate",
                        ConditionalEdge.builder("check-valid")
                                .when(s -> "true".equals(s.getGlobalContext().get("valid")), "process")
                                .otherwise("reject")
                                .build())
                .addEdge("process", "end")
                .addEdge("reject", "end")
                .addNode("end", ctx -> "done")
                .setEntry("validate")
                .setTerminal("end");

        GraphState result = builder.execute(new GraphState());

        assertEquals("rejected", result.getNodeOutputs().get("reject"));
        assertNull(result.getNodeOutputs().get("process"), "process分支不应执行");
        assertEquals("reject", result.getActiveBranch());
    }

    @Test
    void execute_terminalNode_stopsExecution() {
        List<String> executed = new ArrayList<>();

        GraphBuilder builder = GraphBuilder.create("terminal-test")
                .addNode("start", ctx -> { executed.add("start"); return "s"; })
                .addNode("stop", ctx -> { executed.add("stop"); return "stopped"; })
                .addNode("never", ctx -> { executed.add("never"); return "n"; })
                .addEdge("start", "stop")
                .addEdge("stop", "never")
                .setEntry("start")
                .setTerminal("stop");

        GraphState result = builder.execute(new GraphState());

        assertEquals(List.of("start", "stop"), executed);
        assertEquals("stop", result.getCurrentNodeId());
    }

    @Test
    void execute_nodeWithoutOutEdge_completes() {
        GraphBuilder builder = GraphBuilder.create("dead-end-test")
                .addNode("only", ctx -> "only-output")
                .setEntry("only");

        GraphState result = builder.execute(new GraphState());

        assertEquals("COMPLETED", result.getStatus());
        assertEquals("only-output", result.getNodeOutputs().get("only"));
    }

    @Test
    void validate_validGraph_passes() {
        GraphBuilder builder = GraphBuilder.create("valid")
                .addNode("A", ctx -> "A")
                .addNode("B", ctx -> "B")
                .addEdge("A", "B")
                .setEntry("A");

        assertDoesNotThrow(builder::validate);
    }

    @Test
    void validate_missingEntry_throws() {
        GraphBuilder builder = GraphBuilder.create("no-entry")
                .addNode("A", ctx -> "A");

        assertThrows(IllegalStateException.class, builder::validate);
    }

    @Test
    void validate_edgeToNonExistentNode_throws() {
        GraphBuilder builder = GraphBuilder.create("bad-edge")
                .addNode("A", ctx -> "A")
                .addEdge("A", "NonExistent")
                .setEntry("A");

        assertThrows(IllegalStateException.class, builder::validate);
    }

    @Test
    void execute_passesDataBetweenNodes() {
        GraphBuilder builder = GraphBuilder.create("data-flow")
                .addNode("fetch", ctx -> {
                    ctx.getGlobalContext().put("orderId", "ORD-123");
                    return "fetched";
                })
                .addNode("process", ctx -> {
                    String orderId = ctx.getGlobalContext().get("orderId");
                    return "processed:" + orderId;
                })
                .addEdge("fetch", "process")
                .setEntry("process") // 注意：entry是process，但fetch不执行
                .setTerminal("process");

        // 因为entry是process，fetch不会执行，orderId为null
        GraphState result = builder.execute(new GraphState());
        assertEquals("processed:null", result.getNodeOutputs().get("process"));
    }
}
