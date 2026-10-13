-- V1_16_0: RAG评测集表（阶段2-任务11）
-- 按 RagEvaluator.loadEvaluationCases 实际查询的字段建表

CREATE TABLE IF NOT EXISTS `agent_rag_evaluation` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `merchant_id` BIGINT DEFAULT NULL COMMENT '租户ID',
    `question` TEXT NOT NULL COMMENT '评测问题',
    `expected_answer` TEXT COMMENT '期望答案',
    `relevant_chunks` TEXT COMMENT '期望召回的chunk_id列表（JSON数组）',
    `domain` VARCHAR(50) DEFAULT NULL COMMENT '领域：order/refund/inventory/logistics/supplier/goods',
    `difficulty` VARCHAR(20) DEFAULT 'medium' COMMENT '难度：easy/medium/hard',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    INDEX `idx_merchant_id` (`merchant_id`),
    INDEX `idx_domain` (`domain`),
    INDEX `idx_difficulty` (`difficulty`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RAG离线评测集';
