package cn.qihangerp.agent.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 引用生成器
 *
 * 将检索结果转换为规范的引用格式，用于RAG回答中
 *
 * 功能：
 * 1. 从检索结果生成引用列表
 * 2. 在AI回答中插入引用标记
 * 3. 生成多种引用格式（Markdown、纯文本、简洁版）
 * 4. 支持去重和排序
 *
 * @author AI Code Generator
 * @since 2026-10-12
 */
@Slf4j
@Component
public class CitationGenerator {

    /**
     * 从检索结果生成引用列表
     *
     * @param retrievalResults 检索结果列表
     * @param maxCitations     最大引用数量
     * @return 引用列表
     */
    public List<Citation> generateCitations(List<RetrievalResult> retrievalResults, int maxCitations) {
        if (retrievalResults == null || retrievalResults.isEmpty()) {
            return new ArrayList<>();
        }

        // 按分数降序排序并截取Top-N
        List<RetrievalResult> topResults = retrievalResults.stream()
                .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
                .limit(maxCitations)
                .collect(Collectors.toList());

        // 转换为Citation对象
        List<Citation> citations = new ArrayList<>();
        for (int i = 0; i < topResults.size(); i++) {
            RetrievalResult result = topResults.get(i);
            Citation citation = new Citation(
                    i + 1, // 引用编号从1开始
                    result.getDocId(),
                    result.getDocName(),
                    result.getChunkId(),
                    result.getContent(),
                    result.getScore(),
                    result.getRetrievalMethod()
            );
            citations.add(citation);
        }

        log.info("生成{}个引用", citations.size());
        return citations;
    }

    /**
     * 在AI回答中插入引用标记
     *
     * 策略：
     * 1. 检测回答中是否提及引用内容的关键词
     * 2. 在相关句子末尾插入引用编号[1][2]
     * 3. 如果无法自动匹配，则在回答末尾统一添加
     *
     * @param answer           AI生成的回答
     * @param citations        引用列表
     * @param autoInsert       是否自动插入引用标记
     * @return 带引用标记的回答
     */
    public String insertCitations(String answer, List<Citation> citations, boolean autoInsert) {
        if (citations == null || citations.isEmpty()) {
            return answer;
        }

        if (!autoInsert) {
            // 简单模式：在末尾统一添加引用编号
            StringBuilder sb = new StringBuilder(answer);
            sb.append(" ");
            for (Citation citation : citations) {
                sb.append(citation.toShortString());
            }
            return sb.toString();
        }

        // TODO: 智能模式：分析回答内容，在相关位置插入引用
        // 需要NLP技术进行语义匹配
        // 当前版本使用简单关键词匹配

        String result = answer;
        for (Citation citation : citations) {
            // 提取引用片段的关键词（简化实现）
            String snippet = citation.getSnippet();
            if (snippet.length() > 20) {
                String keyword = extractKeyword(snippet);
                if (keyword != null && result.contains(keyword)) {
                    // 在关键词后插入引用标记
                    result = result.replace(keyword, keyword + citation.toShortString());
                }
            }
        }

        // 如果没有自动插入任何引用，则在末尾添加所有引用
        boolean hasInserted = false;
        for (Citation citation : citations) {
            if (result.contains(citation.toShortString())) {
                hasInserted = true;
                break;
            }
        }

        if (!hasInserted) {
            StringBuilder sb = new StringBuilder(result);
            sb.append(" ");
            for (Citation citation : citations) {
                sb.append(citation.toShortString());
            }
            result = sb.toString();
        }

        return result;
    }

    /**
     * 提取关键词（简化实现）
     *
     * 生产环境建议使用：
     * - HanLP关键词提取
     * - TextRank算法
     * - TF-IDF算法
     *
     * @param text 文本
     * @return 关键词
     */
    private String extractKeyword(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }

        // 简化实现：取前20个字符作为关键词
        if (text.length() > 20) {
            return text.substring(0, 20);
        }

        return text;
    }

    /**
     * 生成RAG响应（包含回答和引用）
     *
     * @param answer           AI生成的回答
     * @param retrievalResults 检索结果
     * @param retrievalMethod  检索方法
     * @param duration         耗时（毫秒）
     * @param maxCitations     最大引用数量（默认5）
     * @return RAG响应对象
     */
    public RagResponse generateResponse(String answer,
                                        List<RetrievalResult> retrievalResults,
                                        String retrievalMethod,
                                        long duration,
                                        int maxCitations) {
        // 生成引用列表
        List<Citation> citations = generateCitations(retrievalResults, maxCitations);

        // 在回答中插入引用标记
        String answerWithCitations = insertCitations(answer, citations, false);

        // 构建响应对象
        RagResponse response = new RagResponse();
        response.setAnswer(answerWithCitations);
        response.setCitations(citations);
        response.setRetrievedCount(retrievalResults.size());
        response.setRetrievalMethod(retrievalMethod);
        response.setDuration(duration);

        log.info("生成RAG响应: citations={}, retrievedCount={}, duration={}ms",
                citations.size(), retrievalResults.size(), duration);

        return response;
    }

    /**
     * 生成RAG响应（使用默认参数）
     */
    public RagResponse generateResponse(String answer,
                                        List<RetrievalResult> retrievalResults,
                                        String retrievalMethod,
                                        long duration) {
        return generateResponse(answer, retrievalResults, retrievalMethod, duration, 5);
    }

    /**
     * 格式化引用列表为Markdown
     *
     * @param citations 引用列表
     * @return Markdown格式的引用列表
     */
    public String formatCitationsMarkdown(List<Citation> citations) {
        if (citations == null || citations.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## 📚 参考文献\n\n");

        for (Citation citation : citations) {
            sb.append(citation.toMarkdown());
            sb.append("\n\n");
        }

        return sb.toString();
    }

    /**
     * 格式化引用列表为纯文本
     *
     * @param citations 引用列表
     * @return 纯文本格式的引用列表
     */
    public String formatCitationsPlainText(List<Citation> citations) {
        if (citations == null || citations.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("参考文献：\n");

        for (Citation citation : citations) {
            sb.append(citation.toFormattedString());
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * 去重引用（基于chunkId）
     *
     * @param citations 引用列表
     * @return 去重后的引用列表
     */
    public List<Citation> deduplicateCitations(List<Citation> citations) {
        if (citations == null || citations.isEmpty()) {
            return new ArrayList<>();
        }

        return citations.stream()
                .collect(Collectors.toMap(
                        Citation::getChunkId,
                        c -> c,
                        (c1, c2) -> c1.getScore() > c2.getScore() ? c1 : c2
                ))
                .values()
                .stream()
                .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
                .collect(Collectors.toList());
    }

    /**
     * 过滤低质量引用
     *
     * @param citations      引用列表
     * @param minScore       最低分数阈值
     * @return 过滤后的引用列表
     */
    public List<Citation> filterLowQualityCitations(List<Citation> citations, double minScore) {
        if (citations == null || citations.isEmpty()) {
            return new ArrayList<>();
        }

        return citations.stream()
                .filter(c -> c.getScore() >= minScore)
                .collect(Collectors.toList());
    }
}
