package cn.qihangerp.agent.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * BM25检索器
 *
 * 实现BM25算法进行关键词匹配检索
 *
 * BM25公式：
 * BM25(Q, D) = sum(IDF(q_i) * (f(q_i, D) * (k1 + 1)) / (f(q_i, D) + k1 * (1 - b + b * |D| / avgDL)))
 *
 * 其中：
 * - Q: 查询
 * - D: 文档
 * - q_i: 查询中的第i个词
 * - f(q_i, D): 词q_i在文档D中的词频
 * - |D|: 文档D的长度
 * - avgDL: 所有文档的平均长度
 * - k1: 词频饱和参数（默认1.5）
 * - b: 长度归一化参数（默认0.75）
 * - IDF(q_i): 逆文档频率
 *
 * @author AI Code Generator
 * @since 2026-10-12
 */
@Slf4j
@Component
public class BM25Retriever {

    // BM25参数
    private static final double K1 = 1.5;  // 词频饱和参数
    private static final double B = 0.75;  // 长度归一化参数

    // 中文分词正则（简化实现，生产环境使用jieba或HanLP）
    private static final Pattern WORD_PATTERN = Pattern.compile("[\\u4e00-\\u9fa5]+|[a-zA-Z0-9]+");

    // 倒排索引（内存实现，生产环境存MySQL agent_bm25_index表）
    // chunkId -> 词频映射
    private final Map<String, Map<String, Integer>> invertedIndex = new HashMap<>();
    // chunkId -> chunk内容
    private final Map<String, String> chunkContent = new HashMap<>();
    // 文档频率（term -> 包含该term的chunk数量）
    private final Map<String, Integer> documentFrequency = new HashMap<>();
    // 平均文档长度
    private double avgDocLength = 0.0;
    // 总文档数
    private int totalDocs = 0;

    /**
     * 索引文档
     *
     * @param chunkId chunk唯一标识
     * @param content chunk内容
     */
    public void indexDocument(String chunkId, String content) {
        // 分词
        List<String> terms = tokenize(content);

        // 计算词频
        Map<String, Integer> termFreq = new HashMap<>();
        for (String term : terms) {
            termFreq.put(term, termFreq.getOrDefault(term, 0) + 1);
        }

        // 更新倒排索引
        invertedIndex.put(chunkId, termFreq);
        chunkContent.put(chunkId, content);

        // 更新文档频率
        for (String term : termFreq.keySet()) {
            documentFrequency.put(term, documentFrequency.getOrDefault(term, 0) + 1);
        }

        // 更新统计信息
        totalDocs++;
        avgDocLength = invertedIndex.values().stream()
                .mapToInt(tf -> tf.values().stream().mapToInt(Integer::intValue).sum())
                .average()
                .orElse(0.0);

        log.debug("文档已索引: chunkId={}, terms={}", chunkId, terms.size());
    }

    /**
     * BM25检索
     *
     * @param query 查询问题
     * @param topK  返回数量
     * @return 检索结果列表（按BM25分数降序）
     */
    public List<RetrievalResult> retrieve(String query, int topK) {
        // 查询分词
        List<String> queryTerms = tokenize(query);

        // 计算每个chunk的BM25分数
        Map<String, Double> scores = new HashMap<>();
        for (String chunkId : invertedIndex.keySet()) {
            double bm25Score = calculateBM25(queryTerms, chunkId);
            if (bm25Score > 0) {
                scores.put(chunkId, bm25Score);
            }
        }

        // 排序并截取Top-K
        List<RetrievalResult> results = scores.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(topK)
                .map(entry -> {
                    String chunkId = entry.getKey();
                    double score = entry.getValue();
                    String content = chunkContent.get(chunkId);
                    return RetrievalResult.createBM25Result(chunkId, content, score, null);
                })
                .collect(Collectors.toList());

        log.info("BM25检索完成: query={}, topK={}, matched={}", query, topK, results.size());
        return results;
    }

