-- Agent RAG评测集表
-- 用于存储RAG评测数据集，包含查询问题、期望召回的chunks、期望答案、分类等

CREATE TABLE IF NOT EXISTS `agent_rag_evaluation` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `query` text NOT NULL COMMENT '查询问题',
  `expected_chunks` text COMMENT '期望召回的chunk_id列表(JSON数组格式，如["chunk_001","chunk_002"])',
  `expected_answer` text COMMENT '期望答案',
  `category` varchar(50) COMMENT '类别(订单查询/订单状态/退款流程/库存管理/商品查询/采购管理等)',
  `difficulty` varchar(20) DEFAULT 'medium' COMMENT '难度(easy/medium/hard)',
  `tags` varchar(255) COMMENT '标签(逗号分隔，如"多条件,时间范围,状态筛选")',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_category` (`category`),
  KEY `idx_difficulty` (`difficulty`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent RAG评测集';
