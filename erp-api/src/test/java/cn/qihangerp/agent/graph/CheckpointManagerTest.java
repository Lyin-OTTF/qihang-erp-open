package cn.qihangerp.agent.graph;
import org.junit.jupiter.api.Tag;

import com.mysql.cj.jdbc.MysqlDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 快照管理器集成测试（阶段4-任务27）。
 *
 * <p>使用真实MySQL验证：保存快照、加载最新快照、加载指定版本、
 * 列出版本、更新状态、清理旧版本。</p>
 *
 * @author 回溯开发-阶段4
 * @since 2026-10-12
 */
@Tag("it")
class CheckpointManagerTest {

    private static final String TEST_EXECUTION = "cp-test-" + System.currentTimeMillis();
    private static final String TEST_WORKFLOW = "cp-workflow-test";
    private static CheckpointManager checkpointManager;
    private static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void setup() {
        MysqlDataSource ds = new MysqlDataSource();
        ds.setUrl("jdbc:mysql://127.0.0.1:3306/qihang-erp"
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true");
        ds.setUser("root");
        ds.setPassword("1234");
        jdbcTemplate = new JdbcTemplate((DataSource) ds);
        StateSerializer serializer = new StateSerializer();
        checkpointManager = new CheckpointManager(jdbcTemplate, serializer);
    }

    @AfterAll
    static void cleanup() {
        jdbcTemplate.update("DELETE FROM agent_graph_checkpoint WHERE execution_id LIKE ?", "cp-test-%");
    }

    @Test
    void saveCheckpoint_returnsVersion1() {
        String execId = TEST_EXECUTION + "-save";
        GraphState state = new GraphState();
        state.setCurrentNodeId("node-A");
        state.getGlobalContext().put("key", "value");
        state.getNodeOutputs().put("node-A", "output-A");

        int version = checkpointManager.saveCheckpoint(TEST_WORKFLOW, execId, state);

        assertEquals(1, version, "首次保存版本应为1");
    }

    @Test
    void saveCheckpoint_incrementsVersion() {
        String execId = TEST_EXECUTION + "-increment";
        GraphState state1 = new GraphState();
        state1.setCurrentNodeId("node-1");
        GraphState state2 = new GraphState();
        state2.setCurrentNodeId("node-2");

        int v1 = checkpointManager.saveCheckpoint(TEST_WORKFLOW, execId, state1);
        int v2 = checkpointManager.saveCheckpoint(TEST_WORKFLOW, execId, state2);

        assertEquals(1, v1);
        assertEquals(2, v2);
    }

    @Test
    void loadLatest_returnsMostRecent() {
        String execId = TEST_EXECUTION + "-latest";
        GraphState state1 = new GraphState();
        state1.setCurrentNodeId("node-old");
        GraphState state2 = new GraphState();
        state2.setCurrentNodeId("node-new");
        state2.setLoopCount(5);

        checkpointManager.saveCheckpoint(TEST_WORKFLOW, execId, state1);
        checkpointManager.saveCheckpoint(TEST_WORKFLOW, execId, state2);

        GraphState loaded = checkpointManager.loadLatest(execId);

        assertNotNull(loaded);
        assertEquals("node-new", loaded.getCurrentNodeId());
        assertEquals(5, loaded.getLoopCount());
    }

    @Test
    void loadLatest_nonExistent_returnsNull() {
        GraphState loaded = checkpointManager.loadLatest("non-existent-execution-" + System.nanoTime());
        assertNull(loaded);
    }

    @Test
    void loadByVersion_returnsCorrectVersion() {
        String execId = TEST_EXECUTION + "-byversion";
        GraphState state1 = new GraphState();
        state1.setCurrentNodeId("v1-node");
        GraphState state2 = new GraphState();
        state2.setCurrentNodeId("v2-node");

        checkpointManager.saveCheckpoint(TEST_WORKFLOW, execId, state1);
        checkpointManager.saveCheckpoint(TEST_WORKFLOW, execId, state2);

        GraphState v1 = checkpointManager.loadByVersion(execId, 1);
        GraphState v2 = checkpointManager.loadByVersion(execId, 2);

        assertEquals("v1-node", v1.getCurrentNodeId());
        assertEquals("v2-node", v2.getCurrentNodeId());
    }

    @Test
    void loadByVersion_nonExistent_returnsNull() {
        String execId = TEST_EXECUTION + "-nonexist-ver";
        GraphState state = new GraphState();
        checkpointManager.saveCheckpoint(TEST_WORKFLOW, execId, state);

        GraphState loaded = checkpointManager.loadByVersion(execId, 999);
        assertNull(loaded);
    }

    @Test
    void listVersions_returnsAllVersions() {
        String execId = TEST_EXECUTION + "-list";
        GraphState state = new GraphState();
        checkpointManager.saveCheckpoint(TEST_WORKFLOW, execId, state);
        checkpointManager.saveCheckpoint(TEST_WORKFLOW, execId, state);
        checkpointManager.saveCheckpoint(TEST_WORKFLOW, execId, state);

        List<Integer> versions = checkpointManager.listVersions(execId);
        assertEquals(List.of(1, 2, 3), versions);
    }

    @Test
    void countCheckpoints_returnsCorrectCount() {
        String execId = TEST_EXECUTION + "-count";
        GraphState state = new GraphState();
        checkpointManager.saveCheckpoint(TEST_WORKFLOW, execId, state);
        checkpointManager.saveCheckpoint(TEST_WORKFLOW, execId, state);

        assertEquals(2, checkpointManager.countCheckpoints(execId));
    }

    @Test
    void updateStatus_updatesLatest() {
        String execId = TEST_EXECUTION + "-status";
        GraphState state = new GraphState();
        state.setStatus("RUNNING");
        checkpointManager.saveCheckpoint(TEST_WORKFLOW, execId, state);

        checkpointManager.updateStatus(execId, "COMPLETED");

        GraphState loaded = checkpointManager.loadLatest(execId);
        assertEquals("COMPLETED", loaded.getStatus());
    }

    @Test
    void deleteCheckpoints_removesAll() {
        String execId = TEST_EXECUTION + "-delete";
        GraphState state = new GraphState();
        checkpointManager.saveCheckpoint(TEST_WORKFLOW, execId, state);
        checkpointManager.saveCheckpoint(TEST_WORKFLOW, execId, state);

        int deleted = checkpointManager.deleteCheckpoints(execId);

        assertEquals(2, deleted);
        assertEquals(0, checkpointManager.countCheckpoints(execId));
    }

    @Test
    void cleanupOldVersions_keepsLatestN() {
        String execId = TEST_EXECUTION + "-cleanup";
        GraphState state = new GraphState();
        for (int i = 0; i < 5; i++) {
            checkpointManager.saveCheckpoint(TEST_WORKFLOW, execId, state);
        }

        int deleted = checkpointManager.cleanupOldVersions(execId, 2);

        assertEquals(3, deleted, "应删除3个旧版本");
        assertEquals(2, checkpointManager.countCheckpoints(execId), "应保留最新2个");
        List<Integer> remaining = checkpointManager.listVersions(execId);
        assertEquals(List.of(4, 5), remaining, "应保留版本4和5");
    }

    @Test
    void saveAndLoad_preservesGlobalContext() {
        String execId = TEST_EXECUTION + "-context";
        GraphState state = new GraphState();
        state.getGlobalContext().put("orderId", "ORD-123");
        state.getGlobalContext().put("userId", "user-456");
        state.getNodeOutputs().put("fetch", "order-data");

        checkpointManager.saveCheckpoint(TEST_WORKFLOW, execId, state);
        GraphState loaded = checkpointManager.loadLatest(execId);

        assertEquals("ORD-123", loaded.getGlobalContext().get("orderId"));
        assertEquals("user-456", loaded.getGlobalContext().get("userId"));
        assertEquals("order-data", loaded.getNodeOutputs().get("fetch"));
    }

    @Test
    void saveAndLoad_preservesActiveBranchAndRetryCount() {
        String execId = TEST_EXECUTION + "-branch";
        GraphState state = new GraphState();
        state.setActiveBranch("success-path");
        state.setRetryCount(3);

        checkpointManager.saveCheckpoint(TEST_WORKFLOW, execId, state);
        GraphState loaded = checkpointManager.loadLatest(execId);

        assertEquals("success-path", loaded.getActiveBranch());
        assertEquals(3, loaded.getRetryCount());
    }
}
