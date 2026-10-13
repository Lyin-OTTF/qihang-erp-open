-- V1_17_0: 分层记忆持久化表（阶段3-任务19/21）
-- 按 MemoryRepository 的 UPSERT/FIND SQL 实际使用的字段建表
-- WORKING/SESSION 层主要用 Redis，USER/TEAM 层持久化到 MySQL

CREATE TABLE IF NOT EXISTS `agent_memory` (
    `memory_id` VARCHAR(128) NOT NULL COMMENT '记忆唯一ID（layer:scopeType:scopeId:key 组合）',
    `layer` VARCHAR(20) DEFAULT NULL COMMENT '记忆层：WORKING/SESSION/USER/TEAM',
    `scope_type` VARCHAR(50) DEFAULT NULL COMMENT '作用域类型：conversation/user/team/global',
    `scope_id` VARCHAR(100) DEFAULT NULL COMMENT '作用域ID',
    `key` VARCHAR(255) NOT NULL COMMENT '记忆键',
    `value` TEXT COMMENT '记忆值（JSON序列化）',
    `value_type` VARCHAR(50) DEFAULT NULL COMMENT '值类型：string/number/boolean/json',
    `ttl_seconds` INT DEFAULT NULL COMMENT 'TTL秒数，NULL=永不过期',
    `expires_at` DATETIME DEFAULT NULL COMMENT '过期时间',
    `priority` INT DEFAULT 0 COMMENT '优先级（用于记忆淘汰）',
    `access_count` INT DEFAULT 0 COMMENT '访问次数',
    `merchant_id` BIGINT DEFAULT NULL COMMENT '租户ID',
    `user_id` BIGINT DEFAULT NULL COMMENT '用户ID',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`memory_id`),
    INDEX `idx_layer_scope` (`layer`, `scope_type`, `scope_id`),
    INDEX `idx_merchant_user` (`merchant_id`, `user_id`),
    INDEX `idx_expires_at` (`expires_at`),
    INDEX `idx_key` (`key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI Agent分层记忆持久化表';
