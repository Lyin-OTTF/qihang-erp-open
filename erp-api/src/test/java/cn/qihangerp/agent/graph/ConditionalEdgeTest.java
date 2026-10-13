package cn.qihangerp.agent.graph;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 条件边测试（阶段4-任务26）。
 *
 * <p>验证：条件路由、默认分支、多条件分支、获取所有目标节点。</p>
 *
 * @author 回溯开发-阶段4
 * @since 2026-10-12
 */
class ConditionalEdgeTest {

    @Test
    void route_whenConditionMatches_returnsTarget() {
        GraphState state = new GraphState();
        state.getGlobalContext().put("result", "success");

        ConditionalEdge edge = ConditionalEdge.builder("check")
                .when(s -> "success".equals(s.getGlobalContext().get("result")), "success_node")
                .otherwise("failure_node")
                .build();

        assertEquals("success_node", edge.route(state));
    }

    @Test
    void route_whenNoConditionMatches_returnsDefault() {
        GraphState state = new GraphState();
        state.getGlobalContext().put("result", "unknown");

        ConditionalEdge edge = ConditionalEdge.builder("check")
                .when(s -> "success".equals(s.getGlobalContext().get("result")), "success_node")
                .when(s -> "failure".equals(s.getGlobalContext().get("result")), "failure_node")
                .otherwise("default_node")
                .build();

        assertEquals("default_node", edge.route(state));
    }

    @Test
    void route_multipleConditions_firstMatchWins() {
        GraphState state = new GraphState();
        state.getGlobalContext().put("score", "85");

        ConditionalEdge edge = ConditionalEdge.builder("grade")
                .when(s -> {
                    String score = s.getGlobalContext().get("score");
                    return score != null && Integer.parseInt(score) >= 90;
                }, "A")
                .when(s -> {
                    String score = s.getGlobalContext().get("score");
                    return score != null && Integer.parseInt(score) >= 80;
                }, "B")
                .when(s -> {
                    String score = s.getGlobalContext().get("score");
                    return score != null && Integer.parseInt(score) >= 60;
                }, "C")
                .otherwise("F")
                .build();

        assertEquals("B", edge.route(state));
    }

    @Test
    void route_usesNodeOutputs() {
        GraphState state = new GraphState();
        state.getNodeOutputs().put("validate", "invalid");

        ConditionalEdge edge = ConditionalEdge.builder("validate-result")
                .when(s -> "valid".equals(s.getNodeOutputs().get("validate")), "process")
                .otherwise("reject")
                .build();

        assertEquals("reject", edge.route(state));
    }

    @Test
    void getAllTargets_returnsAllBranches() {
        ConditionalEdge edge = ConditionalEdge.builder("check")
                .when(s -> true, "A")
                .when(s -> false, "B")
                .otherwise("C")
                .build();

        assertEquals(3, edge.getAllTargets().size());
        assertTrue(edge.getAllTargets().contains("A"));
        assertTrue(edge.getAllTargets().contains("B"));
        assertTrue(edge.getAllTargets().contains("C"));
    }

    @Test
    void build_noBranches_throwsException() {
        assertThrows(IllegalStateException.class, () ->
                ConditionalEdge.builder("empty").build());
    }

    @Test
    void build_onlyDefault_works() {
        ConditionalEdge edge = ConditionalEdge.builder("only-default")
                .otherwise("default_node")
                .build();

        GraphState state = new GraphState();
        assertEquals("default_node", edge.route(state));
    }
}