    /**
     * 计算BM25分数
     *
     * BM25(Q, D) = sum(IDF(q_i) * (f(q_i, D) * (k1 + 1)) / (f(q_i, D) + k1 * (1 - b + b * |D| / avgDL)))
     *
     * @param queryTerms 查询词列表
     * @param chunkId    chunk ID
     * @return BM25分数
     */
    private double calculateBM25(List<String> queryTerms, String chunkId) {
        Map<String, Integer> termFreq = invertedIndex.get(chunkId);
        if (termFreq == null || termFreq.isEmpty()) {
            return 0.0;
        }

        // 文档长度
        int docLength = termFreq.values().stream().mapToInt(Integer::intValue).sum();

        double score = 0.0;
        for (String term : queryTerms) {
            // 词频 f(q_i, D)
            int freq = termFreq.getOrDefault(term, 0);
            if (freq == 0) {
                continue;
            }

            // IDF(q_i)
            double idf = calculateIDF(term);

            // BM25公式
            double numerator = freq * (K1 + 1);
            double denominator = freq + K1 * (1 - B + B * docLength / avgDocLength);

            score += idf * (numerator / denominator);
        }

        return score;
    }

    /**
     * 计算IDF（逆文档频率）
     *
     * IDF(term) = log((N - df + 0.5) / (df + 0.5) + 1)
     *
     * 其中：
     * - N: 总文档数
     * - df: 包含该term的文档数
     *
     * @param term 词条
     * @return IDF值
     */
    private double calculateIDF(String term) {
        int df = documentFrequency.getOrDefault(term, 0);
        if (df == 0) {
            return 0.0;
        }

        return Math.log((totalDocs - df + 0.5) / (df + 0.5) + 1);
    }

    /**
     * 中文分词（bigram二元语法 + 英文/数字原词）
     *
     * <p>原实现用正则将整段连续中文作为单个token，导致查询"退款流程"
     * 无法匹配文档中的"已发货订单退款流程"（token边界不对齐）。
     * 改为中文bigram（重叠二元组）后，"退款流程"→[退款,款流,流程]，
     * 可与文档中的bigram部分匹配，是无专业分词器时的标准替代方案。</p>
     *
     * <p>生产环境建议替换为：
     * - jieba分词: https://github.com/huaban/jieba-analysis
     * - HanLP: https://github.com/hankcs/HanLP</p>
     *
     * @param text 文本
     * @return 词列表
     */
    private List<String> tokenize(String text) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> tokens = new ArrayList<>();
        java.util.regex.Matcher matcher = WORD_PATTERN.matcher(text);
        while (matcher.find()) {
            String token = matcher.group().toLowerCase();
            if (token.length() <= 1) {
                continue;
            }
            // 判断是否纯中文
            boolean isChinese = token.chars().allMatch(c -> c >= 0x4e00 && c <= 0x9fa5);
            if (isChinese) {
                // 中文bigram：生成重叠二元组
                for (int i = 0; i < token.length() - 1; i++) {
                    tokens.add(token.substring(i, i + 2));
                }
            } else {
                // 英文/数字保持原词
                tokens.add(token);
            }
        }

        return tokens;
    }

    /**
     * 清空索引
     */
    public void clearIndex() {
        invertedIndex.clear();
        chunkContent.clear();
        documentFrequency.clear();
        avgDocLength = 0.0;
        totalDocs = 0;
        log.info("BM25索引已清空");
    }

    /**
     * 获取索引统计信息
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalDocs", totalDocs);
        stats.put("avgDocLength", avgDocLength);
        stats.put("vocabularySize", documentFrequency.size());
        stats.put("indexedChunks", invertedIndex.size());
        return stats;
    }

    /**
     * 批量索引文档
     *
     * @param chunks chunk映射（chunkId -> content）
     */
    public void indexBatch(Map<String, String> chunks) {
        for (Map.Entry<String, String> entry : chunks.entrySet()) {
            indexDocument(entry.getKey(), entry.getValue());
        }
        log.info("批量索引完成: 共{}个chunk", chunks.size());
    }
}

