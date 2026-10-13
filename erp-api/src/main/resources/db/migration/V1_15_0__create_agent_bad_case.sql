-- V1_15_0: AI Bad Case 反馈收集表（阶段2-任务13）
-- 用户可标记AI的错误/无用/不安全回答，用于后续评测与模型优化

CREATE TABLE IF NOT EXISTS `agent_bad_case` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `trace_id` VARCHAR(64) DEFAULT NULL COMMENT '关联对话traceId，可回溯完整调用链',
    `user_id` BIGINT DEFAULT NULL COMMENT '反馈用户ID',
    `merchant_id` BIGINT DEFAULT NULL COMMENT '租户ID/商户ID',
    `session_id` VARCHAR(64) DEFAULT NULL COMMENT '会话ID',
    `question` TEXT COMMENT '用户原始问题',
    `ai_answer` TEXT COMMENT 'AI给出的回答',
    `feedback_type` VARCHAR(32) NOT NULL COMMENT '反馈类型：WRONG_ANSWER(答案错误)/UNHELPFUL(无用)/UNSAFE(不安全)/OFF_TOPIC(答非所问)/OTHER(其他)',
    `feedback_comment` VARCHAR(500) DEFAULT NULL COMMENT '用户补充说明',
    `status` VARCHAR(20) NOT NULL DEFAULT 'OPEN' COMMENT '处理状态：OPEN(待处理)/PROCESSING(处理中)/RESOLVED(已解决)/IGNORED(已忽略)',
    `resolution` VARCHAR(500) DEFAULT NULL COMMENT '处理结果说明',
    `resolved_by` BIGINT DEFAULT NULL COMMENT '处理人ID',
    `resolved_at` DATETIME DEFAULT NULL COMMENT '处理时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    INDEX `idx_trace_id` (`trace_id`),
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_merchant_id` (`merchant_id`),
    INDEX `idx_status` (`status`),
    INDEX `idx_feedback_type` (`feedback_type`),
    INDEX `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI Bad Case反馈收集表';
