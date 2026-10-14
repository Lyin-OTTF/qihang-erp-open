package cn.qihangerp.agent.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * RAG离线评测定时调度器
 *
 * <p>每日凌晨2点自动运行RAG离线评测，评测结果持久化到agent_rag_eval_result表，
 * 支持历史趋势分析和质量监控。</p>
 *
 * <p>评测内容：对agent_rag_evaluation表中的100条评测用例执行混合检索，
 * 计算Recall/Precision/F1/MRR/NDCG等检索指标和答案质量指标。</p>
 *
 * @author AI Code Generator
 * @since 2026-10-12
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagEvaluationScheduler {

    private final RagEvaluator ragEvaluator;

    /**
     * 每日凌晨2点自动运行离线评测
     *
     * <p>cron表达式: 秒 分 时 日 月 周</p>
     * <p>0 0 2 * * ? = 每天02:00:00执行</p>
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void runDailyEvaluation() {
        log.info("========== 开始每日RAG离线评测 ==========");
        long startTime = System.currentTimeMillis();

        try {
            // 默认评测merchantId=1的商户，TopK=10
            RagEvaluator.RagEvaluationReport report = ragEvaluator.runEvaluationAndPersist(
                    1L, 10, "每日自动评测");

            long duration = System.currentTimeMillis() - startTime;
            log.info("========== 每日RAG离线评测完成 ==========");
            log.info("总用例: {}, 通过: {}, 通过率: {}%",
                    report.getTotalCases(), report.getPassedCases(),
                    String.format("%.2f", report.getPassRate()));
            log.info("平均Recall: {}, Precision: {}, F1: {}, MRR: {}, NDCG: {}",
                    String.format("%.4f", report.getAvgRecall()),
                    String.format("%.4f", report.getAvgPrecision()),
                    String.format("%.4f", report.getAvgF1()),
                    String.format("%.4f", report.getAvgMRR()),
                    String.format("%.4f", report.getAvgNDCG()));
            log.info("平均综合评分: {}, 总耗时: {}ms",
                    String.format("%.2f", report.getAvgOverallScore()), duration);

            // 通过率低于阈值时告警
            if (report.getPassRate() < 60.0) {
                log.warn("RAG评测通过率低于60%阈值: {}%，请检查检索质量和知识库内容",
                        String.format("%.2f", report.getPassRate()));
            }
        } catch (Exception e) {
            log.error("每日RAG离线评测失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 手动触发评测（供Controller调用）
     */
    public RagEvaluator.RagEvaluationReport runManualEvaluation(Long merchantId, int topK, String remark) {
        log.info("手动触发RAG离线评测: merchantId={}, topK={}", merchantId, topK);
        return ragEvaluator.runEvaluationAndPersist(merchantId, topK, remark);
    }
}
