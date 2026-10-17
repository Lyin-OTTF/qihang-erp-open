package cn.qihangerp.agent.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
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
    private final LlmEvaluationService llmEvaluationService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 运行完整评测并持久化结果
     *
     * @param merchantId 商户ID
     * @param topK       检索Top-K
     * @param remark     备注
     * @return 评测报告
     */
    @Transactional(rollbackFor = Exception.class)
    public RagEvaluationReport runEvaluationAndPersist(Long merchantId, int topK, String remark) {
        return runEvaluationAndPersist(merchantId, topK, remark, new EvaluationOptions());
    }

    /**
     * 运行评测并持久化聚合结果和逐用例明细。
     */
    @Transactional(rollbackFor = Exception.class)
    public RagEvaluationReport runEvaluationAndPersist(Long merchantId, int topK, String remark,
                                                       EvaluationOptions options) {
        RagEvaluationReport report = runEvaluation(merchantId, topK, options);
        Long runId = persistResult(merchantId, topK, report, remark, options);
        report.setRunId(runId);
        return report;
    }

    /**
     * 持久化评测结果到agent_rag_eval_result表
     */
    private Long persistResult(Long merchantId, int topK, RagEvaluationReport report,
                               String remark, EvaluationOptions options) {
        try {
            String sql = "INSERT INTO agent_rag_eval_result " +
                    "(merchant_id, eval_time, total_cases, passed_cases, pass_rate, " +
                    "avg_recall, avg_precision, avg_f1, avg_mrr, avg_ndcg, " +
                    "avg_answer_relevancy, avg_answer_correctness, avg_faithfulness, " +
                    "avg_retrieval_time, avg_generation_time, avg_overall_score, " +
                    "total_time, top_k, retrieval_mode, remark, retrieval_cases, llm_used, " +
                    "llm_rated_cases, avg_bleu, avg_rouge_l, model_name, evaluation_mode) " +
                    "VALUES (?, NOW(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
                int index = 1;
                ps.setLong(index++, merchantId);
                ps.setInt(index++, report.getTotalCases());
                ps.setInt(index++, report.getPassedCases());
                ps.setDouble(index++, report.getPassRate());
                ps.setDouble(index++, report.getAvgRecall());
                ps.setDouble(index++, report.getAvgPrecision());
                ps.setDouble(index++, report.getAvgF1());
                ps.setDouble(index++, report.getAvgMRR());
                ps.setDouble(index++, report.getAvgNDCG());
                ps.setDouble(index++, report.getAvgAnswerRelevancy());
                ps.setDouble(index++, report.getAvgAnswerCorrectness());
                ps.setDouble(index++, report.getAvgFaithfulness());
                ps.setLong(index++, report.getAvgRetrievalTime());
                ps.setLong(index++, report.getAvgGenerationTime());
                ps.setDouble(index++, report.getAvgOverallScore());
                ps.setLong(index++, report.getTotalTime());
                ps.setInt(index++, topK);
                ps.setString(index++, "HYBRID");
                ps.setString(index++, remark);
                ps.setInt(index++, report.getRetrievalCases());
                ps.setBoolean(index++, report.isLlmUsed());
                ps.setInt(index++, report.getLlmRatedCases());
                ps.setDouble(index++, report.getAvgBleu());
                ps.setDouble(index++, report.getAvgRougeL());
                ps.setString(index++, report.getModelName());
                ps.setString(index++, report.getEvaluationMode());
                return ps;
            }, keyHolder);
            Number key = keyHolder.getKey();
            if (key == null) {
                throw new IllegalStateException("评测结果未返回自增ID");
            }
            Long runId = key.longValue();
            persistCaseResults(runId, merchantId, report);
            log.info("评测结果已持久化: merchantId={}, passRate={}%", merchantId, String.format("%.2f", report.getPassRate()));
            return runId;
        } catch (Exception e) {
            throw new IllegalStateException("持久化评测结果失败: " + e.getMessage(), e);
        }
    }

    /**
     * 查询最近N次评测结果
     */
    public List<java.util.Map<String, Object>> getRecentResults(Long merchantId, int limit) {
        String sql = "SELECT id, eval_time, total_cases, passed_cases, pass_rate, " +
                "avg_recall, avg_precision, avg_f1, avg_mrr, avg_ndcg, " +
                "avg_overall_score, total_time, retrieval_mode, remark, retrieval_cases, " +
                "llm_used, llm_rated_cases, avg_bleu, avg_rouge_l, model_name, evaluation_mode " +
                "FROM agent_rag_eval_result " +
                "WHERE merchant_id = ? " +
                "ORDER BY eval_time DESC LIMIT ?";
        return jdbcTemplate.queryForList(sql, merchantId, limit);
    }

    /**
     * 运行完整评测
     *
     * @param merchantId 商户ID
     * @param topK       检索Top-K
     * @return 评测报告
     */
    public RagEvaluationReport runEvaluation(Long merchantId, int topK) {
        return runEvaluation(merchantId, topK, new EvaluationOptions());
    }

    public RagEvaluationReport runEvaluation(Long merchantId, int topK, EvaluationOptions options) {
        log.info("开始RAG离线评测: merchantId={}, topK={}", merchantId, topK);
        if (options == null) {
            options = new EvaluationOptions();
        }
        if (options.isRequireLlm()
                && (llmEvaluationService == null || !llmEvaluationService.isLlmEnabled())) {
            throw new IllegalStateException("真实LLM评测已启用，但LlmEvaluationService不可用或未开启");
        }

        long startTime = System.currentTimeMillis();

        // 步骤1: 加载评测集
        List<EvaluationCase> cases = loadEvaluationCases(merchantId);
        if (options.getMaxCases() != null && options.getMaxCases() > 0
                && cases.size() > options.getMaxCases()) {
            cases = new ArrayList<>(cases.subList(0, options.getMaxCases()));
        }
        log.info("加载{}条评测用例", cases.size());

        // 步骤2: 逐个评测
        List<RagEvaluationMetrics> metricsList = new ArrayList<>();
        List<CaseEvaluationResult> caseResults = new ArrayList<>();
        int passedCount = 0;

        for (int i = 0; i < cases.size(); i++) {
            EvaluationCase testCase = cases.get(i);
            log.info("评测用例 {}/{}: {}", i + 1, cases.size(), testCase.getQuestion());

            CaseEvaluation evaluation = evaluateSingleCase(testCase, topK, merchantId, options);
            RagEvaluationMetrics metrics = evaluation.getMetrics();
            metricsList.add(metrics);
            caseResults.add(buildCaseResult(testCase, evaluation));

            if (metrics.getOverall().isPassed()) {
                passedCount++;
            }
        }

        // 步骤3: 聚合统计
        RagEvaluationReport report = aggregateMetrics(metricsList, cases.size(), passedCount);
        report.setCaseResults(caseResults);
        report.setLlmRatedCases((int) caseResults.stream()
                .filter(CaseEvaluationResult::isLlmUsed)
                .count());
        report.setTotalTime(System.currentTimeMillis() - startTime);
        report.setEvaluationMode(options.isRequireLlm()
                ? "LLM_STRICT"
                : (llmEvaluationService != null && llmEvaluationService.isLlmEnabled()
                ? "LLM_WITH_FALLBACK" : "HEURISTIC"));
        report.setModelName(llmEvaluationService != null && llmEvaluationService.isLlmEnabled()
                ? llmEvaluationService.getModelName() : "heuristic");
        report.setLlmUsed(report.getLlmRatedCases() > 0);

        log.info("RAG离线评测完成: 总用例={}, 通过={}, 通过率={}%",
                cases.size(), passedCount, String.format("%.2f", report.getPassRate()));

        return report;
    }

    /**
     * 评测单个用例
     */
    private CaseEvaluation evaluateSingleCase(EvaluationCase testCase, int topK, Long merchantId,
                                              EvaluationOptions options) {
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

        // 步骤2: 生成评测
        long generationStart = System.currentTimeMillis();
        String generatedAnswer = null;
        boolean llmUsed = false;
        boolean llmEnabled = llmEvaluationService != null && llmEvaluationService.isLlmEnabled();
        if (llmEnabled) {
            generatedAnswer = llmEvaluationService.generateAnswer(testCase.getQuestion(), retrievalResults);
            if (generatedAnswer != null && !generatedAnswer.isBlank()) {
                llmUsed = true;
            } else if (options.isRequireLlm()) {
                throw new IllegalStateException("LLM答案生成失败: " + testCase.getQuestion());
            }
        }
        if (generatedAnswer == null || generatedAnswer.isBlank()) {
            generatedAnswer = generateAnswer(retrievalResults, testCase.getQuestion());
        }
        // 步骤2.5: 生成质量打分（LLM启用时用LLM-as-judge，否则用启发式）
        RagEvaluationMetrics.GenerationMetrics generationMetrics;
        if (llmUsed && llmEvaluationService != null) {
            double[] scores = llmEvaluationService.judgeAnswer(
                    testCase.getQuestion(), testCase.getExpectedAnswer(),
                    generatedAnswer, retrievalResults);
            if (scores != null) {
                generationMetrics = new RagEvaluationMetrics.GenerationMetrics();
                generationMetrics.setGenerationTime(0L);
                generationMetrics.setAnswerRelevancy(scores[0]);
                generationMetrics.setAnswerCorrectness(scores[1]);
                generationMetrics.setFaithfulness(scores[2]);
                // 上下文利用率是确定性辅助指标；BLEU/ROUGE使用标准算法
                generationMetrics.setContextUtilization(calculateContextUtilization(generatedAnswer, retrievalResults));
                generationMetrics.setBleuScore(TextGenerationMetrics.bleu4(
                        generatedAnswer, testCase.getExpectedAnswer()));
                generationMetrics.setRougeScore(TextGenerationMetrics.rougeL(
                        generatedAnswer, testCase.getExpectedAnswer()));
                log.debug("LLM裁判打分: relevancy={}, correctness={}, faithfulness={}", scores[0], scores[1], scores[2]);
            } else {
                if (options.isRequireLlm()) {
                    throw new IllegalStateException("LLM裁判打分失败: " + testCase.getQuestion());
                }
                generationMetrics = evaluateGeneration(generatedAnswer, testCase.getExpectedAnswer(),
                        retrievalResults, 0L);
            }
        } else {
            generationMetrics = evaluateGeneration(generatedAnswer, testCase.getExpectedAnswer(),
                    retrievalResults, 0L);
        }
        long generationTime = System.currentTimeMillis() - generationStart;
        generationMetrics.setGenerationTime(generationTime);
        metrics.setGeneration(generationMetrics);

        // 步骤3: 整体评测
        RagEvaluationMetrics.OverallMetrics overallMetrics = new RagEvaluationMetrics.OverallMetrics();
        overallMetrics.setTotalTime(retrievalTime + generationTime);
        metrics.setOverall(overallMetrics);

        metrics.calculateOverallScore();

        CaseEvaluation evaluation = new CaseEvaluation();
        evaluation.setMetrics(metrics);
        evaluation.setGeneratedAnswer(generatedAnswer);
        evaluation.setRetrievedChunks(retrievalResults.stream()
                .map(RetrievalResult::getChunkId)
                .collect(Collectors.toList()));
        evaluation.setLlmUsed(llmUsed);
        return evaluation;
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
            // 无相关性标注的用例不参与检索均值
            metrics.setAnnotated(false);
            metrics.setRecall(0.0);
            metrics.setPrecision(0.0);
            metrics.setF1Score(0.0);
            metrics.setMrr(0.0);
            metrics.setNdcg(0.0);
            metrics.setHit(false);
            return metrics;
        }
        metrics.setAnnotated(true);

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

        double bleuScore = TextGenerationMetrics.bleu4(generatedAnswer, expectedAnswer);
        double rougeScore = TextGenerationMetrics.rougeL(generatedAnswer, expectedAnswer);
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
            List<String> relevantChunks = new ArrayList<>();
            if (chunksJson != null && !chunksJson.isBlank()) {
                try {
                    objectMapper.readTree(chunksJson).forEach(node -> {
                        if (node.isTextual() && !node.asText().isBlank()) {
                            relevantChunks.add(node.asText());
                        }
                    });
                } catch (Exception e) {
                    log.warn("解析relevant_chunks失败: {}", chunksJson);
                }
            }
            testCase.setRelevantChunks(relevantChunks);

            testCase.setDomain(rs.getString("domain"));
            testCase.setDifficulty(rs.getString("difficulty"));

            return testCase;
        }, merchantId);
    }

    private CaseEvaluationResult buildCaseResult(EvaluationCase testCase, CaseEvaluation evaluation) {
        CaseEvaluationResult result = new CaseEvaluationResult();
        result.setQuestion(testCase.getQuestion());
        result.setExpectedAnswer(testCase.getExpectedAnswer());
        result.setGeneratedAnswer(evaluation.getGeneratedAnswer());
        result.setRelevantChunks(testCase.getRelevantChunks());
        result.setRetrievedChunks(evaluation.getRetrievedChunks());
        result.setLlmUsed(evaluation.isLlmUsed());
        result.setRetrieval(evaluation.getMetrics().getRetrieval());
        result.setGeneration(evaluation.getMetrics().getGeneration());
        result.setOverall(evaluation.getMetrics().getOverall());
        return result;
    }

    private void persistCaseResults(Long runId, Long merchantId, RagEvaluationReport report) {
        if (report.getCaseResults() == null || report.getCaseResults().isEmpty()) {
            return;
        }
        String sql = "INSERT INTO agent_rag_eval_case_result " +
                "(run_id, merchant_id, question, expected_answer, generated_answer, " +
                "retrieved_chunks, relevant_chunks, recall, precision_score, f1_score, mrr, ndcg, " +
                "answer_relevancy, answer_correctness, faithfulness, bleu_score, rouge_l, " +
                "retrieval_time, generation_time, llm_used) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        List<Object[]> rows = new ArrayList<>();
        for (CaseEvaluationResult result : report.getCaseResults()) {
            RagEvaluationMetrics.RetrievalMetrics retrieval = result.getRetrieval();
            RagEvaluationMetrics.GenerationMetrics generation = result.getGeneration();
            rows.add(new Object[]{
                    runId,
                    merchantId,
                    result.getQuestion(),
                    result.getExpectedAnswer(),
                    result.getGeneratedAnswer(),
                    toJsonArray(result.getRetrievedChunks()),
                    toJsonArray(result.getRelevantChunks()),
                    retrieval.getRecall(),
                    retrieval.getPrecision(),
                    retrieval.getF1Score(),
                    retrieval.getMrr(),
                    retrieval.getNdcg(),
                    generation.getAnswerRelevancy(),
                    generation.getAnswerCorrectness(),
                    generation.getFaithfulness(),
                    generation.getBleuScore(),
                    generation.getRougeScore(),
                    retrieval.getRetrievalTime(),
                    generation.getGenerationTime(),
                    result.isLlmUsed()
            });
        }
        jdbcTemplate.batchUpdate(sql, rows);
    }

    private String toJsonArray(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (Exception e) {
            return "[]";
        }
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

        List<RagEvaluationMetrics> retrievalMetrics = metricsList.stream()
                .filter(m -> m.getRetrieval() != null && m.getRetrieval().isAnnotated())
                .collect(Collectors.toList());
        report.setRetrievalCases(retrievalMetrics.size());

        // 计算平均值
        double avgRecall = retrievalMetrics.stream()
                .mapToDouble(m -> m.getRetrieval().getRecall())
                .average()
                .orElse(0.0);

        double avgPrecision = retrievalMetrics.stream()
                .mapToDouble(m -> m.getRetrieval().getPrecision())
                .average()
                .orElse(0.0);

        double avgF1 = retrievalMetrics.stream()
                .mapToDouble(m -> m.getRetrieval().getF1Score())
                .average()
                .orElse(0.0);

        double avgMRR = retrievalMetrics.stream()
                .mapToDouble(m -> m.getRetrieval().getMrr())
                .average()
                .orElse(0.0);

        double avgNDCG = retrievalMetrics.stream()
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

        double avgBleu = metricsList.stream()
                .mapToDouble(m -> m.getGeneration().getBleuScore())
                .average()
                .orElse(0.0);

        double avgRouge = metricsList.stream()
                .mapToDouble(m -> m.getGeneration().getRougeScore())
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
        report.setAvgBleu(avgBleu);
        report.setAvgRougeL(avgRouge);
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

    @Data
    private static class CaseEvaluation {
        private RagEvaluationMetrics metrics;
        private String generatedAnswer;
        private List<String> retrievedChunks;
        private boolean llmUsed;
    }

    @Data
    public static class EvaluationOptions {
        /**
         * true 时禁止启发式降级，任一LLM调用失败即终止评测。
         */
        private boolean requireLlm = false;

        /**
         * null或<=0表示评测全部用例。
         */
        private Integer maxCases;
    }

    @Data
    public static class CaseEvaluationResult {
        private String question;
        private String expectedAnswer;
        private String generatedAnswer;
        private List<String> relevantChunks;
        private List<String> retrievedChunks;
        private boolean llmUsed;
        private RagEvaluationMetrics.RetrievalMetrics retrieval;
        private RagEvaluationMetrics.GenerationMetrics generation;
        private RagEvaluationMetrics.OverallMetrics overall;
    }

    /**
     * 评测报告
     */
    @Data
    public static class RagEvaluationReport {
        private Long runId;
        private int totalCases;
        private int passedCases;
        private int retrievalCases;
        private int llmRatedCases;
        private boolean llmUsed;
        private double passRate;
        private long totalTime;
        private String evaluationMode;
        private String modelName;

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
        private double avgBleu;
        private double avgRougeL;

        // 性能指标
        private long avgRetrievalTime;
        private long avgGenerationTime;

        // 综合评分
        private double avgOverallScore;

        private List<CaseEvaluationResult> caseResults;
    }
}
