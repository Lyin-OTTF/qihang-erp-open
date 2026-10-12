-- Agent RAG知识库表结构
-- 包含：文档表、chunk表、向量存储表

-- ==================== 1. 文档表 ====================
CREATE TABLE IF NOT EXISTS `agent_document` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `doc_id` varchar(100) NOT NULL COMMENT '文档唯一标识',
  `doc_name` varchar(255) NOT NULL COMMENT '文档名称',
  `doc_type` varchar(50) NOT NULL COMMENT '文档类型(SOP/FAQ/MANUAL/POLICY)',
  `category` varchar(100) COMMENT '文档分类(售后SOP/退款原因/物流异常/采购流程等)',
  `source_path` varchar(500) COMMENT '源文件路径',
  `source_url` varchar(500) COMMENT '源文件URL',
  `content` longtext COMMENT '文档原始内容',
  `metadata` text COMMENT '元数据(JSON格式)',
  `status` varchar(20) DEFAULT 'ACTIVE' COMMENT '状态(ACTIVE/ARCHIVED/DELETED)',
  `version` int DEFAULT 1 COMMENT '文档版本号',
  `merchant_id` bigint COMMENT '商户ID（多租户隔离）',
  `created_by` bigint COMMENT '创建人ID',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_doc_id` (`doc_id`),
  KEY `idx_category` (`category`),
  KEY `idx_status` (`status`),
  KEY `idx_merchant` (`merchant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent文档表';

-- ==================== 2. Chunk表（文档切片）====================
CREATE TABLE IF NOT EXISTS `agent_knowledge_chunk` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `chunk_id` varchar(100) NOT NULL COMMENT 'Chunk唯一标识',
  `doc_id` varchar(100) NOT NULL COMMENT '所属文档ID',
  `chunk_index` int NOT NULL COMMENT 'Chunk序号（在文档中的位置）',
  `content` text NOT NULL COMMENT 'Chunk内容',
  `content_hash` varchar(64) COMMENT '内容哈希（用于去重）',
  `chunk_size` int COMMENT 'Chunk字符数',
  `metadata` text COMMENT '元数据(JSON格式，包含位置、标题、标签等)',
  `embedding_status` varchar(20) DEFAULT 'PENDING' COMMENT 'Embedding状态(PENDING/SUCCESS/FAILED)',
  `embedding_error` varchar(500) COMMENT 'Embedding失败原因',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_chunk_id` (`chunk_id`),
  KEY `idx_doc_id` (`doc_id`),
  KEY `idx_content_hash` (`content_hash`),
  KEY `idx_embedding_status` (`embedding_status`),
  CONSTRAINT `fk_chunk_doc` FOREIGN KEY (`doc_id`) REFERENCES `agent_document` (`doc_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent知识chunk表';

-- ==================== 3. 检索日志表 ====================
CREATE TABLE IF NOT EXISTS `agent_rag_retrieval_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `trace_id` varchar(100) NOT NULL COMMENT '追踪ID（关联对话）',
  `query` text NOT NULL COMMENT '查询问题',
  `query_embedding_time_ms` int COMMENT 'Query向量化耗时（毫秒）',
  `retrieval_method` varchar(50) COMMENT '检索方法(VECTOR/BM25/HYBRID)',
  `retrieved_chunk_ids` text COMMENT '召回的chunk_id列表(JSON数组)',
  `retrieval_time_ms` int COMMENT '检索耗时（毫秒）',
  `rerank_enabled` tinyint(1) DEFAULT 0 COMMENT '是否启用重排',
  `rerank_time_ms` int COMMENT '重排耗时（毫秒）',
  `top_k` int COMMENT '召回数量',
  `final_chunk_ids` text COMMENT '最终使用的chunk_id列表(JSON数组)',
  `similarity_scores` text COMMENT '相似度分数列表(JSON数组)',
  `merchant_id` bigint COMMENT '商户ID',
  `user_id` bigint COMMENT '用户ID',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_trace_id` (`trace_id`),
  KEY `idx_merchant_user` (`merchant_id`, `user_id`),
  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent RAG检索日志';

-- ==================== 4. BM25倒排索引表（全文检索）====================
CREATE TABLE IF NOT EXISTS `agent_bm25_index` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `term` varchar(100) NOT NULL COMMENT '词条',
  `chunk_id` varchar(100) NOT NULL COMMENT 'Chunk ID',
  `term_frequency` int NOT NULL COMMENT '词频（TF）',
  `doc_frequency` int NOT NULL COMMENT '文档频率（DF）',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_term` (`term`),
  KEY `idx_chunk_id` (`chunk_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent BM25倒排索引';

-- ==================== 5. 知识库统计表 ====================
CREATE TABLE IF NOT EXISTS `agent_knowledge_stats` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `stat_date` date NOT NULL COMMENT '统计日期',
  `total_documents` int DEFAULT 0 COMMENT '文档总数',
  `total_chunks` int DEFAULT 0 COMMENT 'Chunk总数',
  `total_queries` int DEFAULT 0 COMMENT '查询总数',
  `avg_retrieval_time_ms` decimal(10,2) COMMENT '平均检索耗时',
  `avg_recall_at_10` decimal(5,4) COMMENT '平均Recall@10',
  `avg_mrr` decimal(5,4) COMMENT '平均MRR',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_stat_date` (`stat_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent知识库统计';
