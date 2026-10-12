package cn.qihangerp.agent.memory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 分层记忆 MySQL 持久化仓库
 *
 * 负责 USER / TEAM 长期记忆落库（WORKING / SESSION 主要在 Redis）。
 * 使用主数据源 JdbcTemplate，与 RagEvaluator 风格一致。
 *
 * @author Doubao
 * @since 2026-10-12
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 插入或更新（以 memory_id 唯一键做 upsert）
     */
    private static final String UPSERT_SQL =
            "INSERT INTO agent_memory " +
            "(memory_id, layer, scope_type, scope_id, `key`, value, value_type, " +
            " ttl_seconds, expires_at, priority, access_count, merchant_id, user_id, created_at, updated_at) " +
            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,NOW(),NOW()) " +
            "ON DUPLICATE KEY UPDATE " +
            " value=VALUES(value), value_type=VALUES(value_type), ttl_seconds=VALUES(ttl_seconds), " +
            " expires_at=VALUES(expires_at), priority=VALUES(priority), access_count=VALUES(access_count), " +
            " updated_at=NOW()";

    /**
     * 按 memory_id 查询
     */
    private static final String FIND_SQL =
            "SELECT memory_id, layer, scope_type, scope_id, `key`, value, value_type, " +
            " ttl_seconds, expires_at, priority, access_count, merchant_id, user_id, created_at, updated_at " +
            "FROM agent_memory WHERE memory_id = ?";

    private static final String DELETE_SQL = "DELETE FROM agent_memory WHERE memory_id = ?";

    private static final String DELETE_EXPIRED_SQL =
            "DELETE FROM agent_memory WHERE expires_at IS NOT NULL AND expires_at < NOW()";

    /**
     * 保存（新增或覆盖）
     */
    public void save(MemoryEntry e) {
        jdbcTemplate.update(UPSERT_SQL,
                e.getMemoryId(),
                e.getLayer() == null ? null : e.getLayer().name(),
                e.getScopeType(),
                e.getScopeId(),
                e.getKey(),
                e.getValue(),
                e.getValueType(),
                e.getTtlSeconds(),
                e.getExpiresAt(),
                e.getPriority(),
                e.getAccessCount(),
                e.getMerchantId(),
                e.getUserId());
        log.debug("记忆已落库: {}", e.getMemoryId());
    }

    /**
     * 按主键 memory_id 查询，不存在返回 null
     */
    public MemoryEntry findById(String memoryId) {
        List<MemoryEntry> list = jdbcTemplate.query(FIND_SQL, ENTRY_MAPPER, memoryId);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 删除
     */
    public void deleteById(String memoryId) {
        jdbcTemplate.update(DELETE_SQL, memoryId);
    }

    /**
     * 清理所有已过期记忆，返回删除条数（定时任务调用）
     */
    public int deleteExpired() {
        int n = jdbcTemplate.update(DELETE_EXPIRED_SQL);
        log.info("清理过期长期记忆: {} 条", n);
        return n;
    }

    /**
     * ResultSet -> MemoryEntry 映射
     */
    private static final RowMapper<MemoryEntry> ENTRY_MAPPER = new RowMapper<MemoryEntry>() {
        @Override
        public MemoryEntry mapRow(ResultSet rs, int rowNum) throws SQLException {
            MemoryEntry e = new MemoryEntry();
            e.setMemoryId(rs.getString("memory_id"));
            String layer = rs.getString("layer");
            if (layer != null) {
                e.setLayer(MemoryLayer.valueOf(layer));
            }
            e.setScopeType(rs.getString("scope_type"));
            e.setScopeId(rs.getString("scope_id"));
            e.setKey(rs.getString("key"));
            e.setValue(rs.getString("value"));
            e.setValueType(rs.getString("value_type"));
            e.setTtlSeconds((Integer) rs.getObject("ttl_seconds"));
            e.setExpiresAt(rs.getObject("expires_at", LocalDateTime.class));
            e.setPriority((Integer) rs.getObject("priority"));
            e.setAccessCount((Integer) rs.getObject("access_count"));
            e.setMerchantId((Long) rs.getObject("merchant_id"));
            e.setUserId((Long) rs.getObject("user_id"));
            e.setCreatedAt(rs.getObject("created_at", LocalDateTime.class));
            e.setUpdatedAt(rs.getObject("updated_at", LocalDateTime.class));
            return e;
        }
    };
}
