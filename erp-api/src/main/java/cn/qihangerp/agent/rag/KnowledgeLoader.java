package cn.qihangerp.agent.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;

/**
 * 知识库加载器。
 *
 * <p>应用启动时从 rag_document 表加载所有知识 chunk 到 BM25 内存索引。
 * 向量检索（PGVector）未配置时，BM25 是唯一可用的检索路径。</p>
 *
 * @author 回溯开发-阶段3
 * @since 2026-10-12
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeLoader {

    private final JdbcTemplate jdbcTemplate;
    private final BM25Retriever bm25Retriever;

    @PostConstruct
    public void loadKnowledge() {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT chunk_id, content, merchant_id FROM rag_document ORDER BY id");
            for (Map<String, Object> row : rows) {
                String chunkId = (String) row.get("chunk_id");
                String content = (String) row.get("content");
                Object merchantObj = row.get("merchant_id");
                Long merchantId = merchantObj != null ? ((Number) merchantObj).longValue() : null;
                if (chunkId != null && content != null) {
                    bm25Retriever.indexDocument(chunkId, content, merchantId);
                }
            }
            log.info("知识库加载完成: 共 {} 个chunk 已索引到 BM25", rows.size());
        } catch (Exception e) {
            // 表不存在或加载失败时不阻断启动，BM25索引为空
            log.warn("知识库加载失败（rag_document 表可能尚未初始化）: {}", e.getMessage());
        }
    }

    /**
     * 重新加载知识库（清空后重新从DB加载）。
     * 可通过管理接口调用，在新增文档后刷新索引。
     */
    public void reload() {
        bm25Retriever.clearIndex();
        loadKnowledge();
    }
}
