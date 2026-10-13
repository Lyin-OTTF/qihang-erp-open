-- Agent 工具策略配置表
-- 存储每个 Tool 的自治等级、读写属性、审批要求等策略配置
-- 回溯开发-搁置项攻关：任务6 Policy注册表补全

CREATE TABLE IF NOT EXISTS `agent_tool_policy` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tool_name` varchar(100) NOT NULL COMMENT '工具名称（类名.方法名）',
  `autonomy_level` varchar(20) NOT NULL DEFAULT 'OBSERVE' COMMENT '自治等级：OBSERVE/SUGGEST/DRAFT/EXECUTE/ESCALATE',
  `is_write` tinyint(1) DEFAULT 0 COMMENT '是否写操作',
  `scope` varchar(50) COMMENT '影响范围：order/inventory/refund等',
  `is_idempotent` tinyint(1) DEFAULT 0 COMMENT '是否幂等',
  `requires_approval` tinyint(1) DEFAULT 0 COMMENT '是否需要审批',
  `requires_tenant_isolation` tinyint(1) DEFAULT 1 COMMENT '是否需要租户隔离',
  `description` varchar(500) COMMENT '策略说明',
  `enabled` tinyint(1) DEFAULT 1 COMMENT '是否启用',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tool_name` (`tool_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent工具策略配置表';
