-- V1_28_0: 补全RAG评测集至100条（修复uk_merchant_question去重导致的3条缺失）
-- 原有V1_16_1+V1_22预期100条，因question(60)前缀去重实际仅97条
-- 补充3条跨领域/系统级问题，确保前60字符不与已有记录冲突

INSERT IGNORE INTO `agent_rag_evaluation` (`merchant_id`, `question`, `expected_answer`, `relevant_chunks`, `domain`, `difficulty`) VALUES
(1, 'ERP系统如何对接第三方电商平台的订单自动同步？', '通过平台开放API（如淘宝/京东/拼多多开放平台）配置授权，系统定时拉取增量订单并写入oms_shop_order表；需处理订单状态映射、幂等去重（以平台订单号为唯一键）、异常重试和告警；首次对接建议先全量同步历史30天订单，再切换为增量轮询。', '["integration_platform_001","order_sync_001"]', 'integration', 'hard'),
(1, '多仓库之间库存调拨的完整流程和注意事项是什么？', '调出仓创建调拨单→审核→出库扣减→物流在途→调入仓入库增加；调拨过程中在途库存不计入可用库存，需防止重复扣减；注意调拨成本核算（加权平均/先进先出）、跨区调拨的运费分摊、以及调拨失败时的回滚机制；建议调拨单全程留痕可审计。', '["inventory_transfer_001","warehouse_multi_001"]', 'inventory', 'hard'),
(1, '系统数据备份策略应该怎么制定才能防止误删和勒索软件？', '采用3-2-1备份原则：3份数据副本、2种不同介质、1份异地离线；每日全量备份+增量备份，保留至少30天；备份文件加密存储且与生产网络隔离（防勒索软件横向传播）；每月至少做一次恢复演练验证备份可用性；关键操作（如批量删除）需双人复核并留审计日志。', '["backup_strategy_001","security_disaster_001"]', 'system', 'hard');
