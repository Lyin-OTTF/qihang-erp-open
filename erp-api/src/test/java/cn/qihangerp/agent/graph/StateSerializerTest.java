package cn.qihangerp.agent.graph;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 状态序列化器测试（阶段4-任务27）。
 *
 * <p>验证：GraphState序列化/反序列化、Map序列化/反序列化、空值处理。</p>
 *
 * @author 回溯开发-阶段4
 * @since 2026-10-12
 */
class StateSerializerTest {

    private final StateSerializer serializer = new StateSerializer();

    @Test
    void serialize_thenDeserialize_preservesState() {
        GraphState state = new GraphState();
        state.setCurrentNodeId("node-1");
        state.setActiveBranch("branch-A");
        state.setLoopCount(3);
        state.setRetryCount(1);
        state.setStatus("RUNNING");
        state.getGlobalContext().put("orderId", "ORD-123");
        state.getNodeOutputs().put("node-1", "output-1");

        String json = serializer.serialize(state);
        assertNotNull(json);
        assertTrue(json.contains("node-1"));
        assertTrue(json.contains("ORD-123"));

        GraphState restored = serializer.deserialize(json);
        assertEquals("node-1", restored.getCurrentNodeId());
        assertEquals("branch-A", restored.getActiveBranch());
        assertEquals(3, restored.getLoopCount());
        assertEquals(1, restored.getRetryCount());
        assertEquals("RUNNING", restored.getStatus());
        assertEquals("ORD-123", restored.getGlobalContext().get("orderId"));
        assertEquals("output-1", restored.getNodeOutputs().get("node-1"));
    }

    @Test
    void serialize_emptyState_producesValidJson() {
        GraphState state = new GraphState();
        String json = serializer.serialize(state);
        assertNotNull(json);

        GraphState restored = serializer.deserialize(json);
        assertNull(restored.getCurrentNodeId());
        assertEquals(0, restored.getLoopCount());
        assertTrue(restored.getGlobalContext().isEmpty());
    }

    @Test
    void deserialize_null_returnsEmptyState() {
        GraphState state = serializer.deserialize(null);
        assertNotNull(state);
        assertTrue(state.getGlobalContext().isEmpty());
    }

    @Test
    void deserialize_blank_returnsEmptyState() {
        GraphState state = serializer.deserialize("  ");
        assertNotNull(state);
    }

    @Test
    void serializeMap_thenDeserialize_preservesEntries() {
        Map<String, String> map = new HashMap<>();
        map.put("key1", "value1");
        map.put("key2", "value2");

        String json = serializer.serializeMap(map);
        Map<String, String> restored = serializer.deserializeMap(json);

        assertEquals("value1", restored.get("key1"));
        assertEquals("value2", restored.get("key2"));
        assertEquals(2, restored.size());
    }

    @Test
    void serializeMap_null_returnsEmptyJson() {
        String json = serializer.serializeMap(null);
        assertEquals("{}", json);
    }

    @Test
    void serializeMap_empty_returnsEmptyJson() {
        String json = serializer.serializeMap(new HashMap<>());
        assertEquals("{}", json);
    }

    @Test
    void deserializeMap_null_returnsEmptyMap() {
        Map<String, String> map = serializer.deserializeMap(null);
        assertTrue(map.isEmpty());
    }

    @Test
    void graphState_copy_deepCopies() {
        GraphState original = new GraphState();
        original.setCurrentNodeId("orig");
        original.getGlobalContext().put("key", "value");

        GraphState copy = original.copy();
        copy.setCurrentNodeId("modified");
        copy.getGlobalContext().put("key", "changed");

        assertEquals("orig", original.getCurrentNodeId(), "修改copy不应影响original");
        assertEquals("value", original.getGlobalContext().get("key"), "修改copy的map不应影响original");
    }
}
