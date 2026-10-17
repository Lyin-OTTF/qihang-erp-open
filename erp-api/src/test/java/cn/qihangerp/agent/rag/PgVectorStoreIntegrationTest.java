package cn.qihangerp.agent.rag;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PGVector 启用态集成测试：对着真实 PostgreSQL + pgvector 做 insert + cosine 检索。
 *
 * <p>不依赖外部 Embedding API —— 用确定性的"中文 bigram 哈希"桩模型产生 1024 维向量，
 * 因此能在 CI 里稳定验证 pgvector 表结构、写入、HNSW 索引与相似度排序。
 * PG 不可达时自动跳过（assumption），不会让没装 PG 的环境挂掉。</p>
 *
 * <p>连接参数与 {@code agent.vector-store.*} 保持一致，支持环境变量覆盖。</p>
 */
@Tag("it")
class PgVectorStoreIntegrationTest {

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? def : v;
    }

    private static DriverManagerDataSource dataSource() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUrl("jdbc:postgresql://" + env("PGVECTOR_HOST", "localhost") + ":"
                + env("PGVECTOR_PORT", "5432") + "/" + env("PGVECTOR_DATABASE", "erp_vector"));
        ds.setUsername(env("PGVECTOR_USERNAME", "postgres"));
        ds.setPassword(env("PGVECTOR_PASSWORD", "1234"));
        return ds;
    }

    @Test
    void insertAndSearchThroughRealPgvector() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource());
        try {
            jdbcTemplate.queryForObject("select 1", Integer.class);
        } catch (Exception e) {
            Assumptions.assumeTrue(false, "PGVector 不可达，跳过集成测试: " + e.getMessage());
        }

        VectorStore vectorStore = PgVectorStore.builder(jdbcTemplate, new HashingEmbeddingModel())
                .dimensions(1024)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.HNSW)
                .initializeSchema(true)
                .build();

        String marker = "it-" + System.currentTimeMillis();
        try {
            vectorStore.add(List.of(
                    new Document("退货流程：客户签收后7天内可申请退货，需要填写退货原因并上传凭证。",
                            Map.of("merchant_id", 1, "src", marker)),
                    new Document("采购流程：创建采购单后需供应商确认，收货后办理入库。",
                            Map.of("merchant_id", 1, "src", marker)),
                    new Document("物流异常：超过48小时无揽收记录时需联系快递公司核实。",
                            Map.of("merchant_id", 1, "src", marker))));

            List<Document> hits = vectorStore.similaritySearch(
                    SearchRequest.builder().query("客户要退货怎么办").topK(3).build());

            assertFalse(hits.isEmpty(), "相似度检索应返回结果");
            assertTrue(hits.get(0).getText().contains("退货"),
                    "最相关结果应为退货流程，实际: " + hits.get(0).getText());
            assertTrue(hits.get(0).getScore() != null, "pgvector 应返回相似度分数");
        } finally {
            jdbcTemplate.update("delete from vector_store where metadata->>'src' = ?", marker);
        }
    }

    /** 确定性向量化桩：中文按 bigram 哈希到 1024 维并归一化，保证相同文本得到相同向量。 */
    private static class HashingEmbeddingModel implements EmbeddingModel {

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<Embedding> embeddings = new ArrayList<>();
            List<String> inputs = request.getInstructions();
            for (int i = 0; i < inputs.size(); i++) {
                embeddings.add(new Embedding(vectorOf(inputs.get(i)), i));
            }
            return new EmbeddingResponse(embeddings);
        }

        @Override
        public float[] embed(Document document) {
            return vectorOf(document.getText());
        }

        @Override
        public int dimensions() {
            return 1024;
        }

        private static float[] vectorOf(String text) {
            float[] vector = new float[1024];
            if (text == null || text.isEmpty()) {
                return vector;
            }
            String t = text.toLowerCase();
            for (int i = 0; i + 1 < t.length(); i++) {
                int bucket = Math.floorMod(t.substring(i, i + 2).hashCode(), 1024);
                vector[bucket] += 1f;
            }
            double norm = 0;
            for (float v : vector) {
                norm += v * v;
            }
            norm = Math.sqrt(norm);
            if (norm > 0) {
                for (int i = 0; i < vector.length; i++) {
                    vector[i] /= (float) norm;
                }
            }
            return vector;
        }
    }
}
