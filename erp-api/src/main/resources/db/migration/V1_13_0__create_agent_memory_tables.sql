-- Agent分层记忆表（权威建表）
-- 四层记忆：WORKING（工作记忆）、SESSION（会话记忆）、USER（用户记忆）、TEAM（团队记忆）
-- WORKING/SESSION 主要用 Redis，USER/TEAM 持久化到 MySQL（见 MemoryRepository 的 upsert）。
-- 说明：memory_id 为主键（UPSERT 的 ON DUPLICATE KEY 依赖），merchant_id 允许 NULL。
-- 早期版本附带的 snapshot/access_log/mask_log/stats 四张表无代码引用，已移除。

CREATE TABLE IF NOT EXISTS `agent_memory` (
  `memory_id` varchar(128) NOT NULL COMMENT '记忆唯一ID（layer:scopeType:scopeId:key 组合）',
  `layer` varchar(20) DEFAULT NULL COMMENT '记忆层(WORKING/SESSION/USER/TEAM)',
  `scope_type` varchar(50) DEFAULT NULL COMMENT '作用域类型(conversation/user/team/global)',
  `scope_id` varchar(100) DEFAULT NULL COMMENT '作用域ID',
  `key` varchar(255) NOT NULL COMMENT '记忆键',
  `value` text COMMENT '记忆值(JSON序列化)',
  `value_type` varchar(50) DEFAULT NULL COMMENT '值类型(string/number/boolean/json)',
  `ttl_seconds` int DEFAULT NULL COMMENT 'TTL秒数，NULL=永不过期',
  `expires_at` datetime DEFAULT NULL COMMENT '过期时间',
  `priority` int DEFAULT 0 COMMENT '优先级（用于记忆淘汰）',
  `access_count` int DEFAULT 0 COMMENT '访问次数',
  `last_accessed_at` datetime DEFAULT NULL COMMENT '最后访问时间',
  `merchant_id` bigint DEFAULT NULL COMMENT '租户ID',
  `user_id` bigint DEFAULT NULL COMMENT '用户ID',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`memory_id`),
  KEY `idx_layer_scope` (`layer`, `scope_type`, `scope_id`),
  KEY `idx_merchant_user` (`merchant_id`, `user_id`),
  KEY `idx_expires_at` (`expires_at`),
  KEY `idx_key` (`key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent分层记忆';
