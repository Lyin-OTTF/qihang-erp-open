package cn.qihangerp.erp.serviceImpl.ai;

import cn.qihangerp.agent.rag.BM25Retriever;
import cn.qihangerp.agent.rag.CitationGenerator;
import cn.qihangerp.agent.rag.HybridRetriever;
import cn.qihangerp.agent.rag.MysqlFullTextRetriever;
import cn.qihangerp.agent.rag.RetrievalResult;
import cn.qihangerp.agent.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 知识库检索工具单测：验证 RAG 接入对话后的入参透传、空结果提示与引用格式化。
 *
 * <p>用轻量桩替代 Mockito（erp-api 测试作用域未声明 Mockito）。</p>
 */
class KnowledgeToolsTest {

    /** 记录调用参数的检索桩 */
    private static class StubRetriever extends HybridRetriever {
        private final List<RetrievalResult> results;
        private String lastQuery;
        private int lastTopK;
        private Long lastMerchantId;

        StubRetriever(List<RetrievalResult> results) {
            super(new BM25Retriever(), new MysqlFullTextRetriever(null));
            this.results = results;
        }

        @Override
        public List<RetrievalResult> retrieve(String query, int topK, Long merchantId) {
            this.lastQuery = query;
            this.lastTopK = topK;
            this.lastMerchantId = merchantId;
            return results;
        }
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void blankQueryReturnsHintWithoutRetrieval() {
        StubRetriever retriever = new StubRetriever(List.of());
        KnowledgeTools tools = new KnowledgeTools(retriever, new CitationGenerator());

        assertTrue(tools.searchKnowledge("  ", 5).contains("为空"));
        assertNull(retriever.lastQuery, "空查询不应触发检索");
    }

    @Test
    void noHitReturnsExplicitNoResultAndPassesTenant() {
        StubRetriever retriever = new StubRetriever(List.of());
        KnowledgeTools tools = new KnowledgeTools(retriever, new CitationGenerator());
        TenantContext.setTenantId(1L);

        String out = tools.searchKnowledge("退货流程", 5);

        assertTrue(out.contains("未在知识库中找到"));
        assertEquals("退货流程", retriever.lastQuery);
        assertEquals(5, retriever.lastTopK);
        assertEquals(1L, retriever.lastMerchantId);
    }

    @Test
    void formatsCitationsWithChunkIdAndIndex() {
        RetrievalResult hit = RetrievalResult.createBM25Result(
                "chunk_001", "七天无理由退货，需在签收后7天内发起。", 1.2, "doc_1");
        hit.setDocName("退款SOP");
        KnowledgeTools tools = new KnowledgeTools(new StubRetriever(List.of(hit)), new CitationGenerator());
        TenantContext.setTenantId(1L);

        String out = tools.searchKnowledge("退货", 3);

        assertTrue(out.contains("[1]"), "应包含引用编号");
        assertTrue(out.contains("chunk_001"), "应包含 chunkId 便于溯源");
        assertTrue(out.contains("退款SOP"), "应包含文档名称");
    }
}
