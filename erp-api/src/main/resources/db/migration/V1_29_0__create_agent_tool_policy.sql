-- V1_29_0: 创建 agent_tool_policy 表（工具策略动态配置）
-- V1_21_0 为占位空迁移，本表在此正式创建
-- 支持按工具名+商户ID配置自治等级，DB配置优先于@ToolPolicy注解，运行时可动态修改

CREATE TABLE IF NOT EXISTS `agent_tool_policy` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `tool_name` VARCHAR(128) NOT NULL COMMENT '工具名，如 OrderActionTools.cancelOrder',
  `merchant_id` BIGINT DEFAULT NULL COMMENT '商户ID，NULL为全局默认策略',
  `autonomy_level` VARCHAR(32) NOT NULL DEFAULT 'OBSERVE' COMMENT '自治等级: OBSERVE/SUGGEST/DRAFT/EXECUTE/ESCALATE',
  `category` VARCHAR(64) DEFAULT NULL COMMENT '工具分类',
  `description` VARCHAR(512) DEFAULT NULL COMMENT '工具描述',
  `requires_tenant_isolation` TINYINT NOT NULL DEFAULT 1 COMMENT '是否需要租户隔离',
  `allow_concurrent` TINYINT NOT NULL DEFAULT 1 COMMENT '是否允许并发调用',
  `requires_audit` TINYINT NOT NULL DEFAULT 1 COMMENT '是否需要审计日志',
  `max_batch_size` INT NOT NULL DEFAULT 0 COMMENT '批量操作上限，0=不限制',
  `timeout_seconds` INT NOT NULL DEFAULT 0 COMMENT '超时秒数，0=不限制',
  `enabled` TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tool_merchant` (`tool_name`, `merchant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent工具策略动态配置表';
