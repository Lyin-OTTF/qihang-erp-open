package cn.qihangerp.agent.rag;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * RAG检索接口
 *
 * 提供混合检索、纯向量检索、纯BM25检索等功能
 *
 * @author AI Code Generator
 * @since 2026-10-12
 */
@RestController
@RequestMapping("/api/agent/rag")
@RequiredArgsConstructor
public class RagController {

    private final HybridRetriever hybridRetriever;
    private final BM25Retriever bm25Retriever;
    private final CitationGenerator citationGenerator;
    private final RagEvaluator ragEvaluator;
    private final RagEvaluationScheduler ragEvaluationScheduler;
    private final MysqlFullTextRetriever mysqlFullTextRetriever;

    /**
     * 混合检索（BM25 + Vector + RRF融合）
     *
     * POST /api/agent/rag/retrieve
     * Body: {
     *   "query": "如何处理退款申请？",
     *   "topK": 10,
     *   "merchantId": 1001
     * }
     */
    @PostMapping("/retrieve")
    public Map<String, Object> retrieve(@RequestBody Map<String, Object> request) {
        String query = (String) request.get("query");
        Integer topK = request.get("topK") != null ? (Integer) request.get("topK") : 10;
        Long merchantId = Long.valueOf(request.get("merchantId").toString());

        long startTime = System.currentTimeMillis();

        List<RetrievalResult> results = hybridRetriever.retrieve(query, topK, merchantId);

        long duration = System.currentTimeMillis() - startTime;

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("query", query);
        response.put("topK", topK);
        response.put("results", results);
        response.put("count", results.size());
        response.put("duration", duration + "ms");

        return response;
    }

    /**
     * 纯向量检索
     *
     * POST /api/agent/rag/retrieve/vector
     */
    @PostMapping("/retrieve/vector")
    public Map<String, Object> retrieveVectorOnly(@RequestBody Map<String, Object> request) {
        String query = (String) request.get("query");
        Integer topK = request.get("topK") != null ? (Integer) request.get("topK") : 10;
        Long merchantId = Long.valueOf(request.get("merchantId").toString());

        List<RetrievalResult> results = hybridRetriever.retrieveVectorOnly(query, topK, merchantId);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("method", "VECTOR");
        response.put("results", results);
        response.put("count", results.size());

        return response;
    }

    /**
     * 纯BM25检索
     *
     * POST /api/agent/rag/retrieve/bm25
     */
    @PostMapping("/retrieve/bm25")
    public Map<String, Object> retrieveBM25Only(@RequestBody Map<String, Object> request) {
        String query = (String) request.get("query");
        Integer topK = request.get("topK") != null ? (Integer) request.get("topK") : 10;
        Long merchantId = Long.valueOf(request.get("merchantId").toString());

        List<RetrievalResult> results = hybridRetriever.retrieveBM25Only(query, topK, merchantId);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("method", "BM25");
        response.put("results", results);
        response.put("count", results.size());

        return response;
    }

    /**
     * 索引文档
     *
     * POST /api/agent/rag/index
     * Body: {
     *   "chunkId": "chunk_001",
     *   "content": "订单查询相关内容..."
     * }
     */
    @PostMapping("/index")
    public Map<String, Object> indexDocument(@RequestBody Map<String, Object> request) {
        String chunkId = (String) request.get("chunkId");
        String content = (String) request.get("content");

        bm25Retriever.indexDocument(chunkId, content);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "文档已索引");
        response.put("chunkId", chunkId);

