package cn.qihangerp.agent.graph;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 快照管理器（CheckpointManager）。
 *
 * <p>将工作流执行状态持久化到 agent_graph_checkpoint 表，支持：
 * <ul>
 *   <li>saveCheckpoint：保存当前执行快照</li>
 *   <li>loadLatest：加载最新快照用于中断恢复</li>
 *   <li>loadByVersion：加载指定版本快照</li>
 *   <li>listCheckpoints：列出某执行的所有快照</li>
 *   <li>deleteCheckpoints：清理某工作流的历史快照</li>
 * </ul>
 * </p>
 *
 * @author 回溯开发-阶段4
 * @since 2026-10-12
 */
@Slf4j
@Component
public class CheckpointManager {

    private final JdbcTemplate jdbcTemplate;
    private final StateSerializer serializer;

    public CheckpointManager(JdbcTemplate jdbcTemplate, StateSerializer stateSerializer) {
        this.jdbcTemplate = jdbcTemplate;
        this.serializer = stateSerializer;
    }

    /**
     * 保存检查点。
     *
     * @param workflowId 工作流ID
     * @param executionId 执行ID
     * @param state 当前图状态
     * @return 快照版本号
     */
    public synchronized int saveCheckpoint(String workflowId, String executionId, GraphState state) {
        // synchronized：DAG 的节点完成回调在 dag-worker 线程里触发，同一批次多个节点可能并发保存，
        // 先查 MAX(version) 再插入会产生竞态（撞 uk_execution_version）。checkpoint 写入频率低，
        // 单实例内串行化即可；多实例部署需改成数据库原子递增。
        // 获取当前最大版本号
        Integer maxVersion = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(checkpoint_version), 0) FROM agent_graph_checkpoint WHERE execution_id = ?",
                Integer.class, executionId);
        int newVersion = (maxVersion == null ? 0 : maxVersion) + 1;

        String nodeOutputsJson = serializer.serializeMap(state.getNodeOutputs());
        String globalContextJson = serializer.serializeMap(state.getGlobalContext());

        jdbcTemplate.update(
                "INSERT INTO agent_graph_checkpoint " +
                        "(workflow_id, execution_id, checkpoint_version, current_node_id, " +
                        " node_outputs, global_context, active_branch, loop_count, retry_count, status) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                workflowId, executionId, newVersion,
                state.getCurrentNodeId(),
                nodeOutputsJson,
                globalContextJson,
                state.getActiveBranch(),
                state.getLoopCount(),
                state.getRetryCount(),
                state.getStatus());

        log.info("保存检查点: workflow={}, execution={}, version={}, node={}",
                workflowId, executionId, newVersion, state.getCurrentNodeId());
        return newVersion;
    }

    /**
     * 加载最新快照。
     *
     * @param executionId 执行ID
     * @return GraphState，如果没有快照返回null
     */
    public GraphState loadLatest(String executionId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM agent_graph_checkpoint WHERE execution_id = ? " +
                        "ORDER BY checkpoint_version DESC LIMIT 1",
                executionId);

        if (rows.isEmpty()) {
            log.debug("未找到快照: execution={}", executionId);
            return null;
        }

        return mapRowToState(rows.get(0));
    }

    /**
     * 加载指定版本快照。
     */
    public GraphState loadByVersion(String executionId, int version) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM agent_graph_checkpoint WHERE execution_id = ? AND checkpoint_version = ?",
                executionId, version);

        if (rows.isEmpty()) {
            log.warn("未找到指定版本快照: execution={}, version={}", executionId, version);
            return null;
        }

        return mapRowToState(rows.get(0));
    }

    /**
     * 列出某执行的所有快照版本号。
     */
    public List<Integer> listVersions(String executionId) {
        return jdbcTemplate.queryForList(
                "SELECT checkpoint_version FROM agent_graph_checkpoint WHERE execution_id = ? ORDER BY checkpoint_version",
                Integer.class, executionId);
    }

    /**
     * 获取快照数量。
     */
    public int countCheckpoints(String executionId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_graph_checkpoint WHERE execution_id = ?",
                Integer.class, executionId);
        return count != null ? count : 0;
    }

    /**
     * 更新快照状态（标记完成/失败）。
     */
    public void updateStatus(String executionId, String status) {
        jdbcTemplate.update(
                "UPDATE agent_graph_checkpoint SET status = ? WHERE execution_id = ? " +
                        "AND checkpoint_version = (SELECT MAX(v) FROM " +
                        "(SELECT checkpoint_version as v FROM agent_graph_checkpoint WHERE execution_id = ?) as t)",
                status, executionId, executionId);
        log.info("更新快照状态: execution={}, status={}", executionId, status);
    }

    /**
     * 删除某执行的所有快照。
     */
    public int deleteCheckpoints(String executionId) {
        int deleted = jdbcTemplate.update(
                "DELETE FROM agent_graph_checkpoint WHERE execution_id = ?", executionId);
        log.info("删除快照: execution={}, 数量={}", executionId, deleted);
        return deleted;
    }

    /**
     * 清理过期快照（保留最新N个版本）。
     */
    public int cleanupOldVersions(String executionId, int keepLatest) {
        List<Integer> versions = listVersions(executionId);
        if (versions.size() <= keepLatest) return 0;

        int toDelete = versions.size() - keepLatest;
        List<Integer> oldVersions = versions.subList(0, toDelete);
        for (int v : oldVersions) {
            jdbcTemplate.update(
                    "DELETE FROM agent_graph_checkpoint WHERE execution_id = ? AND checkpoint_version = ?",
                    executionId, v);
        }
        log.info("清理旧快照: execution={}, 删除{}个", executionId, toDelete);
        return toDelete;
    }

    private GraphState mapRowToState(Map<String, Object> row) {
        GraphState state = new GraphState();
        state.setCurrentNodeId((String) row.get("current_node_id"));
        state.setNodeOutputs(serializer.deserializeMap((String) row.get("node_outputs")));
        state.setGlobalContext(serializer.deserializeMap((String) row.get("global_context")));
        state.setActiveBranch((String) row.get("active_branch"));
        state.setLoopCount(row.get("loop_count") != null ? ((Number) row.get("loop_count")).intValue() : 0);
        state.setRetryCount(row.get("retry_count") != null ? ((Number) row.get("retry_count")).intValue() : 0);
        state.setStatus((String) row.get("status"));
        return state;
    }
}
