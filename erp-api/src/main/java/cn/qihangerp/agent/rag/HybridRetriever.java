package cn.qihangerp.agent.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 混合检索器
 *
 * 融合BM25（关键词匹配）和向量检索（语义相似），使用RRF（Reciprocal Rank Fusion）算法融合结果
 *
 * 检索流程：
 * 1. 并行执行BM25检索和向量检索
 * 2. 使用RRF算法融合两个排序列表
 * 3. 返回Top-K结果
 *
 * RRF公式：
 * RRF_score(chunk) = sum(1 / (k + rank_i))
 * 其中 rank_i 是chunk在第i个排序列表中的排名，k是常数（默认60）
 *
 * @author AI Code Generator
 * @since 2026-10-12
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HybridRetriever {

    // RRF常数k（论文推荐值为60）
    private static final int RRF_K = 60;

    // BM25检索器
    private final BM25Retriever bm25Retriever;

    // 向量检索器（占位，需要集成PGVector）
    // private final VectorStoreRetriever vectorRetriever;

    /**
     * 混合检索
     *
     * @param query      查询问题
     * @param topK       返回数量
     * @param merchantId 商户ID（租户隔离）
     * @return 检索结果列表
     */
    public List<RetrievalResult> retrieve(String query, int topK, Long merchantId) {
        long startTime = System.currentTimeMillis();

        // 步骤1: 并行执行BM25和向量检索
        List<RetrievalResult> vectorResults = retrieveByVector(query, topK * 2, merchantId);
        List<RetrievalResult> bm25Results = retrieveByBM25(query, topK * 2, merchantId);

        log.debug("向量检索召回{}个结果，BM25检索召回{}个结果",
                vectorResults.size(), bm25Results.size());

        // 步骤2: RRF融合
        List<RetrievalResult> fusedResults = fuseWithRRF(vectorResults, bm25Results);

        // 步骤3: 截取Top-K
        List<RetrievalResult> topKResults = fusedResults.stream()
                .limit(topK)
                .collect(Collectors.toList());

        // 设置排名
        for (int i = 0; i < topKResults.size(); i++) {
            topKResults.get(i).setRank(i + 1);
        }

        long duration = System.currentTimeMillis() - startTime;
        log.info("混合检索完成: query={}, topK={}, duration={}ms",
                query, topK, duration);

        return topKResults;
    }

    /**
     * 向量检索（语义相似）
     */
    private List<RetrievalResult> retrieveByVector(String query, int topK, Long merchantId) {
        // TODO: 集成Spring AI VectorStore
        log.debug("执行向量检索（占位）: query=, topK={}", query, topK);

        // 占位实现：返回模拟数据
        List<RetrievalResult> results = new ArrayList<>();
        results.add(RetrievalResult.createVectorResult(
                "chunk_001", "订单查询相关内容...", 0.95, "doc_001"));
        results.add(RetrievalResult.createVectorResult(
                "chunk_002", "退款流程相关内容...", 0.88, "doc_002"));
        results.add(RetrievalResult.createVectorResult(
                "chunk_003", "库存管理相关内容...", 0.82, "doc_003"));

        return results;
    }

    /**
     * BM25检索（关键词匹配）
     */
    private List<RetrievalResult> retrieveByBM25(String query, int topK, Long merchantId) {
        log.debug("执行BM25检索: query={}, topK={}", query, topK);

        // 使用BM25Retriever进行检索
        List<RetrievalResult> results = bm25Retriever.retrieve(query, topK);

        // TODO: 添加租户隔离过滤（merchantId）
        // 需要在agent_knowledge_chunk表中添加merchant_id字段

        return results;
    }

    /**
     * RRF融合算法
     *
     * RRF_score(chunk) = sum(1 / (k + rank_i))
     * 其中 rank_i 是chunk在第i个排序列表中的排名（1-based）
     *
     * @param vectorResults 向量检索结果
     * @param bm25Results   BM25检索结果
     * @return 融合后的结果列表（按RRF分数降序）
     */
    private List<RetrievalResult> fuseWithRRF(List<RetrievalResult> vectorResults,
                                               List<RetrievalResult> bm25Results) {
        // 步骤1: 构建chunkId -> RRF分数的映射
        Map<String, Double> rrfScores = new HashMap<>();
        Map<String, RetrievalResult> chunkMap = new HashMap<>();

        // 处理向量检索结果
        for (int i = 0; i < vectorResults.size(); i++) {
            RetrievalResult result = vectorResults.get(i);
            String chunkId = result.getChunkId();
            int rank = i + 1; // 1-based排名
            double rrfScore = 1.0 / (RRF_K + rank);

            rrfScores.put(chunkId, rrfScores.getOrDefault(chunkId, 0.0) + rrfScore);
            chunkMap.put(chunkId, result);
        }

        // 处理BM25检索结果
        for (int i = 0; i < bm25Results.size(); i++) {
            RetrievalResult result = bm25Results.get(i);
            String chunkId = result.getChunkId();
            int rank = i + 1;
            double rrfScore = 1.0 / (RRF_K + rank);

            rrfScores.put(chunkId, rrfScores.getOrDefault(chunkId, 0.0) + rrfScore);
            if (!chunkMap.containsKey(chunkId)) {
                chunkMap.put(chunkId, result);
            }
        }

        // 步骤2: 创建融合结果
        List<RetrievalResult> fusedResults = new ArrayList<>();
        for (Map.Entry<String, Double> entry : rrfScores.entrySet()) {
            String chunkId = entry.getKey();
            double rrfScore = entry.getValue();
            RetrievalResult original = chunkMap.get(chunkId);

            // 获取原始分数
            Double vectorScore = original.getVectorScore();
            Double bm25Score = original.getBm25Score();

            RetrievalResult fusedResult = RetrievalResult.createHybridResult(
                    chunkId,
                    original.getContent(),
                    vectorScore != null ? vectorScore : 0.0,
                    bm25Score != null ? bm25Score : 0.0,
                    rrfScore,
                    original.getDocId()
            );
            fusedResult.setDocName(original.getDocName());
            fusedResult.setMetadata(original.getMetadata());

            fusedResults.add(fusedResult);
        }

        // 步骤3: 按RRF分数降序排序
        fusedResults.sort((a, b) -> Double.compare(b.getRrfScore(), a.getRrfScore()));

        log.debug("RRF融合完成: 向量{}个 + BM25{}个 -> 融合{}个",
                vectorResults.size(), bm25Results.size(), fusedResults.size());

        return fusedResults;
    }

    /**
     * 纯向量检索（不使用BM25）
     */
    public List<RetrievalResult> retrieveVectorOnly(String query, int topK, Long merchantId) {
        List<RetrievalResult> results = retrieveByVector(query, topK, merchantId);
        for (int i = 0; i < results.size(); i++) {
            results.get(i).setRank(i + 1);
        }
        return results;
    }

    /**
     * 纯BM25检索（不使用向量）
     */
    public List<RetrievalResult> retrieveBM25Only(String query, int topK, Long merchantId) {
        List<RetrievalResult> results = retrieveByBM25(query, topK, merchantId);
        for (int i = 0; i < results.size(); i++) {
            results.get(i).setRank(i + 1);
        }
        return results;
    }

    /**
     * 计算RRF分数（单个chunk在多个排序列表中的融合分数）
     *
     * @param ranks 排名列表（1-based）
     * @return RRF分数
     */
    public static double calculateRRFScore(List<Integer> ranks) {
        return ranks.stream()
                .mapToDouble(rank -> 1.0 / (RRF_K + rank))
                .sum();
    }
}

