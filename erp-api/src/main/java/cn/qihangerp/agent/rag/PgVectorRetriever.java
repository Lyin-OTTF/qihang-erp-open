package cn.qihangerp.agent.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * PGVector向量检索器（语义检索）。
 *
 * <p>封装Spring AI的VectorStore，当PGVector + EmbeddingModel配置完成时自动启用。
 * 未配置时VectorStore为null，{@link #isAvailable()}返回false，调用方应降级到MySQL FT。</p>
 *
 * <p>启用条件：
 * <ol>
 *   <li>PostgreSQL + pgvector扩展已安装并运行</li>
 *   <li>application-agent.yml中配置了spring.ai.vectorstore.pgvector数据源</li>
 *   <li>ErpApi主类移除PgVectorStoreAutoConfiguration的exclude</li>
 *   <li>配置了可用的EmbeddingModel API（OpenAI/阿里云百炼等，DeepSeek不提供embedding）</li>
 * </ol>
 * </p>
 *
 * @author 回溯开发-PGVector接线
 * @since 2026-10-13
 */
@Slf4j
@Component
public class PgVectorRetriever {

    @Autowired(required = false)
    private VectorStore vectorStore;

    @jakarta.annotation.PostConstruct
    public void init() {
        if (vectorStore != null) {
            log.info("PGVector向量检索器已启用（VectorStore已注入）");
        } else {
            log.info("PGVector向量检索器未启用（VectorStore未配置，将降级到MySQL FT）");
        }
    }

    /**
     * PGVector是否可用。
     */
    public boolean isAvailable() {
        return vectorStore != null;
    }

    /**
     * 向量语义检索。
     *
     * @param query      查询文本
     * @param topK       返回数量
     * @param merchantId 商户ID（租户隔离，通过metadata过滤）
     * @return 检索结果列表；不可用时返回空列表
     */
    public List<RetrievalResult> retrieve(String query, int topK, Long merchantId) {
        if (!isAvailable()) {
            return Collections.emptyList();
        }

        try {
            SearchRequest request = SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .build();

            List<Document> documents = vectorStore.similaritySearch(request);

            return documents.stream()
                    .filter(doc -> {
                        // 租户隔离：metadata中merchant_id匹配
                        if (merchantId == null) return true;
                        Object mid = doc.getMetadata().get("merchant_id");
                        return mid == null || Long.parseLong(mid.toString()) == merchantId;
                    })
                    .map(doc -> {
                        RetrievalResult result = new RetrievalResult();
                        result.setChunkId(doc.getId());
                        result.setContent(doc.getText());
                        result.setVectorScore(doc.getScore() != null ? doc.getScore() : 0.0);
                        result.setDocId((String) doc.getMetadata().get("doc_id"));
                        result.setDocName((String) doc.getMetadata().get("title"));
                        result.setMetadata(doc.getMetadata());
                        return result;
                    })
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.warn("PGVector检索失败，降级到MySQL FT: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}
