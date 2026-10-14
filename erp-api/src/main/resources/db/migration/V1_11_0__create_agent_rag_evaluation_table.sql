-- Agent RAG评测集表（权威建表，含新旧两套字段）
-- 说明：本表早期版本(V1_11_0)只有 query/expected_chunks/category/tags，
-- 后 RagEvaluator 改用 merchant_id/question/relevant_chunks/domain。
-- 为让干净环境可一次重建，这里一次性创建全部列（旧列保留以兼容历史数据）。
-- 实际权威数据为 V1_16_1(24条) + V1_22_0(76条) 共100条。

CREATE TABLE IF NOT EXISTS `agent_rag_evaluation` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  -- 新schema（RagEvaluator.loadEvaluationCases 使用）
  `merchant_id` bigint DEFAULT NULL COMMENT '租户ID',
  `question` text COMMENT '评测问题',
  `relevant_chunks` text COMMENT '期望召回的chunk_id列表(JSON数组)',
  `domain` varchar(50) DEFAULT NULL COMMENT '领域：order/refund/inventory/logistics/purchase/goods/member',
  -- 旧schema（历史兼容）
  `query` text COMMENT '查询问题(旧列)',
  `expected_chunks` text COMMENT '期望召回chunk(旧列)',
  `category` varchar(50) COMMENT '类别(旧列)',
  `tags` varchar(255) COMMENT '标签(旧列)',
  -- 公共列
  `expected_answer` text COMMENT '期望答案',
  `difficulty` varchar(20) DEFAULT 'medium' COMMENT '难度(easy/medium/hard)',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_merchant_id` (`merchant_id`),
  KEY `idx_domain` (`domain`),
  KEY `idx_category` (`category`),
  KEY `idx_difficulty` (`difficulty`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent RAG评测集';
