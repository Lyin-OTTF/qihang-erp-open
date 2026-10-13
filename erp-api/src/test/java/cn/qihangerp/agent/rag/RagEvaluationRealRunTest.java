package cn.qihangerp.agent.rag;

import com.mysql.cj.jdbc.MysqlDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RAG离线评测真实跑批测试。
 *
 * <p>手动wire RagEvaluator及其依赖（HybridRetriever/BM25/MySQL FT/CitationGenerator），
 * 对agent_rag_evaluation表中100条评测用例真实执行混合检索，
 * 计算Recall@10/Precision/F1/MRR/NDCG等指标并持久化到agent_rag_eval_result表。</p>
 *
 * <p>这不是mock测试——是对真实DB中110篇文档和100条评测用例的真实检索评测。</p>
 */
class RagEvaluationRealRunTest {

    private JdbcTemplate createJdbcTemplate() {
        MysqlDataSource ds = new MysqlDataSource();
        ds.setUrl("jdbc:mysql://127.0.0.1:3306/qihang-erp"
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true");
        ds.setUser("root");
        ds.setPassword("1234");
        return new JdbcTemplate((DataSource) ds);
    }

    @Test
    void testRealEvaluationRun() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();

        // 1. 验证前置数据
        Integer docCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM rag_document", Integer.class);
        Integer evalCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM agent_rag_evaluation", Integer.class);
        System.out.println("=== 评测前置数据 ===");
        System.out.println("rag_document(chunk数): " + docCount);
        System.out.println("agent_rag_evaluation(评测用例): " + evalCount);
        assertTrue(docCount != null && docCount > 0, "知识库文档不能为空");
        assertTrue(evalCount != null && evalCount > 0, "评测用例不能为空");

        // 2. 手动wire组件
        BM25Retriever bm25Retriever = new BM25Retriever();
        // 加载BM25索引（模拟KnowledgeLoader）
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT chunk_id, content FROM rag_document ORDER BY id");
        int indexed = 0;
        for (Map<String, Object> row : rows) {
            String chunkId = (String) row.get("chunk_id");
            String content = (String) row.get("content");
            if (chunkId != null && content != null) {
                bm25Retriever.indexDocument(chunkId, content);
                indexed++;
            }
        }
        System.out.println("BM25索引加载: " + indexed + " 个chunk");

        MysqlFullTextRetriever mysqlFtRetriever = new MysqlFullTextRetriever(jdbcTemplate);
        HybridRetriever hybridRetriever = new HybridRetriever(bm25Retriever, mysqlFtRetriever);
        CitationGenerator citationGenerator = new CitationGenerator();
        RagEvaluator ragEvaluator = new RagEvaluator(jdbcTemplate, hybridRetriever, citationGenerator);

        // 3. 真实跑评测
        System.out.println("\n=== 开始真实评测（TopK=10）===");
        long startTime = System.currentTimeMillis();
        RagEvaluator.RagEvaluationReport report = ragEvaluator.runEvaluationAndPersist(
                1L, 10, "回溯开发-真实跑批-" + System.currentTimeMillis());
        long elapsed = System.currentTimeMillis() - startTime;

        // 4. 输出真实指标
        System.out.println("\n=== 评测结果（真实数据）===");
        System.out.println("总用例数: " + report.getTotalCases());
        System.out.println("通过用例: " + report.getPassedCases());
        System.out.println("通过率: " + String.format("%.2f%%", report.getPassRate()));
        System.out.println("Avg Recall@10: " + String.format("%.4f", report.getAvgRecall()));
        System.out.println("Avg Precision: " + String.format("%.4f", report.getAvgPrecision()));
        System.out.println("Avg F1: " + String.format("%.4f", report.getAvgF1()));
        System.out.println("Avg MRR: " + String.format("%.4f", report.getAvgMRR()));
        System.out.println("Avg NDCG: " + String.format("%.4f", report.getAvgNDCG()));
        System.out.println("Avg 检索耗时(ms): " + report.getAvgRetrievalTime());
        System.out.println("总耗时(ms): " + elapsed);

        // 5. 验证已持久化
        Integer resultCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_rag_eval_result WHERE merchant_id = 1", Integer.class);
        System.out.println("\nagent_rag_eval_result表记录数: " + resultCount);
        assertTrue(resultCount != null && resultCount > 0, "评测结果应已持久化");

        // 6. 基本断言（不设过高阈值，只验证指标在合理范围）
        assertTrue(report.getTotalCases() > 0, "总用例数应大于0");
        assertTrue(report.getAvgRecall() >= 0 && report.getAvgRecall() <= 1,
                "Recall应在[0,1]范围: " + report.getAvgRecall());
        assertTrue(report.getAvgMRR() >= 0 && report.getAvgMRR() <= 1,
                "MRR应在[0,1]范围: " + report.getAvgMRR());

        System.out.println("\n=== 评测完成，真实指标已落库 ===");
    }
}
