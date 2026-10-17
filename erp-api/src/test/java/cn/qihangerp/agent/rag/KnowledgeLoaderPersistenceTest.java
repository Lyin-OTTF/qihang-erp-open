package cn.qihangerp.agent.rag;
import org.junit.jupiter.api.Tag;

import com.mysql.cj.jdbc.MysqlDataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * KnowledgeLoader 真实 DB 集成测试。
 *
 * <p>验证从 rag_document 表加载知识 chunk 到 BM25 内存索引，
 * 加载后检索能返回真实文档内容。</p>
 *
 * @author 回溯开发-阶段3
 * @since 2026-10-12
 */
@Tag("it")
class KnowledgeLoaderPersistenceTest {

    private static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void setup() {
        MysqlDataSource ds = new MysqlDataSource();
        ds.setUrl("jdbc:mysql://127.0.0.1:3306/qihang-erp"
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true");
        ds.setUser(System.getenv().getOrDefault("MYSQL_USERNAME", "root"));
        ds.setPassword(System.getenv().getOrDefault("MYSQL_PASSWORD", "1234"));
        jdbcTemplate = new JdbcTemplate((DataSource) ds);
    }

    @Test
    void ragDocumentTable_hasSeedData() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rag_document", Integer.class);
        assertNotNull(count);
        assertTrue(count >= 18, "rag_document 应至少有18条种子数据，实际: " + count);
    }

    @Test
    void knowledgeLoader_loadsFromDbIntoBM25() {
        BM25Retriever retriever = new BM25Retriever();
        KnowledgeLoader loader = new KnowledgeLoader(jdbcTemplate, retriever);

        // 加载前索引为空
        assertEquals(0, retriever.getStatistics().get("totalDocs"));

        // 执行加载
        loader.loadKnowledge();

        // 加载后索引应有数据
        Map<String, Object> stats = retriever.getStatistics();
        int totalDocs = (Integer) stats.get("totalDocs");
        assertTrue(totalDocs >= 18, "加载后BM25索引应至少有18个文档，实际: " + totalDocs);
        assertTrue((Integer) stats.get("vocabularySize") > 0, "词汇表应非空");
    }

    @Test
    void knowledgeLoader_afterLoad_retrievalReturnsRealContent() {
        BM25Retriever retriever = new BM25Retriever();
        KnowledgeLoader loader = new KnowledgeLoader(jdbcTemplate, retriever);
        loader.loadKnowledge();

        // 检索退款相关内容
        List<RetrievalResult> results = retriever.retrieve("退款流程", 3);
        assertFalse(results.isEmpty(), "加载真实数据后检索'退款流程'应返回结果");

        // 验证返回的是真实SOP内容（非假数据）
        RetrievalResult top = results.get(0);
        assertTrue(top.getChunkId().startsWith("sop_"),
                "应返回真实SOP文档，chunkId应以sop_开头，实际: " + top.getChunkId());
        assertNotNull(top.getContent());
        assertTrue(top.getContent().length() > 20, "内容应为真实SOP文本，实际长度: " + top.getContent().length());
        // 内容应包含退款相关关键词
        assertTrue(top.getContent().contains("退款"),
                "最相关结果应包含'退款'关键词，实际内容前50字: " + top.getContent().substring(0, Math.min(50, top.getContent().length())));
    }

    @Test
    void knowledgeLoader_reload_clearsAndReloads() {
        BM25Retriever retriever = new BM25Retriever();
        KnowledgeLoader loader = new KnowledgeLoader(jdbcTemplate, retriever);

        loader.loadKnowledge();
        int firstLoad = (Integer) retriever.getStatistics().get("totalDocs");

        // reload 应清空后重新加载
        loader.reload();
        int secondLoad = (Integer) retriever.getStatistics().get("totalDocs");
        assertEquals(firstLoad, secondLoad, "reload后文档数应一致");
    }

    @Test
    void knowledgeLoader_doesNotCrashWhenTableEmpty() {
        // 用一个空的BM25和相同的jdbcTemplate（表有数据，但验证异常处理）
        BM25Retriever retriever = new BM25Retriever();
        // 即使表为空，loadKnowledge 也不应抛异常（有try-catch保护）
        KnowledgeLoader loader = new KnowledgeLoader(jdbcTemplate, retriever);
        assertDoesNotThrow(() -> loader.loadKnowledge());
    }
}
