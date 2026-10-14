package cn.qihangerp.agent.rag;
import org.junit.jupiter.api.Tag;

import com.mysql.cj.jdbc.MysqlDataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MySQL全文检索器集成测试（搁置项攻关-任务14，PGVector降级方案）。
 *
 * <p>使用真实 MySQL + FULLTEXT索引(ngram分词器) 验证：中文检索、
 * 相关性排序、空查询处理、租户隔离、可用性检测。</p>
 *
 * <p>前置条件：rag_document表已有FULLTEXT索引，且包含至少18篇SOP文档。</p>
 *
 * @author 回溯开发-搁置项攻关
 * @since 2026-10-12
 */
@Tag("it")
class MysqlFullTextRetrieverTest {

    private static MysqlFullTextRetriever retriever;
    private static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void setup() {
        MysqlDataSource ds = new MysqlDataSource();
        ds.setUrl("jdbc:mysql://127.0.0.1:3306/qihang-erp"
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true");
        ds.setUser("root");
        ds.setPassword("1234");
        jdbcTemplate = new JdbcTemplate((DataSource) ds);
        retriever = new MysqlFullTextRetriever(jdbcTemplate);
    }

    @Test
    void isAvailable_returnsTrue_whenIndexExists() {
        assertTrue(retriever.isAvailable(), "FULLTEXT索引应存在");
    }

    @Test
    void retrieve_chineseQuery_returnsResults() {
        List<RetrievalResult> results = retriever.retrieve("退款流程", 5, 1L);
        assertNotNull(results);
        assertFalse(results.isEmpty(), "中文查询'退款流程'应返回结果");
        assertTrue(results.size() <= 5, "不应超过topK");

        // 验证结果包含退款相关内容
        boolean hasRefund = results.stream()
                .anyMatch(r -> r.getContent() != null &&
                        (r.getContent().contains("退款") || r.getContent().contains("售后")));
        assertTrue(hasRefund, "检索结果应包含退款或售后相关内容");
    }

    @Test
    void retrieve_orderQuery_returnsOrderResults() {
        List<RetrievalResult> results = retriever.retrieve("订单发货", 5, 1L);
        assertNotNull(results);
        assertFalse(results.isEmpty(), "查询'订单发货'应返回结果");

        boolean hasOrder = results.stream()
                .anyMatch(r -> r.getContent() != null && r.getContent().contains("订单"));
        assertTrue(hasOrder, "检索结果应包含订单相关内容");
    }

    @Test
    void retrieve_emptyQuery_returnsEmpty() {
        List<RetrievalResult> results = retriever.retrieve("", 5, 1L);
        assertTrue(results.isEmpty(), "空查询应返回空列表");
    }

    @Test
    void retrieve_nullQuery_returnsEmpty() {
        List<RetrievalResult> results = retriever.retrieve(null, 5, 1L);
        assertTrue(results.isEmpty(), "null查询应返回空列表");
    }

    @Test
    void retrieve_resultsHaveMetadata() {
        List<RetrievalResult> results = retriever.retrieve("库存管理", 3, 1L);
        if (!results.isEmpty()) {
            RetrievalResult first = results.get(0);
            assertNotNull(first.getChunkId(), "chunkId不应为空");
            assertNotNull(first.getContent(), "content不应为空");
            assertTrue(first.getScore() > 0, "相关性分数应大于0");
            assertNotNull(first.getMetadata(), "metadata不应为空");
            assertEquals("MYSQL_FT", first.getMetadata().get("source"));
        }
    }

    @Test
    void retrieve_tenantIsolation_differentMerchant() {
        // merchant_id=2 应该没有数据（所有测试数据都是merchant_id=1）
        List<RetrievalResult> results = retriever.retrieve("退款", 5, 2L);
        assertTrue(results.isEmpty(), "merchant_id=2应无数据（租户隔离）");
    }

    @Test
    void retrieve_topKLimitsResults() {
        List<RetrievalResult> results = retriever.retrieve("订单", 2, 1L);
        assertTrue(results.size() <= 2, "topK=2时不应超过2条结果");
    }

    @Test
    void getStatistics_containsExpectedFields() {
        var stats = retriever.getStatistics();
        assertTrue((Boolean) stats.get("available"));
        assertEquals("ngram", stats.get("parser"));
        assertEquals("NATURAL LANGUAGE MODE", stats.get("mode"));
        assertEquals("PGVector", stats.get("fallbackFor"));
    }
}
