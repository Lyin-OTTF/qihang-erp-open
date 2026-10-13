-- 阶段4：图状态快照表
-- 用于DAG工作流执行中断后的快照恢复
CREATE TABLE IF NOT EXISTS `agent_graph_checkpoint` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `workflow_id` varchar(100) NOT NULL COMMENT '工作流ID',
  `execution_id` varchar(100) NOT NULL COMMENT '执行ID',
  `checkpoint_version` int NOT NULL COMMENT '快照版本',
  `current_node_id` varchar(100) COMMENT '当前节点ID',
  `node_outputs` text COMMENT '节点输出(JSON)',
  `global_context` text COMMENT '全局上下文(JSON)',
  `active_branch` varchar(100) COMMENT '激活分支',
  `loop_count` int DEFAULT 0 COMMENT '循环次数',
  `retry_count` int DEFAULT 0 COMMENT '重试次数',
  `status` varchar(20) DEFAULT 'RUNNING' COMMENT '执行状态: RUNNING/COMPLETED/FAILED',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_execution_version` (`execution_id`, `checkpoint_version`),
  KEY `idx_workflow` (`workflow_id`),
  KEY `idx_execution` (`execution_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent工作流快照';
