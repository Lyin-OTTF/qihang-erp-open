package cn.qihangerp.agent.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BM25检索器 + HybridRetriever + CitationGenerator 集成测试。
 *
 * <p>用真实ERP SOP文档索引BM25，验证：
 * 1. BM25检索能返回相关文档
 * 2. HybridRetriever在向量检索不可用时优雅降级为BM25-only
 * 3. CitationGenerator能从检索结果生成规范引用
 * 4. RRF融合算法正确性</p>
 *
 * @author 回溯开发-阶段3
 * @since 2026-10-12
 */
class RagRetrievalTest {

    private BM25Retriever bm25Retriever;
    private HybridRetriever hybridRetriever;
    private CitationGenerator citationGenerator;

    @BeforeEach
    void setUp() {
        bm25Retriever = new BM25Retriever();
        hybridRetriever = new HybridRetriever(bm25Retriever, null); // 测试中不使用MySQL全文检索
        citationGenerator = new CitationGenerator();

        // 索引真实SOP文档片段
        bm25Retriever.indexDocument("sop_refund_001",
                "已发货订单退款流程：客户收到货后申请退款，需先确认退款类型。仅退款和退货退款的处理方式不同。退款状态包括待审核、待退货、待退款、已完成。");
        bm25Retriever.indexDocument("sop_order_001",
                "已付款订单取消流程：客户申请取消已付款订单时，客服需先确认订单是否已发货。未发货订单可直接取消并退款，已发货订单需签收后退货退款。");
        bm25Retriever.indexDocument("sop_inventory_001",
                "库存不足提示与处理：客户下单时系统提示库存不足，说明该SKU可用库存为0。可查询在途采购单或推荐替代商品，不可超卖下单。");
        bm25Retriever.indexDocument("sop_logistics_001",
                "电子面单打印操作：打印电子面单需选择快递公司和模板，确认面单余额充足。热敏纸规格100x180mm。");
        bm25Retriever.indexDocument("sop_goods_002",
                "SKU与SPU区别说明：SPU是标准产品单元代表一款商品，SKU是库存量单位代表具体规格。一个SPU下可有多个SKU，每个SKU有独立条码价格库存。");
    }

    // ==================== BM25检索 ====================

    @Test
    void bm25_retrieve_returnsRelevantResults() {
        List<RetrievalResult> results = bm25Retriever.retrieve("退款流程", 5);
        assertFalse(results.isEmpty(), "查询'退款流程'应返回结果");
        // 退款相关文档应排在前面
        assertTrue(results.get(0).getChunkId().contains("refund"),
                "最相关结果应为退款文档，实际: " + results.get(0).getChunkId());
        assertNotNull(results.get(0).getContent());
        assertNotNull(results.get(0).getBm25Score());
        assertTrue(results.get(0).getBm25Score() > 0, "BM25分数应大于0");
    }

    @Test
    void bm25_retrieve_returnsTopK() {
        List<RetrievalResult> results = bm25Retriever.retrieve("订单", 2);
        assertEquals(2, results.size(), "topK=2应返回2条");
    }

    @Test
    void bm25_retrieve_emptyForUnmatchedQuery() {
        List<RetrievalResult> results = bm25Retriever.retrieve("量子物理相对论", 5);
        assertTrue(results.isEmpty(), "不相关查询应返回空结果");
    }

    @Test
    void bm25_resultsSortedByScoreDescending() {
        List<RetrievalResult> results = bm25Retriever.retrieve("库存 SKU 商品", 5);
        for (int i = 1; i < results.size(); i++) {
            assertTrue(results.get(i - 1).getBm25Score() >= results.get(i).getBm25Score(),
                    "结果应按BM25分数降序排列");
        }
    }

    // ==================== HybridRetriever降级 ====================

    @Test
    void hybridRetrieve_degradesToBM25WhenVectorUnavailable() {
        // retrieveByVector 已修复为返回空列表（向量存储未配置）
        List<RetrievalResult> results = hybridRetriever.retrieve("退款", 5, 1001L);
        assertFalse(results.isEmpty(), "向量不可用时应降级为BM25检索并返回结果");
        // 结果应来自BM25（chunkId为sop_开头）
        assertTrue(results.get(0).getChunkId().startsWith("sop_"),
                "降级结果应来自BM25索引，实际: " + results.get(0).getChunkId());
    }

    @Test
    void hybridRetrieve_setsRankCorrectly() {
        List<RetrievalResult> results = hybridRetriever.retrieve("订单", 3, 1001L);
        for (int i = 0; i < results.size(); i++) {
            assertEquals(i + 1, results.get(i).getRank(), "排名应从1开始连续");
        }
    }