        return response;
    }

    /**
     * 批量索引文档
     *
     * POST /api/agent/rag/index/batch
     * Body: {
     *   "chunks": {
     *     "chunk_001": "内容1",
     *     "chunk_002": "内容2"
     *   }
     * }
     */
    @PostMapping("/index/batch")
    public Map<String, Object> indexBatch(@RequestBody Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        Map<String, String> chunks = (Map<String, String>) request.get("chunks");

        bm25Retriever.indexBatch(chunks);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "批量索引完成");
        response.put("count", chunks.size());

        return response;
    }

    /**
     * 清空BM25索引
     *
     * DELETE /api/agent/rag/index
     */
    @DeleteMapping("/index")
    public Map<String, Object> clearIndex() {
        bm25Retriever.clearIndex();

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "BM25索引已清空");

        return response;
    }

    /**
     * 获取BM25索引统计
     *
     * GET /api/agent/rag/index/stats
     */
    @GetMapping("/index/stats")
    public Map<String, Object> getIndexStatistics() {
        Map<String, Object> stats = bm25Retriever.getStatistics();

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("stats", stats);

        return response;
    }

    /**
     * 测试RRF算法
     *
     * POST /api/agent/rag/test/rrf
     * Body: {
     *   "ranks": [1, 3, 5]
     * }
     */
    @PostMapping("/test/rrf")
    public Map<String, Object> testRRF(@RequestBody Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        List<Integer> ranks = (List<Integer>) request.get("ranks");

        double rrfScore = HybridRetriever.calculateRRFScore(ranks);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("ranks", ranks);
        response.put("rrfScore", rrfScore);
        response.put("formula", "RRF = sum(1 / (60 + rank))");

        return response;
    }

    /**
     * 生成引用
     *
     * POST /api/agent/rag/citations/generate
     * Body: {
     *   "query": "如何处理退款？",
     *   "answer": "退款流程如下：首先...",
     *   "topK": 10,
     *   "maxCitations": 5,
     *   "merchantId": 1001
     * }
     */
    @PostMapping("/citations/generate")
    public Map<String, Object> generateCitations(@RequestBody Map<String, Object> request) {
        String query = (String) request.get("query");
        String answer = (String) request.get("answer");
        Integer topK = request.get("topK") != null ? (Integer) request.get("topK") : 10;
        Integer maxCitations = request.get("maxCitations") != null ? (Integer) request.get("maxCitations") : 5;
        Long merchantId = Long.valueOf(request.get("merchantId").toString());

        long startTime = System.currentTimeMillis();

        // 步骤1: 混合检索
        List<RetrievalResult> retrievalResults = hybridRetriever.retrieve(query, topK, merchantId);

        // 步骤2: 生成引用
        long duration = System.currentTimeMillis() - startTime;
        RagResponse ragResponse = citationGenerator.generateResponse(
                answer,
                retrievalResults,
                "HYBRID",
                duration,
                maxCitations
        );

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("query", query);
        response.put("answer", ragResponse.getAnswer());
        response.put("fullAnswer", ragResponse.toFullAnswer());
        response.put("citations", ragResponse.getCitations());
        response.put("retrievedCount", ragResponse.getRetrievedCount());
        response.put("citationCount", ragResponse.getCitations().size());
        response.put("duration", duration + "ms");

        return response;
    }

    /**
     * 格式化引用为Markdown
     *
     * POST /api/agent/rag/citations/format
     * Body: {
     *   "citations": [...],
     *   "format": "markdown" // or "plaintext"
     * }
     */
    @PostMapping("/citations/format")
    public Map<String, Object> formatCitations(@RequestBody Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> citationMaps = (List<Map<String, Object>>) request.get("citations");
        String format = request.get("format") != null ? (String) request.get("format") : "markdown";

        // 转换为Citation对象
        List<Citation> citations = citationMaps.stream()
                .map(map -> {
                    Citation citation = new Citation();
                    citation.setIndex(((Number) map.get("index")).intValue());
                    citation.setDocId((String) map.get("docId"));
                    citation.setDocName((String) map.get("docName"));
                    citation.setChunkId((String) map.get("chunkId"));
                    citation.setSnippet((String) map.get("snippet"));
                    citation.setScore(((Number) map.get("score")).doubleValue());
                    citation.setType((String) map.get("type"));
                    return citation;
                })
                .collect(Collectors.toList());

        String formatted;
        if ("markdown".equalsIgnoreCase(format)) {
            formatted = citationGenerator.formatCitationsMarkdown(citations);
        } else {
            formatted = citationGenerator.formatCitationsPlainText(citations);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("format", format);
        response.put("formatted", formatted);

        return response;
    }

    /**
     * 引用去重和过滤
     *
     * POST /api/agent/rag/citations/filter
     * Body: {
     *   "citations": [...],
     *   "minScore": 0.5
     * }
     */
    @PostMapping("/citations/filter")
    public Map<String, Object> filterCitations(@RequestBody Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> citationMaps = (List<Map<String, Object>>) request.get("citations");
        Double minScore = request.get("minScore") != null
                ? ((Number) request.get("minScore")).doubleValue()
                : 0.0;

        // 转换为Citation对象
        List<Citation> citations = citationMaps.stream()
                .map(map -> {
                    Citation citation = new Citation();
                    citation.setIndex(((Number) map.get("index")).intValue());
                    citation.setDocId((String) map.get("docId"));
                    citation.setDocName((String) map.get("docName"));
                    citation.setChunkId((String) map.get("chunkId"));
                    citation.setSnippet((String) map.get("snippet"));
                    citation.setScore(((Number) map.get("score")).doubleValue());
                    citation.setType((String) map.get("type"));
                    return citation;
                })
                .collect(Collectors.toList());

        // 去重
        List<Citation> deduplicated = citationGenerator.deduplicateCitations(citations);

        // 过滤低质量引用
        List<Citation> filtered = citationGenerator.filterLowQualityCitations(deduplicated, minScore);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("original", citations.size());
        response.put("deduplicated", deduplicated.size());
        response.put("filtered", filtered.size());
        response.put("citations", filtered);

        return response;
    }

    /**
     * 运行RAG离线评测（结果持久化）
     *
     * POST /api/agent/rag/evaluate
     * Body: {
     *   "merchantId": 1001,
     *   "topK": 10,
     *   "remark": "手动评测"
     * }
     */
    @PostMapping("/evaluate")
    public Map<String, Object> runEvaluation(@RequestBody Map<String, Object> request) {
        Long merchantId = Long.valueOf(request.get("merchantId").toString());
        Integer topK = request.get("topK") != null ? (Integer) request.get("topK") : 10;
        String remark = request.get("remark") != null ? (String) request.get("remark") : "手动评测";

        RagEvaluator.RagEvaluationReport report = ragEvaluationScheduler.runManualEvaluation(merchantId, topK, remark);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("report", report);
        response.put("persisted", true);

        return response;
    }

    /**
     * 查询最近评测历史
     *
     * GET /api/agent/rag/evaluate/history?merchantId=1&limit=10
     */
    @GetMapping("/evaluate/history")
    public Map<String, Object> getEvaluationHistory(
            @RequestParam(defaultValue = "1") Long merchantId,
            @RequestParam(defaultValue = "10") Integer limit) {

        List<java.util.Map<String, Object>> history = ragEvaluator.getRecentResults(merchantId, limit);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("merchantId", merchantId);
        response.put("count", history.size());
        response.put("history", history);

        return response;
    }

    /**
     * 获取MySQL全文检索状态（PGVector降级方案）
     *
     * GET /api/agent/rag/mysql-ft/status
     */
    @GetMapping("/mysql-ft/status")
    public Map<String, Object> getMysqlFtStatus() {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("status", mysqlFullTextRetriever.getStatistics());
        return response;
    }
}
