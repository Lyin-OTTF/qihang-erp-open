-- Agent分层记忆表结构
-- 四层记忆：Working（工作记忆）、Session（会话记忆）、User（用户记忆）、Team（团队记忆）

-- ==================== 1. 分层记忆表 ====================
CREATE TABLE IF NOT EXISTS `agent_memory` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `memory_id` varchar(100) NOT NULL COMMENT '记忆唯一标识',
  `layer` varchar(20) NOT NULL COMMENT '记忆层(WORKING/SESSION/USER/TEAM)',
  `scope_type` varchar(50) NOT NULL COMMENT '作用域类型(CONVERSATION/SESSION/USER/MERCHANT)',
  `scope_id` varchar(100) NOT NULL COMMENT '作用域ID',
  `key` varchar(255) NOT NULL COMMENT '记忆键',
  `value` text NOT NULL COMMENT '记忆值(JSON格式)',
  `value_type` varchar(50) DEFAULT 'STRING' COMMENT '值类型(STRING/JSON/LIST/MAP)',
  `ttl_seconds` int COMMENT 'TTL秒数（0=永久）',
  `expires_at` datetime COMMENT '过期时间',
  `priority` int DEFAULT 0 COMMENT '优先级（用于淘汰策略）',
  `access_count` int DEFAULT 0 COMMENT '访问次数',
  `last_accessed_at` datetime COMMENT '最后访问时间',
  `merchant_id` bigint NOT NULL COMMENT '商户ID（租户隔离）',
  `user_id` bigint COMMENT '用户ID',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_memory_id` (`memory_id`),
  KEY `idx_layer_scope` (`layer`, `scope_type`, `scope_id`),
  KEY `idx_expires` (`expires_at`),
  KEY `idx_merchant_user` (`merchant_id`, `user_id`),
  KEY `idx_key` (`key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent分层记忆';

-- ==================== 2. 记忆快照表（备份与恢复）====================
CREATE TABLE IF NOT EXISTS `agent_memory_snapshot` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `snapshot_id` varchar(100) NOT NULL COMMENT '快照唯一标识',
  `scope_type` varchar(50) NOT NULL COMMENT '作用域类型',
  `scope_id` varchar(100) NOT NULL COMMENT '作用域ID',
  `snapshot_data` longtext NOT NULL COMMENT '快照数据(JSON格式)',
  `snapshot_time` datetime NOT NULL COMMENT '快照时间',
  `memory_count` int COMMENT '记忆条数',
  `total_size_bytes` bigint COMMENT '总大小（字节）',
  `merchant_id` bigint NOT NULL COMMENT '商户ID',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_snapshot_id` (`snapshot_id`),
  KEY `idx_scope` (`scope_type`, `scope_id`),
  KEY `idx_merchant` (`merchant_id`),
  KEY `idx_snapshot_time` (`snapshot_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent记忆快照';

-- ==================== 3. 敏感信息脱敏日志 ====================
CREATE TABLE IF NOT EXISTS `agent_sensitive_mask_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `memory_id` varchar(100) NOT NULL COMMENT '记忆ID',
  `field_name` varchar(100) NOT NULL COMMENT '字段名',
  `original_type` varchar(50) NOT NULL COMMENT '原始类型(PHONE/ID_CARD/BANK_CARD/EMAIL)',
  `masked_value` varchar(255) NOT NULL COMMENT '脱敏后的值',
  `mask_method` varchar(50) NOT NULL COMMENT '脱敏方法(PARTIAL_MASK/HASH/ENCRYPT)',
  `merchant_id` bigint NOT NULL COMMENT '商户ID',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_memory_id` (`memory_id`),
  KEY `idx_merchant` (`merchant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent敏感信息脱敏日志';

-- ==================== 4. 记忆访问日志 ====================
CREATE TABLE IF NOT EXISTS `agent_memory_access_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `memory_id` varchar(100) NOT NULL COMMENT '记忆ID',
  `access_type` varchar(20) NOT NULL COMMENT '访问类型(READ/WRITE/DELETE)',
  `layer` varchar(20) NOT NULL COMMENT '记忆层',
  `key` varchar(255) NOT NULL COMMENT '记忆键',
  `trace_id` varchar(100) COMMENT '追踪ID',
  `merchant_id` bigint NOT NULL COMMENT '商户ID',
  `user_id` bigint COMMENT '用户ID',
  `accessed_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '访问时间',
  PRIMARY KEY (`id`),
  KEY `idx_memory_id` (`memory_id`),
  KEY `idx_trace_id` (`trace_id`),
  KEY `idx_merchant_user` (`merchant_id`, `user_id`),
  KEY `idx_accessed_at` (`accessed_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent记忆访问日志';

-- ==================== 5. 记忆统计表 ====================
CREATE TABLE IF NOT EXISTS `agent_memory_stats` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `stat_date` date NOT NULL COMMENT '统计日期',
  `layer` varchar(20) NOT NULL COMMENT '记忆层',
  `total_count` int DEFAULT 0 COMMENT '记忆总数',
  `expired_count` int DEFAULT 0 COMMENT '过期数量',
  `avg_ttl_seconds` int COMMENT '平均TTL（秒）',
  `total_size_bytes` bigint COMMENT '总大小（字节）',
  `merchant_id` bigint COMMENT '商户ID（NULL表示全局统计）',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_stat` (`stat_date`, `layer`, `merchant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent记忆统计';
