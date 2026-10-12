package cn.qihangerp.agent.rag;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * RAG离线评测器
 *
 * 使用agent_rag_evaluation表中的100条评测集数据，对RAG系统进行离线评测
 *
 * 评测维度：
 * 1. 检索质量：Recall、Precision、F1、MRR、NDCG@K
 * 2. 答案质量：相关性、正确性、忠实度
 * 3. 性能：检索耗时、生成耗时、端到端延迟
 *
 * @author AI Code Generator
 * @since 2026-10-12
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagEvaluator {

    private final JdbcTemplate jdbcTemplate;
    private final HybridRetriever hybridRetriever;
    private final CitationGenerator citationGenerator;

    /**
     * 运行完整评测
     *
     * @param merchantId 商户ID
     * @param topK       检索Top-K
     * @return 评测报告
     */
    public RagEvaluationReport runEvaluation(Long merchantId, int topK) {
        log.info("开始RAG离线评测: merchantId={}, topK={}", merchantId, topK);

        long startTime = System.currentTimeMillis();

        // 步骤1: 加载评测集
        List<EvaluationCase> cases = loadEvaluationCases(merchantId);
        log.info("加载{}条评测用例", cases.size());

        // 步骤2: 逐个评测
        List<RagEvaluationMetrics> metricsList = new ArrayList<>();
        int passedCount = 0;

        for (int i = 0; i < cases.size(); i++) {
            EvaluationCase testCase = cases.get(i);
            log.info("评测用例 {}/{}: {}", i + 1, cases.size(), testCase.getQuestion());

            RagEvaluationMetrics metrics = evaluateSingleCase(testCase, topK, merchantId);
            metricsList.add(metrics);

            if (metrics.getOverall().isPassed()) {
                passedCount++;
            }
        }

        // 步骤3: 聚合统计
        RagEvaluationReport report = aggregateMetrics(metricsList, cases.size(), passedCount);
        report.setTotalTime(System.currentTimeMillis() - startTime);

        log.info("RAG离线评测完成: 总用例={}, 通过={}, 通过率={:.2f}%",
                cases.size(), passedCount, report.getPassRate());

        return report;
    }

    /**
     * 评测单个用例
     */
    private RagEvaluationMetrics evaluateSingleCase(EvaluationCase testCase, int topK, Long merchantId) {
        RagEvaluationMetrics metrics = new RagEvaluationMetrics();

        // 步骤1: 检索评测
        long retrievalStart = System.currentTimeMillis();
        List<RetrievalResult> retrievalResults = hybridRetriever.retrieve(
                testCase.getQuestion(),
                topK,
                merchantId
        );
        long retrievalTime = System.currentTimeMillis() - retrievalStart;

        RagEvaluationMetrics.RetrievalMetrics retrievalMetrics = evaluateRetrieval(
                retrievalResults,
                testCase.getRelevantChunks(),
                retrievalTime
        );
        metrics.setRetrieval(retrievalMetrics);

        // 步骤2: 生成评测（模拟，实际需要调用LLM）
        long generationStart = System.currentTimeMillis();
        String generatedAnswer = generateAnswer(retrievalResults, testCase.getQuestion());
        long generationTime = System.currentTimeMillis() - generationStart;

        RagEvaluationMetrics.GenerationMetrics generationMetrics = evaluateGeneration(
                generatedAnswer,
                testCase.getExpectedAnswer(),
                retrievalResults,
                generationTime
        );
        metrics.setGeneration(generationMetrics);

        // 步骤3: 整体评测
        RagEvaluationMetrics.OverallMetrics overallMetrics = new RagEvaluationMetrics.OverallMetrics();
        overallMetrics.setTotalTime(retrievalTime + generationTime);
        metrics.setOverall(overallMetrics);

        metrics.calculateOverallScore();

        return metrics;
    }

    /**
     * 检索评测
     */
    private RagEvaluationMetrics.RetrievalMetrics evaluateRetrieval(
            List<RetrievalResult> retrievalResults,
            List<String> relevantChunks,
            long retrievalTime) {

        RagEvaluationMetrics.RetrievalMetrics metrics = new RagEvaluationMetrics.RetrievalMetrics();
        metrics.setRetrievalTime(retrievalTime);

        if (relevantChunks == null || relevantChunks.isEmpty()) {
            // 无标注数据，返回默认值
            metrics.setRecall(0.0);
            metrics.setPrecision(0.0);
            metrics.setF1Score(0.0);
            metrics.setMrr(0.0);
            metrics.setNdcg(0.0);
            metrics.setHit(false);
            return metrics;
        }

        Set<String> relevantSet = new HashSet<>(relevantChunks);
        Set<String> retrievedSet = retrievalResults.stream()
                .map(RetrievalResult::getChunkId)
                .collect(Collectors.toSet());

        // 计算召回率和精确率
        Set<String> intersection = new HashSet<>(retrievedSet);
        intersection.retainAll(relevantSet);

        double recall = relevantSet.isEmpty() ? 0.0 : (double) intersection.size() / relevantSet.size();
        double precision = retrievedSet.isEmpty() ? 0.0 : (double) intersection.size() / retrievedSet.size();
        double f1 = (precision + recall) == 0 ? 0.0 : 2 * precision * recall / (precision + recall);

        metrics.setRecall(recall);
        metrics.setPrecision(precision);
        metrics.setF1Score(f1);

        // 计算MRR（第一个相关文档的倒数排名）
        double mrr = 0.0;
        for (int i = 0; i < retrievalResults.size(); i++) {
            if (relevantSet.contains(retrievalResults.get(i).getChunkId())) {
                mrr = 1.0 / (i + 1);
                break;
            }
        }
        metrics.setMrr(mrr);

        // 计算NDCG@K
        double ndcg = calculateNDCG(retrievalResults, relevantSet);
        metrics.setNdcg(ndcg);

        // 计算Hit Rate
        boolean hit = !intersection.isEmpty();
        metrics.setHit(hit);

        return metrics;
    }

    /**
     * 生成评测
     */
    private RagEvaluationMetrics.GenerationMetrics evaluateGeneration(
            String generatedAnswer,
            String expectedAnswer,
            List<RetrievalResult> retrievalResults,
            long generationTime) {

        RagEvaluationMetrics.GenerationMetrics metrics = new RagEvaluationMetrics.GenerationMetrics();
        metrics.setGenerationTime(generationTime);

        if (expectedAnswer == null || expectedAnswer.isEmpty()) {
            // 无标准答案，返回默认值
            metrics.setAnswerRelevancy(0.0);
            metrics.setAnswerCorrectness(0.0);
            metrics.setFaithfulness(0.0);
            metrics.setContextUtilization(0.0);
            metrics.setBleuScore(0.0);
            metrics.setRougeScore(0.0);
            return metrics;
        }

        // 答案相关性（简化实现：基于关键词重叠）
        double answerRelevancy = calculateTextSimilarity(generatedAnswer, expectedAnswer);
        metrics.setAnswerRelevancy(answerRelevancy);

        // 答案正确性（与标准答案的匹配度）
        double answerCorrectness = calculateAnswerCorrectness(generatedAnswer, expectedAnswer);
        metrics.setAnswerCorrectness(answerCorrectness);

        // 忠实度（答案是否基于检索到的文档）
        double faithfulness = calculateFaithfulness(generatedAnswer, retrievalResults);
        metrics.setFaithfulness(faithfulness);

        // 上下文利用率
        double contextUtilization = calculateContextUtilization(generatedAnswer, retrievalResults);
        metrics.setContextUtilization(contextUtilization);

        // BLEU和ROUGE（简化实现）
        double bleuScore = calculateBLEU(generatedAnswer, expectedAnswer);
        double rougeScore = calculateROUGE(generatedAnswer, expectedAnswer);
        metrics.setBleuScore(bleuScore);
        metrics.setRougeScore(rougeScore);

        return metrics;
    }

    /**
     * 计算NDCG@K
     */
    private double calculateNDCG(List<RetrievalResult> retrievalResults, Set<String> relevantSet) {
        if (retrievalResults.isEmpty() || relevantSet.isEmpty()) {
            return 0.0;
        }

        // DCG (Discounted Cumulative Gain)
        double dcg = 0.0;
        for (int i = 0; i < retrievalResults.size(); i++) {
            boolean isRelevant = relevantSet.contains(retrievalResults.get(i).getChunkId());
            double rel = isRelevant ? 1.0 : 0.0;
            dcg += rel / Math.log(i + 2); // log(i+2) because i starts from 0
        }

        // IDCG (Ideal DCG)
        double idcg = 0.0;
        int relevantCount = Math.min(retrievalResults.size(), relevantSet.size());
        for (int i = 0; i < relevantCount; i++) {
            idcg += 1.0 / Math.log(i + 2);
        }

        return idcg == 0 ? 0.0 : dcg / idcg;
    }

    /**
     * 计算文本相似度（简化实现：基于Jaccard相似度）
     */
    private double calculateTextSimilarity(String text1, String text2) {
        Set<String> words1 = new HashSet<>(Arrays.asList(text1.toLowerCase().split("\\s+")));
        Set<String> words2 = new HashSet<>(Arrays.asList(text2.toLowerCase().split("\\s+")));

        Set<String> intersection = new HashSet<>(words1);
        intersection.retainAll(words2);

        Set<String> union = new HashSet<>(words1);
        union.addAll(words2);

        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }

    /**
     * 计算答案正确性
     */
    private double calculateAnswerCorrectness(String generated, String expected) {
        // 简化实现：字符串包含判断
        if (generated.contains(expected) || expected.contains(generated)) {
            return 1.0;
        }
        return calculateTextSimilarity(generated, expected);
    }

    /**
     * 计算忠实度（答案是否基于检索文档）
     */
    private double calculateFaithfulness(String answer, List<RetrievalResult> retrievalResults) {
        if (retrievalResults.isEmpty()) {
            return 0.0;
        }

        // 检查答案中的关键词是否出现在检索文档中
        String[] answerWords = answer.toLowerCase().split("\\s+");
        int matchCount = 0;

        for (String word : answerWords) {
            if (word.length() <= 2) continue;

            for (RetrievalResult result : retrievalResults) {
                if (result.getContent().toLowerCase().contains(word)) {
                    matchCount++;
                    break;
                }
            }
        }

        return answerWords.length == 0 ? 0.0 : (double) matchCount / answerWords.length;
    }

    /**
     * 计算上下文利用率
     */
    private double calculateContextUtilization(String answer, List<RetrievalResult> retrievalResults) {
        if (retrievalResults.isEmpty()) {
            return 0.0;
        }

        int usedChunks = 0;
        for (RetrievalResult result : retrievalResults) {
            String[] chunkWords = result.getContent().toLowerCase().split("\\s+");
            for (String word : chunkWords) {
                if (word.length() > 2 && answer.toLowerCase().contains(word)) {
                    usedChunks++;
                    break;
                }
            }
        }

        return (double) usedChunks / retrievalResults.size();
    }

    /**
     * 计算BLEU分数（简化实现）
     */
    private double calculateBLEU(String generated, String expected) {
        return calculateTextSimilarity(generated, expected);
    }

    /**
     * 计算ROUGE分数（简化实现）
     */
    private double calculateROUGE(String generated, String expected) {
        return calculateTextSimilarity(generated, expected);
    }

    /**
     * 生成答案（模拟，实际需要调用LLM）
     */
    private String generateAnswer(List<RetrievalResult> retrievalResults, String question) {
        // 简化实现：拼接检索结果的前3个chunk
        StringBuilder sb = new StringBuilder();
        sb.append("根据检索到的信息，");

        int limit = Math.min(3, retrievalResults.size());
        for (int i = 0; i < limit; i++) {
            sb.append(retrievalResults.get(i).getContent());
            if (i < limit - 1) {
                sb.append("，");
            }
        }

        return sb.toString();
    }

    /**
     * 加载评测用例
     */
    private List<EvaluationCase> loadEvaluationCases(Long merchantId) {
        String sql = "SELECT id, question, expected_answer, relevant_chunks, domain, difficulty " +
                "FROM agent_rag_evaluation " +
                "WHERE merchant_id = ? " +
                "ORDER BY id";

        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            EvaluationCase testCase = new EvaluationCase();
            testCase.setId(rs.getLong("id"));
            testCase.setQuestion(rs.getString("question"));
            testCase.setExpectedAnswer(rs.getString("expected_answer"));

            // 解析relevant_chunks（JSON数组）
            String chunksJson = rs.getString("relevant_chunks");
            if (chunksJson != null && !chunksJson.isEmpty()) {
                // 简化实现：假设格式为 ["chunk_001", "chunk_002"]
                chunksJson = chunksJson.replace("[", "").replace("]", "").replace("\"", "");
                testCase.setRelevantChunks(Arrays.asList(chunksJson.split(",")));
            } else {
                testCase.setRelevantChunks(new ArrayList<>());
            }

            testCase.setDomain(rs.getString("domain"));
            testCase.setDifficulty(rs.getString("difficulty"));

            return testCase;
        }, merchantId);
    }

    /**
     * 聚合指标
     */
    private RagEvaluationReport aggregateMetrics(List<RagEvaluationMetrics> metricsList,
                                                  int totalCases,
                                                  int passedCount) {
        RagEvaluationReport report = new RagEvaluationReport();
        report.setTotalCases(totalCases);
        report.setPassedCases(passedCount);
        report.setPassRate(totalCases == 0 ? 0.0 : (double) passedCount / totalCases * 100);

        if (metricsList.isEmpty()) {
            return report;
        }

        // 计算平均值
        double avgRecall = metricsList.stream()
                .mapToDouble(m -> m.getRetrieval().getRecall())
                .average()
                .orElse(0.0);

        double avgPrecision = metricsList.stream()
                .mapToDouble(m -> m.getRetrieval().getPrecision())
                .average()
                .orElse(0.0);

        double avgF1 = metricsList.stream()
                .mapToDouble(m -> m.getRetrieval().getF1Score())
                .average()
                .orElse(0.0);

        double avgMRR = metricsList.stream()
                .mapToDouble(m -> m.getRetrieval().getMrr())
                .average()
                .orElse(0.0);

        double avgNDCG = metricsList.stream()
                .mapToDouble(m -> m.getRetrieval().getNdcg())
                .average()
                .orElse(0.0);

        double avgAnswerRelevancy = metricsList.stream()
                .mapToDouble(m -> m.getGeneration().getAnswerRelevancy())
                .average()
                .orElse(0.0);

        double avgAnswerCorrectness = metricsList.stream()
                .mapToDouble(m -> m.getGeneration().getAnswerCorrectness())
                .average()
                .orElse(0.0);

        double avgFaithfulness = metricsList.stream()
                .mapToDouble(m -> m.getGeneration().getFaithfulness())
                .average()
                .orElse(0.0);

        long avgRetrievalTime = (long) metricsList.stream()
                .mapToLong(m -> m.getRetrieval().getRetrievalTime())
                .average()
                .orElse(0.0);

        long avgGenerationTime = (long) metricsList.stream()
                .mapToLong(m -> m.getGeneration().getGenerationTime())
                .average()
                .orElse(0.0);

        double avgOverallScore = metricsList.stream()
                .mapToDouble(m -> m.getOverall().getOverallScore())
                .average()
                .orElse(0.0);

        report.setAvgRecall(avgRecall);
        report.setAvgPrecision(avgPrecision);
        report.setAvgF1(avgF1);
        report.setAvgMRR(avgMRR);
        report.setAvgNDCG(avgNDCG);
        report.setAvgAnswerRelevancy(avgAnswerRelevancy);
        report.setAvgAnswerCorrectness(avgAnswerCorrectness);
        report.setAvgFaithfulness(avgFaithfulness);
        report.setAvgRetrievalTime(avgRetrievalTime);
        report.setAvgGenerationTime(avgGenerationTime);
        report.setAvgOverallScore(avgOverallScore);

        return report;
    }

    /**
     * 评测用例
     */
    @Data
    private static class EvaluationCase {
        private Long id;
        private String question;
        private String expectedAnswer;
        private List<String> relevantChunks;
        private String domain;
        private String difficulty;
    }

    /**
     * 评测报告
     */
    @Data
    public static class RagEvaluationReport {
        private int totalCases;
        private int passedCases;
        private double passRate;
        private long totalTime;

        // 检索指标
        private double avgRecall;
        private double avgPrecision;
        private double avgF1;
        private double avgMRR;
        private double avgNDCG;

        // 生成指标
        private double avgAnswerRelevancy;
        private double avgAnswerCorrectness;
        private double avgFaithfulness;

        // 性能指标
        private long avgRetrievalTime;
        private long avgGenerationTime;

        // 综合评分
        private double avgOverallScore;
    }
}
