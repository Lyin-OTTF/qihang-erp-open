package cn.qihangerp.agent.graph;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 状态序列化器。
 *
 * <p>将GraphState序列化为JSON字符串用于持久化，或从JSON反序列化恢复状态。
 * 使用Jackson ObjectMapper，确保跨版本兼容性。</p>
 *
 * @author 回溯开发-阶段4
 * @since 2026-10-12
 */
@Slf4j
@Component
public class StateSerializer {

    private final ObjectMapper objectMapper;

    public StateSerializer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public StateSerializer() {
        this(new ObjectMapper());
    }

    /**
     * 序列化GraphState为JSON字符串
     */
    public String serialize(GraphState state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (JsonProcessingException e) {
            log.error("GraphState序列化失败", e);
            throw new IllegalStateException("状态序列化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从JSON字符串反序列化GraphState
     */
    public GraphState deserialize(String json) {
        if (json == null || json.isBlank()) {
            return new GraphState();
        }
        try {
            return objectMapper.readValue(json, GraphState.class);
        } catch (JsonProcessingException e) {
            log.error("GraphState反序列化失败: {}", json, e);
            throw new IllegalStateException("状态反序列化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 序列化Map为JSON字符串（用于node_outputs和global_context字段）
     */
    public String serializeMap(java.util.Map<String, ?> map) {
        if (map == null || map.isEmpty()) return "{}";
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            log.error("Map序列化失败", e);
            return "{}";
        }
    }

    /**
     * 从JSON字符串反序列化为Map
     */
    @SuppressWarnings("unchecked")
    public java.util.Map<String, String> deserializeMap(String json) {
        if (json == null || json.isBlank()) return new java.util.HashMap<>();
        try {
            return objectMapper.readValue(json, java.util.Map.class);
        } catch (JsonProcessingException e) {
            log.error("Map反序列化失败: {}", json, e);
            return new java.util.HashMap<>();
        }
    }
}
