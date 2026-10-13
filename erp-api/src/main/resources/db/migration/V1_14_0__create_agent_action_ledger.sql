-- V1_14_0: AI Agent 工具调用审计日志表（Task8 ActionLedger）
-- 记录所有 AI Tool 的调用历史，支持按 traceId/merchantId/userId 追溯

CREATE TABLE IF NOT EXISTS `agent_action_ledger` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `trace_id` VARCHAR(64) NOT NULL COMMENT '链路追踪ID，关联一次对话的所有Tool调用',
    `tool_name` VARCHAR(128) NOT NULL COMMENT 'Tool名称（格式：ClassName.methodName）',
    `tool_scope` VARCHAR(64) DEFAULT NULL COMMENT 'Tool作用域/分类（order/goods/inventory等）',
    `autonomy_level` VARCHAR(32) NOT NULL COMMENT '自治等级（OBSERVE/SUGGEST/DRAFT/EXECUTE/ESCALATE）',

    `merchant_id` BIGINT DEFAULT NULL COMMENT '租户ID/商户ID',
    `user_id` BIGINT DEFAULT NULL COMMENT '操作用户ID',
    `session_id` VARCHAR(64) DEFAULT NULL COMMENT '会话ID',

    `input_params` TEXT COMMENT '输入参数（JSON格式）',
    `output_result` TEXT COMMENT '输出结果（JSON格式）',

    `success` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否成功：1=成功，0=失败',
    `error_message` VARCHAR(512) DEFAULT NULL COMMENT '错误信息（失败时记录）',
    `error_type` VARCHAR(64) DEFAULT NULL COMMENT '错误类型（用于统计分析）',

    `start_time` DATETIME NOT NULL COMMENT '开始时间',
    `end_time` DATETIME DEFAULT NULL COMMENT '结束时间',
    `duration_ms` INT DEFAULT NULL COMMENT '执行耗时（毫秒）',

    `is_write_operation` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否为写操作：1=是，0=否',
    `requires_approval` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否需要审批：1=是，0=否',
    `approved` TINYINT(1) DEFAULT NULL COMMENT '是否已审批：1=已批准，0=已拒绝，NULL=无需审批',

    `ip_address` VARCHAR(64) DEFAULT NULL COMMENT '请求IP地址',
    `user_agent` VARCHAR(512) DEFAULT NULL COMMENT '用户代理（浏览器/客户端信息）',

    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',

    PRIMARY KEY (`id`),
    INDEX `idx_trace_id` (`trace_id`),
    INDEX `idx_tool_name` (`tool_name`),
    INDEX `idx_merchant_id` (`merchant_id`),
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_create_time` (`create_time`),
    INDEX `idx_success` (`success`),
    INDEX `idx_tool_scope` (`tool_scope`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI Agent工具调用审计日志';
