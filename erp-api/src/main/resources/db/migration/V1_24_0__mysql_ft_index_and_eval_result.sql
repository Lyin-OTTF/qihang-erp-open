-- V1_24_0: PGVector MySQL降级 + 离线评测结果持久化（搁置项攻关-任务14/18）

-- 1. 为rag_document.content添加FULLTEXT索引（幂等：用 information_schema 守卫，已存在则跳过）
-- MySQL MATCH AGAINST 支持中文（ngram分词器，MySQL 5.7+内置）
DROP PROCEDURE IF EXISTS agent_add_ft_index;
DELIMITER //
CREATE PROCEDURE agent_add_ft_index()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rag_document'
                     AND INDEX_NAME = 'ft_content') THEN
        ALTER TABLE `rag_document` ADD FULLTEXT INDEX `ft_content` (`content`) WITH PARSER ngram;
    END IF;
END //
DELIMITER ;
CALL agent_add_ft_index();
DROP PROCEDURE IF EXISTS agent_add_ft_index;

-- 2. 离线评测结果表：存储每次RAG评测的聚合指标，支持历史趋势分析
CREATE TABLE IF NOT EXISTS `agent_rag_eval_result` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `merchant_id` BIGINT NOT NULL COMMENT '商户ID',
  `eval_time` DATETIME NOT NULL COMMENT '评测时间',
  `total_cases` INT NOT NULL DEFAULT 0 COMMENT '评测用例总数',
  `passed_cases` INT NOT NULL DEFAULT 0 COMMENT '通过用例数',
  `pass_rate` DOUBLE NOT NULL DEFAULT 0 COMMENT '通过率(%)',
  `avg_recall` DOUBLE NOT NULL DEFAULT 0 COMMENT '平均召回率',
  `avg_precision` DOUBLE NOT NULL DEFAULT 0 COMMENT '平均精确率',
  `avg_f1` DOUBLE NOT NULL DEFAULT 0 COMMENT '平均F1',
  `avg_mrr` DOUBLE NOT NULL DEFAULT 0 COMMENT '平均MRR',
  `avg_ndcg` DOUBLE NOT NULL DEFAULT 0 COMMENT '平均NDCG',
  `avg_answer_relevancy` DOUBLE NOT NULL DEFAULT 0 COMMENT '平均答案相关性',
  `avg_answer_correctness` DOUBLE NOT NULL DEFAULT 0 COMMENT '平均答案正确性',
  `avg_faithfulness` DOUBLE NOT NULL DEFAULT 0 COMMENT '平均忠实度',
  `avg_retrieval_time` BIGINT NOT NULL DEFAULT 0 COMMENT '平均检索耗时(ms)',
  `avg_generation_time` BIGINT NOT NULL DEFAULT 0 COMMENT '平均生成耗时(ms)',
  `avg_overall_score` DOUBLE NOT NULL DEFAULT 0 COMMENT '平均综合评分',
  `total_time` BIGINT NOT NULL DEFAULT 0 COMMENT '总耗时(ms)',
  `top_k` INT NOT NULL DEFAULT 10 COMMENT '评测TopK',
  `retrieval_mode` VARCHAR(32) NOT NULL DEFAULT 'HYBRID' COMMENT '检索模式: HYBRID/BM25/MYSQL_FT',
  `remark` VARCHAR(500) DEFAULT NULL COMMENT '备注',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_merchant_time` (`merchant_id`, `eval_time`),
  KEY `idx_eval_time` (`eval_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='RAG离线评测结果表';
