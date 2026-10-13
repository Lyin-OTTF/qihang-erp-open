-- V1_18_0: RAG知识库文档表（阶段3-任务15）
-- 存储SOP文档chunk，启动时由 KnowledgeLoader 加载到 BM25 内存索引

CREATE TABLE IF NOT EXISTS `rag_document` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `chunk_id` VARCHAR(128) NOT NULL COMMENT 'chunk唯一标识（BM25索引键）',
    `doc_id` VARCHAR(128) DEFAULT NULL COMMENT '源文档ID',
    `title` VARCHAR(500) DEFAULT NULL COMMENT '文档标题',
    `content` TEXT NOT NULL COMMENT 'chunk正文内容',
    `domain` VARCHAR(50) DEFAULT NULL COMMENT '领域：order/refund/inventory/logistics/supplier/goods',
    `merchant_id` BIGINT DEFAULT NULL COMMENT '租户ID，NULL=全局共享',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_chunk_id` (`chunk_id`),
    INDEX `idx_domain` (`domain`),
    INDEX `idx_merchant` (`merchant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RAG知识库文档chunk表';
