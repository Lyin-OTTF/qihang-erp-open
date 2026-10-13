-- AI Agent 操作审批表
-- 当 Tool 自治等级为 ESCALATE 时，ToolGateway 创建审批记录，等待人工确认
-- 回溯开发-搁置项攻关：任务9 ApprovalManager确认流

CREATE TABLE IF NOT EXISTS `agent_approval` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `trace_id` varchar(64) NOT NULL COMMENT '对话追踪ID',
  `tool_name` varchar(100) NOT NULL COMMENT '工具名称（类名.方法名）',
  `tool_description` varchar(500) COMMENT '工具操作描述',
  `merchant_id` bigint COMMENT '商户ID',
  `user_id` bigint COMMENT '发起用户ID',
  `input_params` text COMMENT '调用参数JSON',
  `status` varchar(20) NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING待审批/APPROVED已批准/REJECTED已拒绝/EXECUTED已执行/EXPIRED已过期',
  `approver_id` bigint COMMENT '审批人ID',
  `approve_comment` varchar(500) COMMENT '审批意见',
  `approved_at` datetime COMMENT '审批时间',
  `executed_at` datetime COMMENT '执行时间',
  `execution_result` text COMMENT '执行结果摘要',
  `error_message` text COMMENT '执行错误信息',
  `expires_at` datetime COMMENT '过期时间',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_trace_id` (`trace_id`),
  KEY `idx_merchant_status` (`merchant_id`, `status`),
  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI Agent操作审批表';
