-- V1_30_0: 真实RAG评测收口
-- 1. 修正评测集相关性标注，使其引用真实rag_document.chunk_id
-- 2. 持久化LLM模式、BLEU/ROUGE和逐用例答案明细

DROP PROCEDURE IF EXISTS agent_add_rag_eval_column;
DELIMITER //
CREATE PROCEDURE agent_add_rag_eval_column(
    IN p_table VARCHAR(64),
    IN p_column VARCHAR(64),
    IN p_definition VARCHAR(500)
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = p_table
          AND COLUMN_NAME = p_column
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN `', p_column, '` ', p_definition);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END //
DELIMITER ;

CALL agent_add_rag_eval_column('agent_rag_eval_result', 'retrieval_cases', 'INT NOT NULL DEFAULT 0');
CALL agent_add_rag_eval_column('agent_rag_eval_result', 'llm_used', 'TINYINT(1) NOT NULL DEFAULT 0');
CALL agent_add_rag_eval_column('agent_rag_eval_result', 'llm_rated_cases', 'INT NOT NULL DEFAULT 0');
CALL agent_add_rag_eval_column('agent_rag_eval_result', 'avg_bleu', 'DOUBLE NOT NULL DEFAULT 0');
CALL agent_add_rag_eval_column('agent_rag_eval_result', 'avg_rouge_l', 'DOUBLE NOT NULL DEFAULT 0');
CALL agent_add_rag_eval_column('agent_rag_eval_result', 'model_name', 'VARCHAR(100) NOT NULL DEFAULT ''heuristic''');
CALL agent_add_rag_eval_column('agent_rag_eval_result', 'evaluation_mode', 'VARCHAR(32) NOT NULL DEFAULT ''HEURISTIC''');
DROP PROCEDURE IF EXISTS agent_add_rag_eval_column;

CREATE TABLE IF NOT EXISTS `agent_rag_eval_case_result` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `run_id` BIGINT NOT NULL COMMENT '聚合评测结果ID',
  `merchant_id` BIGINT NOT NULL COMMENT '商户ID',
  `question` VARCHAR(500) NOT NULL COMMENT '评测问题',
  `expected_answer` TEXT COMMENT '标准答案',
  `generated_answer` TEXT COMMENT '生成答案',
  `retrieved_chunks` TEXT COMMENT '检索命中的chunk ID JSON',
  `relevant_chunks` TEXT COMMENT '相关性标注chunk ID JSON',
  `recall` DOUBLE NOT NULL DEFAULT 0,
  `precision_score` DOUBLE NOT NULL DEFAULT 0,
  `f1_score` DOUBLE NOT NULL DEFAULT 0,
  `mrr` DOUBLE NOT NULL DEFAULT 0,
  `ndcg` DOUBLE NOT NULL DEFAULT 0,
  `answer_relevancy` DOUBLE NOT NULL DEFAULT 0,
  `answer_correctness` DOUBLE NOT NULL DEFAULT 0,
  `faithfulness` DOUBLE NOT NULL DEFAULT 0,
  `bleu_score` DOUBLE NOT NULL DEFAULT 0,
  `rouge_l` DOUBLE NOT NULL DEFAULT 0,
  `retrieval_time` BIGINT NOT NULL DEFAULT 0,
  `generation_time` BIGINT NOT NULL DEFAULT 0,
  `llm_used` TINYINT(1) NOT NULL DEFAULT 0,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_run_id` (`run_id`),
  KEY `idx_merchant_time` (`merchant_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='RAG评测逐用例结果';

DROP TEMPORARY TABLE IF EXISTS rag_eval_relevance_seed;
CREATE TEMPORARY TABLE rag_eval_relevance_seed (
  question VARCHAR(500) NOT NULL,
  relevant_chunks VARCHAR(1000) NOT NULL,
  PRIMARY KEY (question)
) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO rag_eval_relevance_seed (question, relevant_chunks) VALUES
('客户要求取消已付款的订单怎么处理？', '["sop_order_001"]'),
('订单状态有哪些？分别代表什么含义？', '["sop_order_002"]'),
('订单发货后客户要求改地址怎么办？', '["sop_order_003"]'),
('如何批量导出近30天的订单数据？', '["sop_order_012"]'),
('客户申请退款但商家已发货，应该怎么处理？', '["sop_refund_001"]'),
('退款状态有哪些？', '["sop_refund_001","sop_refund_004"]'),
('仅退款和退货退款有什么区别？', '["sop_refund_002"]'),
('客户收到商品后说少发了一件，怎么处理？', '["sop_refund_003"]'),
('库存不足时系统会怎么提示？', '["sop_inventory_001"]'),
('如何查看某个商品的库存流水？', '["sop_inventory_002"]'),
('盘点时发现账实不符怎么调整？', '["sop_inventory_003","sop_inventory_006"]'),
('安全库存怎么设置？有什么作用？', '["sop_inventory_008"]'),
('怎么打印快递面单？', '["sop_logistics_001","sop_logistics_005"]'),
('电子面单余额不足怎么办？', '["sop_logistics_005"]'),
('发货后物流信息一直不更新怎么处理？', '["sop_logistics_002"]'),
('支持哪些物流公司？', '["sop_logistics_003"]'),
('怎么创建采购订单？', '["sop_supplier_001"]'),
('采购入库时数量和订单不一致怎么办？', '["sop_supplier_002"]'),
('供应商管理需要维护哪些信息？', '["sop_supplier_003"]'),
('采购退货流程是怎样的？', '["sop_purchase_006"]'),
('怎么新增商品？', '["sop_goods_001"]'),
('SKU和SPU有什么区别？', '["sop_goods_002"]'),
('商品价格怎么批量修改？', '["sop_goods_003"]'),
('商品下架后还能恢复吗？', '["sop_goods_007"]'),
('客户下单后未付款，订单多久会自动关闭？', '["sop_order_004"]'),
('拼团订单怎么取消？', '["sop_order_011"]'),
('订单备注怎么修改？', '["sop_order_015"]'),
('如何合并多个订单一起发货？', '[]'),
('订单拆分发货怎么操作？', '[]'),
('客户要求修改订单商品数量怎么办？', '[]'),
('订单锁定状态是什么意思？', '["sop_order_017"]'),
('如何查询订单的操作日志？', '[]'),
('客户说没收到货但物流显示签收了怎么办？', '["sop_order_018","sop_logistics_010"]'),
('预售订单的发货规则是什么？', '["sop_order_016"]'),
('订单金额计算错误怎么处理？', '["sop_order_005"]'),
('如何设置订单自动审核？', '[]'),
('七天无理由退货的条件是什么？', '["sop_refund_002"]'),
('退款多久能到账？', '["sop_refund_010"]'),
('客户退货后仓库说影响二次销售怎么办？', '["sop_refund_005"]'),
('换货流程是怎样的？', '[]'),
('售后超时未处理会怎样？', '["sop_refund_010","sop_refund_012"]'),
('客户申请退款但商品已使用过怎么办？', '["sop_refund_004"]'),
('部分退款怎么操作？', '["sop_refund_006"]'),
('售后单可以关闭后重新申请吗？', '["sop_refund_007"]'),
('客户投诉售后处理太慢怎么安抚？', '["sop_refund_018"]'),
('运费险怎么理赔？', '["sop_refund_009"]'),
('商品过了保修期还能维修吗？', '["sop_refund_014"]'),
('恶意退款客户怎么处理？', '["sop_refund_013"]'),
('库存预警怎么设置？', '["sop_inventory_008"]'),
('库存调拨怎么操作？', '["sop_inventory_007"]'),
('如何处理库存盘盈盘亏？', '["sop_inventory_003","sop_inventory_006"]'),
('在途库存是什么意思？', '["sop_inventory_010"]'),
('库存锁定和库存占用有什么区别？', '["sop_inventory_013","sop_inventory_010"]'),
('多仓库库存怎么管理？', '["sop_inventory_010"]'),
('库存为0但采购在途，客户能下单吗？', '["sop_inventory_008","sop_inventory_001"]'),
('怎么导出库存报表？', '["sop_inventory_014"]'),
('批次库存怎么管理？', '["sop_inventory_012"]'),
('库存成本怎么计算？', '["sop_inventory_011"]'),
('物流单号填错了怎么办？', '["sop_logistics_005"]'),
('如何批量发货？', '[]'),
('电子面单打印失败怎么办？', '["sop_logistics_005","sop_logistics_001"]'),
('物流异常有哪些类型？', '["sop_logistics_006"]'),
('怎么设置默认物流公司？', '["sop_logistics_004"]'),
('运费模板怎么配置？', '["sop_logistics_007"]'),
('国际物流怎么处理？', '["sop_logistics_013"]'),
('物流轨迹不更新超过多久算异常？', '["sop_logistics_010"]'),
('如何处理客户拒收？', '["sop_logistics_011"]'),
('采购单怎么创建？', '["sop_supplier_001","sop_purchase_001"]'),
('采购入库流程是什么？', '["sop_supplier_002"]'),
('供应商管理包含哪些信息？', '["sop_supplier_003","sop_purchase_002"]'),
('采购退货怎么操作？', '["sop_purchase_006"]'),
('怎么根据库存自动生成采购建议？', '["sop_purchase_001"]'),
('采购价格变动怎么处理？', '["sop_purchase_007"]'),
('采购账期怎么管理？', '["sop_purchase_005"]'),
('供应商评级怎么算？', '["sop_purchase_002"]'),
('采购订单可以部分入库吗？', '["sop_supplier_002"]'),
('紧急采购怎么走加急流程？', '["sop_purchase_008"]'),
('供应商对账怎么操作？', '["sop_purchase_009"]'),
('采购样品怎么管理？', '["sop_purchase_004"]'),
('商品上下架怎么操作？', '["sop_goods_007"]'),
('商品分类怎么管理？', '["sop_goods_005"]'),
('商品库存怎么同步到多个平台？', '["sop_goods_010"]'),
('商品详情页图片有什么要求？', '["sop_goods_008"]'),
('怎么设置商品促销价？', '["sop_goods_006"]'),
('商品条码怎么管理？', '["sop_goods_010"]'),
('滞销商品怎么处理？', '["sop_goods_013"]'),
('会员等级怎么划分？', '["sop_member_002"]'),
('积分怎么获取和使用？', '["sop_member_003"]'),
('客户标签怎么用？', '["sop_member_006"]'),
('怎么给会员发优惠券？', '["sop_member_004"]'),
('客户信息怎么修改？', '["sop_member_001"]'),
('会员储值怎么管理？', '["sop_member_005"]'),
('怎么查看客户购买历史？', '["sop_member_006"]'),
('客户投诉怎么处理和跟进？', '["sop_member_008"]'),
('沉睡客户怎么唤醒？', '["sop_member_007"]'),
('客户黑名单怎么设置？', '["sop_member_010"]'),
('ERP系统如何对接第三方电商平台的订单自动同步？', '["sop_order_013"]'),
('多仓库之间库存调拨的完整流程和注意事项是什么？', '["sop_inventory_007"]'),
('系统数据备份策略应该怎么制定才能防止误删和勒索软件？', '[]');

UPDATE agent_rag_evaluation e
JOIN rag_eval_relevance_seed s ON e.question = s.question
SET e.relevant_chunks = s.relevant_chunks
WHERE e.merchant_id = 1;

DROP TEMPORARY TABLE IF EXISTS rag_eval_relevance_seed;