    @Test
    void hybridRetrieve_bm25Only_returnsResults() {
        List<RetrievalResult> results = hybridRetriever.retrieveBM25Only("库存", 3, 1001L);
        assertFalse(results.isEmpty());
        assertEquals(1, results.get(0).getRank());
    }

    @Test
    void hybridRetrieve_vectorOnly_returnsEmptyWhenUnconfigured() {
        List<RetrievalResult> results = hybridRetriever.retrieveVectorOnly("任何查询", 5, 1001L);
        assertTrue(results.isEmpty(), "向量存储未配置时纯向量检索应返回空");
    }

    // ==================== RRF算法 ====================

    @Test
    void rrfScore_calculatedCorrectly() {
        // RRF = sum(1/(60+rank))
        // rank=1 → 1/61 ≈ 0.01639
        double score1 = HybridRetriever.calculateRRFScore(List.of(1));
        assertEquals(1.0 / 61, score1, 0.0001);

        // rank=1,3 → 1/61 + 1/63
        double score2 = HybridRetriever.calculateRRFScore(List.of(1, 3));
        assertEquals(1.0 / 61 + 1.0 / 63, score2, 0.0001);
    }

    // ==================== CitationGenerator ====================

    @Test
    void citationGenerator_generatesCitationsFromResults() {
        List<RetrievalResult> results = bm25Retriever.retrieve("退款", 3);
        List<Citation> citations = citationGenerator.generateCitations(results, 5);
        assertFalse(citations.isEmpty(), "应生成引用");
        assertEquals(1, citations.get(0).getIndex(), "引用编号从1开始");
        assertNotNull(citations.get(0).getChunkId());
        assertNotNull(citations.get(0).getSnippet());
    }

    @Test
    void citationGenerator_respectsMaxCitations() {
        List<RetrievalResult> results = bm25Retriever.retrieve("订单", 5);
        List<Citation> citations = citationGenerator.generateCitations(results, 2);
        assertEquals(2, citations.size(), "maxCitations=2应只生成2个引用");
    }

    @Test
    void citationGenerator_emptyForEmptyResults() {
        List<Citation> citations = citationGenerator.generateCitations(List.of(), 5);
        assertTrue(citations.isEmpty());
    }

    @Test
    void citationGenerator_formatMarkdown_containsReferences() {
        List<RetrievalResult> results = bm25Retriever.retrieve("退款", 2);
        List<Citation> citations = citationGenerator.generateCitations(results, 5);
        String markdown = citationGenerator.formatCitationsMarkdown(citations);
        assertTrue(markdown.contains("参考文献"), "Markdown格式应包含参考文献标题");
        assertTrue(markdown.contains(citations.get(0).getChunkId()), "应包含chunkId");
    }

    @Test
    void citationGenerator_deduplicateByChunkId() {
        // 构造两个相同chunkId的引用
        Citation c1 = new Citation(1, "doc1", "文档1", "chunk_001", "内容A", 0.9, "BM25");
        Citation c2 = new Citation(2, "doc1", "文档1", "chunk_001", "内容B", 0.8, "VECTOR");
        List<Citation> deduplicated = citationGenerator.deduplicateCitations(List.of(c1, c2));
        assertEquals(1, deduplicated.size(), "相同chunkId应去重");
        assertEquals(0.9, deduplicated.get(0).getScore(), "去重应保留分数高的");
    }

    @Test
    void citationGenerator_filterLowQuality() {
        Citation c1 = new Citation(1, "doc1", "文档1", "chunk_001", "内容A", 0.9, "BM25");
        Citation c2 = new Citation(2, "doc2", "文档2", "chunk_002", "内容B", 0.3, "BM25");
        List<Citation> filtered = citationGenerator.filterLowQualityCitations(List.of(c1, c2), 0.5);
        assertEquals(1, filtered.size(), "应过滤掉分数低于0.5的引用");
        assertEquals("chunk_001", filtered.get(0).getChunkId());
    }

    @Test
    void citationGenerator_generateResponse_includesCitationsInAnswer() {
        List<RetrievalResult> results = bm25Retriever.retrieve("退款", 2);
        RagResponse response = citationGenerator.generateResponse(
                "退款需要先确认类型。", results, "HYBRID", 50);
        assertNotNull(response.getAnswer());
        assertFalse(response.getCitations().isEmpty());
        assertEquals("HYBRID", response.getRetrievalMethod());
        assertEquals(50, response.getDuration());
        assertTrue(response.getRetrievedCount() > 0);
    }

    // ==================== BM25统计 ====================

    @Test
    void bm25_statistics_reflectIndexedDocs() {
        var stats = bm25Retriever.getStatistics();
        assertEquals(5, stats.get("totalDocs"), "应索引5个文档");
        assertTrue((Integer) stats.get("vocabularySize") > 0, "词汇表应非空");
    }
}
