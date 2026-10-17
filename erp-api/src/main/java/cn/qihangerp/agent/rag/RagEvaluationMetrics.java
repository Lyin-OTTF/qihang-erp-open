package cn.qihangerp.agent.rag;

import lombok.Data;

/**
 * RAG评测指标
 *
 * 包含检索和生成两个阶段的评测指标
 *
 * @author AI Code Generator
 * @since 2026-10-12
 */
@Data
public class RagEvaluationMetrics {

    /**
     * 检索阶段指标
     */
    private RetrievalMetrics retrieval;

    /**
     * 生成阶段指标
     */
    private GenerationMetrics generation;

    /**
     * 整体指标
     */
    private OverallMetrics overall;

    @Data
    public static class RetrievalMetrics {
        /**
         * 是否存在可用的相关性标注。未标注用例不参与检索均值。
         */
        private boolean annotated;

        /**
         * 召回率（Recall）
         * 定义：检索到的相关文档数 / 所有相关文档数
         */
        private double recall;

        /**
         * 精确率（Precision）
         * 定义：检索到的相关文档数 / 检索到的总文档数
         */
        private double precision;

        /**
         * F1分数
         * 定义：2 * (precision * recall) / (precision + recall)
         */
        private double f1Score;

        /**
         * MRR（Mean Reciprocal Rank）
         * 定义：1 / 第一个相关文档的排名
         */
        private double mrr;

        /**
         * NDCG@K（Normalized Discounted Cumulative Gain）
         * 定义：考虑排序质量的评测指标
         */
        private double ndcg;

        /**
         * Hit Rate（命中率）
         * 定义：检索结果中是否包含至少一个相关文档
         */
        private boolean hit;

        /**
         * 检索耗时（毫秒）
         */
        private long retrievalTime;
    }

    @Data
    public static class GenerationMetrics {
        /**
         * 答案相关性（Answer Relevancy）
         * 定义：生成的答案与问题的相关程度（0-1）
         */
        private double answerRelevancy;

        /**
         * 答案正确性（Answer Correctness）
         * 定义：生成的答案与标准答案的匹配度（0-1）
         */
        private double answerCorrectness;

        /**
         * 忠实度（Faithfulness）
         * 定义：生成的答案是否忠于检索到的文档（0-1）
         */
        private double faithfulness;

        /**
         * 上下文利用率（Context Utilization）
         * 定义：生成的答案使用了多少检索到的上下文
         */
        private double contextUtilization;

        /**
         * BLEU分数（与标准答案的相似度）
         */
        private double bleuScore;

        /**
         * ROUGE-L分数（与标准答案的重叠度）
         */
        private double rougeScore;

        /**
         * 生成耗时（毫秒）
         */
        private long generationTime;
    }

    @Data
    public static class OverallMetrics {
        /**
         * 端到端延迟（毫秒）
         */
        private long totalTime;

        /**
         * 是否通过（根据阈值判断）
         */
        private boolean passed;

        /**
         * 综合评分（0-100）
         */
        private double overallScore;
    }

    /**
     * 计算综合评分
     *
     * 权重分配：
     * - 检索质量：40%
     * - 答案质量：40%
     * - 性能：20%
     */
    public void calculateOverallScore() {
        if (overall == null) {
            overall = new OverallMetrics();
        }

        double performanceScore = 1.0;
        if (overall.getTotalTime() > 5000) {
            performanceScore = 0.5; // 超过5秒性能较差
        } else if (overall.getTotalTime() > 3000) {
            performanceScore = 0.7; // 3-5秒性能一般
        } else if (overall.getTotalTime() > 1000) {
            performanceScore = 0.9; // 1-3秒性能良好
        }

        double weightedScore = performanceScore * 0.2;
        double totalWeight = 0.2;
        if (retrieval != null && retrieval.isAnnotated()) {
            double retrievalScore = (retrieval.getPrecision() + retrieval.getRecall()
                    + retrieval.getF1Score()) / 3.0;
            weightedScore += retrievalScore * 0.4;
            totalWeight += 0.4;
        }
        if (generation != null) {
            double generationScore = (generation.getAnswerRelevancy()
                    + generation.getAnswerCorrectness()
                    + generation.getFaithfulness()) / 3.0;
            weightedScore += generationScore * 0.4;
            totalWeight += 0.4;
        }

        overall.setOverallScore(totalWeight == 0.0 ? 0.0 : weightedScore / totalWeight * 100);

        // 判断是否通过（综合评分>=60分）
        overall.setPassed(overall.getOverallScore() >= 60.0);
    }
}
