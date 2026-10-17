package cn.qihangerp.agent.rag;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.http.client.ReactorClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.client.RestClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 Embedding → PGVector 端到端集成测试（本地 Ollama，无需云 API Key）。
 *
 * <p>用 Ollama 的 bge-m3（1024 维，与 vector_store 表维度一致）对中文 SOP 文档做真实向量化，
 * 写入 PostgreSQL pgvector 后用自然语言问题检索，断言最相关文档排在首位。</p>
 *
 * <p>Ollama 未启动 / 模型未拉取 / PG 不可达时自动跳过（assumption），不影响其它环境。</p>
 */
@Tag("it")
class OllamaEmbeddingPgVectorIntegrationTest {

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? def : v;
    }

    @Test
    void realEmbeddingThenSearchThroughPgvector() {
        String baseUrl = env("OLLAMA_BASE_URL", "http://localhost:11434");
        String model = env("OLLAMA_EMBEDDING_MODEL", "bge-m3");

        // CPU 上批量向量化较慢，显式放宽读超时（默认较短会 ReadTimeout）
        var httpClient = HttpClient.create().responseTimeout(Duration.ofSeconds(180));
        var requestFactory = new ReactorClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(180));
        RestClient.Builder restClientBuilder = RestClient.builder().requestFactory(requestFactory);

        OllamaApi ollamaApi = OllamaApi.builder()
                .baseUrl(baseUrl)
                .restClientBuilder(restClientBuilder)
                .build();
        OllamaEmbeddingModel embeddingModel = OllamaEmbeddingModel.builder()
                .ollamaApi(ollamaApi)
                .options(OllamaEmbeddingOptions.builder().model(model).build())
                .build();

        // Ollama 不可达 / 模型未拉取时跳过
        float[] probe;
        try {
            probe = embeddingModel.embed("健康检查");
        } catch (Exception e) {
            Assumptions.assumeTrue(false, "Ollama 不可用或未拉取模型 " + model + ": " + e.getMessage());
            return;
        }
        Assumptions.assumeTrue(probe.length == 1024,
                "模型 " + model + " 维度为 " + probe.length + "，与 vector_store(1024) 不一致，跳过");

        JdbcTemplate jdbcTemplate = new JdbcTemplate(pgDataSource());
        try {
            jdbcTemplate.queryForObject("select 1", Integer.class);
        } catch (Exception e) {
            Assumptions.assumeTrue(false, "PGVector 不可达，跳过: " + e.getMessage());
        }

        VectorStore vectorStore = PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .dimensions(1024)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.HNSW)
                .initializeSchema(true)
                .build();

        String marker = "ollama-it-" + System.currentTimeMillis();
        try {
            vectorStore.add(List.of(
                    new Document("退货流程：客户签收后7天内可申请退货，需要填写退货原因并上传凭证，审核通过后原路退款。",
                            Map.of("merchant_id", 1, "src", marker)),
                    new Document("采购流程：创建采购单后需供应商确认，收货后办理入库并更新库存成本。",
                            Map.of("merchant_id", 1, "src", marker)),
                    new Document("物流异常：超过48小时无揽收记录时需联系快递公司核实并在系统中登记。",
                            Map.of("merchant_id", 1, "src", marker))));

            List<Document> hits = vectorStore.similaritySearch(
                    SearchRequest.builder().query("客户要退货怎么办").topK(3).build());

            assertFalse(hits.isEmpty(), "真实向量检索应返回结果");
            assertTrue(hits.get(0).getText().contains("退货"),
                    "最相关结果应为退货流程，实际: " + hits.get(0).getText());
            assertTrue(hits.get(0).getScore() != null, "应返回相似度分数");
        } finally {
            jdbcTemplate.update("delete from vector_store where metadata->>'src' = ?", marker);
        }
    }

    private static DriverManagerDataSource pgDataSource() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUrl("jdbc:postgresql://" + env("PGVECTOR_HOST", "localhost") + ":"
                + env("PGVECTOR_PORT", "5432") + "/" + env("PGVECTOR_DATABASE", "erp_vector"));
        ds.setUsername(env("PGVECTOR_USERNAME", "postgres"));
        ds.setPassword(env("PGVECTOR_PASSWORD", "1234"));
        return ds;
    }
}
