package cn.qihangerp.agent.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 轻量级 Rerank 重排序器。
 *
 * <p>在 BM25 + 向量 RRF 融合之后，对 Top-N 候选结果做二次重排序，
 * 提升查询与文档的语义相关性。不调用外部 LLM，基于以下信号加权：
 * <ul>
 *   <li>查询词覆盖度：查询词在文档中出现的比例（越高越相关）</li>
 *   <li>查询词位置：查询词出现在文档开头权重更高</li>
 *   <li>词频密度：查询词在文档中的密集程度</li>
 *   <li>原始分数：BM25 分数和 RRF 分数作为基础分</li>
 * </ul>
 *
 * <p>配置项：agent.rag.rerank-enabled（是否启用）、
 * spring.ai.vectorstore.rag.rerank.top-n（重排序候选数，默认5）</p>
 *
 * @author 回溯开发-RAG增强
 * @since 2026-10-14
 */
@Slf4j
@Component
public class Reranker {

    private static final Pattern WORD_PATTERN = Pattern.compile("[\\u4e00-\\u9fa5]+|[a-zA-Z0-9]+");

    /** 是否启用 Rerank */
    @Value("${agent.rag.rerank-enabled:false}")
    private boolean rerankEnabled;

    /** Rerank 候选数（取 RRF 融合后的前 N 个做重排序） */
    @Value("${spring.ai.vectorstore.rag.rerank.top-n:5}")
    private int rerankTopN;

    /**
     * 对检索结果进行重排序。
     *
     * @param query      查询问题
     * @param candidates RRF 融合后的候选结果（已按 RRF 分数降序）
     * @param topK       最终返回数量
     * @return 重排序后的结果
     */
    public List<RetrievalResult> rerank(String query, List<RetrievalResult> candidates, int topK) {
        if (!rerankEnabled || candidates == null || candidates.isEmpty()) {
            return candidates;
        }

        // 只取前 rerankTopN 个候选做重排序，后面的保持原顺序
        int rerankCount = Math.min(rerankTopN, candidates.size());
        List<RetrievalResult> toRerank = new ArrayList<>(candidates.subList(0, rerankCount));
        List<RetrievalResult> rest = new ArrayList<>(candidates.subList(rerankCount, candidates.size()));

        List<String> queryTerms = tokenize(query);
        if (queryTerms.isEmpty()) {
            return candidates;
        }

        // 计算每个候选的重排序分数
        for (RetrievalResult result : toRerank) {
            double rerankScore = calculateRerankScore(queryTerms, result);
            result.setRerankScore(rerankScore);
        }

        // 按 rerankScore 降序，分数相同则保持原 RRF 顺序
        toRerank.sort((a, b) -> {
            int cmp = Double.compare(b.getRerankScore(), a.getRerankScore());
            if (cmp != 0) return cmp;
            return Double.compare(b.getRrfScore(), a.getRrfScore());
        });

        // 合并：重排序后的候选 + 剩余候选
        List<RetrievalResult> merged = new ArrayList<>(toRerank);
        merged.addAll(rest);

        log.debug("Rerank完成: query={}, 候选{}个, 重排序{}个, topK={}",
                query, candidates.size(), rerankCount, topK);

        return merged.stream().limit(topK).collect(Collectors.toList());
    }

    /**
     * 计算重排序分数。
     *
     * 分数 = 0.4 * 覆盖度 + 0.3 * 位置分 + 0.2 * 密度分 + 0.1 * 原始分归一化
     */
    private double calculateRerankScore(List<String> queryTerms, RetrievalResult result) {
        String content = result.getContent() != null ? result.getContent().toLowerCase() : "";
        if (content.isEmpty()) {
            return 0.0;
        }

        // 1. 查询词覆盖度（0-1）
        int matched = 0;
        int firstMatchPos = Integer.MAX_VALUE;
        int totalMatchPos = 0;
        for (String term : queryTerms) {
            int pos = content.indexOf(term.toLowerCase());
            if (pos >= 0) {
                matched++;
                firstMatchPos = Math.min(firstMatchPos, pos);
                totalMatchPos += pos;
            }
        }
        double coverage = (double) matched / queryTerms.size();

        // 2. 位置分（0-1）：第一个匹配词越靠前分数越高
        double positionScore = 0.0;
        if (firstMatchPos < Integer.MAX_VALUE) {
            positionScore = Math.max(0, 1.0 - (double) firstMatchPos / Math.max(content.length(), 1));
        }

        // 3. 密度分（0-1）：匹配词在文档中的密集程度
        double densityScore = 0.0;
        if (matched > 0) {
            double avgPos = (double) totalMatchPos / matched;
            densityScore = Math.max(0, 1.0 - avgPos / Math.max(content.length(), 1));
        }

        // 4. 原始分归一化（BM25分数，0-1近似）
        double rawScore = result.getBm25Score() != null ? result.getBm25Score() : 0.0;
        double normalizedRaw = Math.min(1.0, rawScore / 10.0);  // BM25分数通常在0-10之间

        // 加权融合
        return 0.4 * coverage + 0.3 * positionScore + 0.2 * densityScore + 0.1 * normalizedRaw;
    }

    /**
     * 简单分词（与 BM25Retriever 保持一致：中文 bigram + 英文原词）。
     */
    private List<String> tokenize(String text) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> tokens = new ArrayList<>();
        java.util.regex.Matcher matcher = WORD_PATTERN.matcher(text);
        while (matcher.find()) {
            String token = matcher.group().toLowerCase();
            if (token.length() <= 1) continue;
            boolean isChinese = token.chars().allMatch(c -> c >= 0x4e00 && c <= 0x9fa5);
            if (isChinese) {
                for (int i = 0; i < token.length() - 1; i++) {
                    tokens.add(token.substring(i, i + 2));
                }
            } else {
                tokens.add(token);
            }
        }
        return tokens;
    }

    public boolean isRerankEnabled() {
        return rerankEnabled;
    }

    public int getRerankTopN() {
        return rerankTopN;
    }
}
