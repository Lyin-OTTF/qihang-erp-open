package cn.qihangerp.agent.rag;

import cn.qihangerp.erp.ErpApi;
import cn.qihangerp.model.entity.AiConfig;
import cn.qihangerp.service.AiConfigService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real RAG evaluation with LLM answer generation and LLM-as-judge.
 *
 * <p>This test intentionally calls a paid external model and is excluded from
 * normal unit/integration test profiles. Run it with:</p>
 *
 * <pre>
 * mvn -pl erp-api -am test -Prag-llm-eval -Dtest=RagLlmEvaluationIntegrationTest
 * </pre>
 */
@Tag("llm")
@SpringBootTest(classes = ErpApi.class, webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "agent.evaluation.llm-enabled=true",
        "agent.evaluation.llm-timeout-seconds=120",
        "agent.evaluation.llm-max-attempts=3",
        "spring.data.redis.password=${REDIS_PASSWORD:}",
        "spring.ai.model.chat=none",
        "agent.vector-store.enabled=true"
})
@ActiveProfiles({"dev", "agent"})
class RagLlmEvaluationIntegrationTest {

    private static final String REMARK_PREFIX = "真实LLM评测-";

    @Autowired
    private RagEvaluator ragEvaluator;

    @Autowired
    private LlmEvaluationService llmEvaluationService;

    @Autowired
    private AiConfigService aiConfigService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void runRealLlmEvaluation() {
        AiConfig config = aiConfigService.getDefaultConfig();
        assertNotNull(config, "必须存在启用的AI配置");
        assertNotNull(config.getApiKey(), "AI配置API Key不能为空");
        assertTrue(!config.getApiKey().isBlank(), "AI配置API Key不能为空");
        assertTrue(llmEvaluationService.isLlmEnabled(), "真实LLM评测开关必须开启");

        jdbcTemplate.update("DELETE FROM agent_rag_eval_case_result WHERE run_id IN " +
                "(SELECT id FROM agent_rag_eval_result WHERE remark LIKE ?)", REMARK_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM agent_rag_eval_result WHERE remark LIKE ?", REMARK_PREFIX + "%");

        RagEvaluator.EvaluationOptions options = new RagEvaluator.EvaluationOptions();
        options.setRequireLlm(true);
        String maxCases = System.getProperty("rag.evaluation.max-cases",
                System.getenv().getOrDefault("RAG_EVAL_MAX_CASES", "0"));
        options.setMaxCases(Integer.parseInt(maxCases));

        RagEvaluator.RagEvaluationReport report = ragEvaluator.runEvaluationAndPersist(
                1L,
                10,
                REMARK_PREFIX + System.currentTimeMillis(),
                options);

        System.out.println("=== Real RAG Evaluation ===");
        System.out.println("runId=" + report.getRunId());
        System.out.println("mode=" + report.getEvaluationMode() + ", model=" + report.getModelName());
        System.out.println("cases=" + report.getTotalCases() + ", retrievalCases=" + report.getRetrievalCases()
                + ", llmRatedCases=" + report.getLlmRatedCases());
        System.out.println("recall=" + report.getAvgRecall()
                + ", precision=" + report.getAvgPrecision()
                + ", mrr=" + report.getAvgMRR()
                + ", ndcg=" + report.getAvgNDCG());
        System.out.println("relevancy=" + report.getAvgAnswerRelevancy()
                + ", correctness=" + report.getAvgAnswerCorrectness()
                + ", faithfulness=" + report.getAvgFaithfulness());
        System.out.println("bleu=" + report.getAvgBleu() + ", rougeL=" + report.getAvgRougeL());
        System.out.println("passRate=" + report.getPassRate() + "%, overall=" + report.getAvgOverallScore());

        assertNotNull(report.getRunId());
        assertEquals("LLM_STRICT", report.getEvaluationMode());
        assertEquals(report.getTotalCases(), report.getLlmRatedCases(),
                "每个用例都必须完成LLM生成和裁判");
        assertTrue(report.isLlmUsed());
        assertTrue(report.getAvgAnswerCorrectness() >= 0.0 && report.getAvgAnswerCorrectness() <= 1.0);
        assertTrue(report.getAvgBleu() >= 0.0 && report.getAvgBleu() <= 1.0);
        assertTrue(report.getAvgRougeL() >= 0.0 && report.getAvgRougeL() <= 1.0);

        Integer detailCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_rag_eval_case_result WHERE run_id = ?",
                Integer.class,
                report.getRunId());
        assertEquals(report.getTotalCases(), detailCount, "逐用例明细应完整落库");
    }
}
