package cn.qihangerp.agent.rag;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 检索结果
 *
 * 表示一个召回的chunk及其相关信息
 *
 * @author AI Code Generator
 * @since 2026-10-12
 */
@Data
public class RetrievalResult {

    /**
     * Chunk唯一标识
     */
    private String chunkId;

    /**
     * Chunk内容
     */
    private String content;

    /**
     * 相似度分数（0-1）
     */
    private double score;

    /**
     * 排名（1-based）
     */
    private int rank;

    /**
     * 所属文档ID
     */
    private String docId;

    /**
     * 所属文档名称
     */
    private String docName;

    /**
     * 检索方法（VECTOR/BM25/HYBRID）
     */
    private String retrievalMethod;

    /**
     * 元数据
     */
    private Map<String, Object> metadata;

    /**
     * BM25分数（如果使用BM25检索）
     */
    private Double bm25Score;

    /**
     * 向量相似度分数（如果使用向量检索）
     */
    private Double vectorScore;

    /**
     * RRF融合分数（如果使用混合检索）
     */
    private Double rrfScore;

    /**
     * Rerank重排序分数（如果启用了Rerank）
     */
    private Double rerankScore;

    /**
     * 创建向量检索结果
     */
    public static RetrievalResult createVectorResult(String chunkId, String content,
                                                     double vectorScore, String docId) {
        RetrievalResult result = new RetrievalResult();
        result.setChunkId(chunkId);
        result.setContent(content);
        result.setVectorScore(vectorScore);
        result.setScore(vectorScore);
        result.setDocId(docId);
        result.setRetrievalMethod("VECTOR");
        return result;
    }

    /**
     * 创建BM25检索结果
     */
    public static RetrievalResult createBM25Result(String chunkId, String content,
                                                   double bm25Score, String docId) {
        RetrievalResult result = new RetrievalResult();
        result.setChunkId(chunkId);
        result.setContent(content);
        result.setBm25Score(bm25Score);
        result.setScore(bm25Score);
        result.setDocId(docId);
        result.setRetrievalMethod("BM25");
        return result;
    }

    /**
     * 创建混合检索结果
     */
    public static RetrievalResult createHybridResult(String chunkId, String content,
                                                     double vectorScore, double bm25Score,
                                                     double rrfScore, String docId) {
        RetrievalResult result = new RetrievalResult();
        result.setChunkId(chunkId);
        result.setContent(content);
        result.setVectorScore(vectorScore);
        result.setBm25Score(bm25Score);
        result.setRrfScore(rrfScore);
        result.setScore(rrfScore);
        result.setDocId(docId);
        result.setRetrievalMethod("HYBRID");
        return result;
    }
}
