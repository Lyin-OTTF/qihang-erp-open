package cn.qihangerp.agent.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * MySQL全文检索器（PGVector降级方案）
 *
 * <p>当PGVector向量存储不可用时，使用MySQL FULLTEXT索引 + MATCH AGAINST
 * 进行语义近似检索。MySQL 8.0内置ngram分词器支持中文全文检索。</p>
 *
 * <p>三级降级链路：PGVector(向量语义) → MySQL FULLTEXT(全文相关性) → BM25(关键词)</p>
 *
 * <p>注意：MySQL全文检索是基于词频的相关性匹配，不是真正的语义向量检索，
 * 对同义词、上下位词的召回能力弱于向量检索，但优于纯关键词BM25。</p>
 *
 * @author AI Code Generator
 * @since 2026-10-12
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MysqlFullTextRetriever {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 使用MySQL MATCH AGAINST进行全文检索
     *
     * @param query      查询文本
     * @param topK       返回数量
     * @param merchantId 商户ID（租户隔离）
     * @return 检索结果列表
     */
    public List<RetrievalResult> retrieve(String query, int topK, Long merchantId) {
        if (query == null || query.trim().isEmpty()) {
            return List.of();
        }

        long startTime = System.currentTimeMillis();

        // 使用BOOLEAN MODE支持多关键词组合，IN NATURAL LANGUAGE MODE更适合自然语言
        // 对于中文ngram分词，NATURAL LANGUAGE MODE效果更好
        String sql = "SELECT chunk_id, doc_id, title, content, domain, " +
                "MATCH(content) AGAINST (? IN NATURAL LANGUAGE MODE) AS relevance_score " +
                "FROM rag_document " +
                "WHERE merchant_id = ? " +
                "AND MATCH(content) AGAINST (? IN NATURAL LANGUAGE MODE) > 0 " +
                "ORDER BY relevance_score DESC " +
                "LIMIT ?";

        try {
            List<RetrievalResult> results = jdbcTemplate.query(sql, (rs, rowNum) -> {
                String chunkId = rs.getString("chunk_id");
                String content = rs.getString("content");
                double score = rs.getDouble("relevance_score");
                String docId = rs.getString("doc_id");
                String title = rs.getString("title");
                String domain = rs.getString("domain");

                RetrievalResult result = RetrievalResult.createVectorResult(chunkId, content, score, docId);
                result.setDocName(title);
                result.setMetadata(java.util.Map.of("domain", domain != null ? domain : "", "source", "MYSQL_FT"));
                return result;
            }, query, merchantId, query, topK);

            long duration = System.currentTimeMillis() - startTime;
            log.info("MySQL全文检索完成: query={}, topK={}, matched={}, duration={}ms",
                    query, topK, results.size(), duration);

            return results;
        } catch (Exception e) {
            // FULLTEXT索引不存在或查询失败时，降级返回空列表，由HybridRetriever继续降级到BM25
            log.warn("MySQL全文检索失败，降级到BM25: query={}, error={}", query, e.getMessage());
            return List.of();
        }
    }

    /**
     * 检查MySQL全文检索是否可用（FULLTEXT索引是否存在）
     */
    public boolean isAvailable() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.STATISTICS " +
                            "WHERE TABLE_SCHEMA = DATABASE() " +
                            "AND TABLE_NAME = 'rag_document' " +
                            "AND INDEX_NAME = 'ft_content'",
                    Integer.class);
            return count != null && count > 0;
        } catch (Exception e) {
            log.warn("检查MySQL全文索引失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 获取全文索引统计信息
     */
    public java.util.Map<String, Object> getStatistics() {
        java.util.Map<String, Object> stats = new java.util.HashMap<>();
        stats.put("available", isAvailable());
        stats.put("parser", "ngram");
        stats.put("mode", "NATURAL LANGUAGE MODE");
        stats.put("fallbackFor", "PGVector");
        return stats;
    }
}
