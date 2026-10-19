-- V1_31_0: 根据真实评测结果补齐RAG知识缺口

INSERT IGNORE INTO `rag_document`
    (`chunk_id`, `doc_id`, `title`, `content`, `domain`, `merchant_id`)
VALUES
('sop_order_019', 'sop_order', '多订单合并发货流程',
 '多订单合并发货流程：在订单列表勾选同一客户、同一收货地址且均为待发货状态的订单，点击合并发货。系统校验商品和库存后生成合并出库单，每个原订单仍需保留独立订单号、金额和售后关系。合并发货完成后，各原订单分别记录同一物流单号和发货时间；库存按合并后的商品明细一次扣减。不同地址、含预售商品或售后中的订单不得合并。', 'order', 1),
('sop_order_020', 'sop_order', '订单拆分发货流程',
 '订单拆分发货流程：订单包含多个商品且部分商品缺货时，可在订单详情选择拆分发货。系统生成多个发货批次，每个批次可填写独立物流公司和物流单号。先发出有货商品，剩余商品保持待发货；全部批次发出后订单发货状态变为全部发货。拆单后的库存、退款金额和售后数量按商品明细分别记录。', 'order', 1),
('sop_order_021', 'sop_order', '订单商品数量修改规范',
 '订单商品数量修改规范：待付款订单由客户取消后重新下单；已付款未发货订单需要修改数量时，客服先确认库存和差价，提交订单修改申请并经审核后执行。增加数量需补款，减少数量按差额退款。已发货订单不能修改商品数量，应引导客户走售后补发、退款或换货流程。', 'order', 1),
('sop_order_022', 'sop_order', '订单操作日志查询',
 '订单操作日志查询：在订单详情页打开操作记录，可查看创建、付款、审核、改价、发货、备注、退款和售后等操作。每条日志包含操作人、操作时间、操作类型、修改前后内容和traceId。日志至少保留6个月，排障时可按订单号、操作人和时间范围筛选。操作日志只读，不允许手工修改或删除。', 'order', 1),
('sop_order_023', 'sop_order', '订单自动审核配置',
 '订单自动审核配置：在系统设置的订单审核规则中配置金额上限、客户信用、收货地区、商品类型和风控标签等条件。满足条件的订单自动审核通过并进入待发货，命中高风险规则的订单必须人工审核。规则修改需管理员审批并记录审计日志，发布前应使用历史订单做命中预览，避免误放行。', 'order', 1),
('sop_order_024', 'sop_order', '订单锁定状态处理',
 '订单锁定状态处理：状态22表示订单被风控或人工锁定，常见原因包括大额异常、频繁退款、异地登录、收货信息异常和支付风险。锁定期间不得发货，客服需查看风控原因并补充材料，由有权限人员审核后解锁或取消。解锁后订单恢复正常待发货流程，所有锁定和解锁操作均写入审计日志。', 'order', 1),
('sop_order_025', 'sop_order', '订单金额错误处理',
 '订单金额错误处理：先核对商品单价、数量、优惠、运费和税费明细，并确认平台回传金额与ERP计算金额是否一致。未付款订单可取消重下；已付款未发货订单通过改价或退款补差处理；已发货订单只能通过售后退款或补款处理。禁止直接修改数据库中已支付订单金额，金额调整必须保留审批和审计记录。', 'order', 1),
('sop_refund_019', 'sop_refund', '换货处理流程',
 '换货处理流程：客户提交换货申请并选择换出SKU，客服审核原因和商品状态，审核通过后客户寄回原商品。仓库验货确认无影响二次销售后，生成换货出库单并发出新商品。质量问题由商家承担运费，无理由换货由客户承担运费。换货完成后更新售后单和库存流水，原订单保留完整换货记录。', 'refund', 1),
('sop_refund_020', 'sop_refund', '运费险理赔流程',
 '运费险理赔流程：订单需在发货前购买运费险。客户完成退货并填写真实物流单号后，平台在退款完成后自动发起理赔；赔付金额以保单为准，通常3-5个工作日到账。客服可协助查询保单和理赔进度，但不得承诺具体赔付金额。退回空包、虚假物流或不符合保单条件的申请可能被拒赔。', 'refund', 1),
('sop_refund_021', 'sop_refund', '保修期与付费维修',
 '保修期与付费维修：保修期内且属于质量问题的商品，按品牌或供应商政策提供免费维修、换件或换货。超过保修期的商品可提供付费维修，客服需先告知检测费、维修费和预计周期，客户确认后再寄修。人为损坏、进水和私自拆机通常不在免费保修范围。', 'refund', 1),
('sop_refund_022', 'sop_refund', '售后关闭后重新申请',
 '售后关闭后重新申请：售后单关闭后，如仍在平台或商家约定的售后有效期内，客户可针对同一订单商品重新提交申请。系统需校验历史申请次数和商品状态，超过有效期或达到申请次数上限的，需由客服人工审核。重新申请应关联原订单和商品明细，保留历史售后记录。', 'refund', 1),
('sop_refund_023', 'sop_refund', '已使用商品退款处理',
 '已使用商品退款处理：已使用商品不支持七天无理由退货。若客户主张质量问题，需要提供商品照片、视频、检测报告等证据，客服核实后可提供维修、换货、部分退款或退货退款。无质量问题的已使用商品原则上拒绝退款，但可根据商品状态和平台规则协商折旧退款。', 'refund', 1),
('sop_logistics_014', 'sop_logistics', '默认物流公司设置',
 '默认物流公司设置：在物流设置中选择常用快递并配置电子面单账号、网点、模板和月结信息。可按仓库、商品类型、收货地区或店铺设置不同物流策略，发货时系统优先匹配默认承运商。修改默认物流公司前应检查面单余额和覆盖区域，保存后对新发货订单生效。', 'logistics', 1),
('sop_logistics_015', 'sop_logistics', '物流单号填写错误处理',
 '物流单号填写错误处理：发货后24小时内且物流未揽收的，可在订单发货信息中修改物流公司和单号。已被揽收或超过平台修改时限的，需联系平台客服处理。修改后系统通知客户新的物流信息，并保留原单号和修改原因。严禁使用虚假单号规避发货时效。', 'logistics', 1),
('sop_logistics_016', 'sop_logistics', '运费模板配置',
 '运费模板配置：按地区、重量、件数和金额设置首重、续重、满额包邮及偏远地区规则。模板可绑定商品或店铺，下单时系统按收货地址和商品明细自动计算运费。多个模板同时匹配时按优先级最高的规则执行，修改模板前应使用测试地址预览计算结果。', 'logistics', 1),
('sop_logistics_017', 'sop_logistics', '批量发货操作',
 '批量发货操作：在订单列表勾选多个待发货订单，点击批量发货，可统一选择物流公司或导入物流单号Excel。系统先校验订单状态、库存、地址和面单余额，校验失败的订单单独列出。发货成功的订单生成物流单号和库存出库流水，部分失败时可修正后再次提交，不重复处理已成功的订单。', 'logistics', 1),
('sop_inventory_016', 'sop_inventory', '库存报表导出',
 '库存报表导出：在库存管理页选择统计时间、仓库、商品分类和SKU范围，生成包含期初库存、入库、出库、锁定量、可用库存、期末库存和库存金额的Excel。数据量较大时应使用异步导出，任务完成后下载。导出结果按当前权限过滤租户和仓库，并记录导出审计日志。', 'inventory', 1),
('sop_member_011', 'sop_member', '会员优惠券发放',
 '会员优惠券发放：在会员营销中创建优惠券，配置面额、使用门槛、有效期、适用商品和领取数量。可按会员等级、标签、消费历史或指定会员人群发放，发送前先预估覆盖人数和预算。优惠券发放后记录领取和使用明细，活动期间监控核销率和异常刷券行为。', 'member', 1),
('sop_member_012', 'sop_member', '客户购买历史查询',
 '客户购买历史查询：在客户详情页查看订单数、累计消费、最近购买时间、客单价、退款率和商品偏好。购买明细可按时间、店铺、渠道和商品筛选，支持跳转到原订单。客服只能查看当前租户内且权限范围内的客户数据，敏感信息按规则脱敏并记录访问审计。', 'member', 1),
('sop_member_013', 'sop_member', '客户黑名单管理',
 '客户黑名单管理：对恶意退款、频繁拒收、欺诈、辱骂客服等高风险客户可设置黑名单。创建时填写原因、证据、生效范围和期限，提交管理员审批。黑名单客户可限制下单、货到付款或优惠券领取，但不得影响其正常售后和隐私权利。解除黑名单同样需要审批并保留记录。', 'member', 1),
('sop_member_014', 'sop_member', '客户标签使用规范',
 '客户标签使用规范：标签用于描述客户偏好、价值、渠道和风险，例如高复购、价格敏感、母婴人群、风险客户。可在会员营销中按标签圈选人群，也可在客服工作台展示服务提示。标签必须由规则或人工审核产生，定期清理失效标签，并禁止使用敏感个人信息直接作为标签名称。', 'member', 1),
('sop_purchase_011', 'sop_purchase', '采购样品管理',
 '采购样品管理：样品申请需填写供应商、商品、用途、数量和预计费用，经审批后创建样品采购单。样品到货后由申请人确认并做质量评估，可转为正式商品或归档为不合格样品。样品成本计入采购费用并保留供应商、检测结果和使用记录，避免与正常销售库存混用。', 'purchase', 1),
('sop_system_001', 'sop_system', '系统数据备份与勒索防护',
 '系统数据备份与勒索防护：采用3-2-1备份原则，至少保留3份副本、2种介质和1份异地离线备份。数据库每日全量加增量备份，保留不少于30天，备份文件加密并与生产网络隔离。每月执行恢复演练，验证备份可恢复性。批量删除、权限变更等高风险操作需双人复核和审计告警，发现勒索迹象立即隔离主机并切换只读应急模式。', 'system', 1);

UPDATE agent_rag_evaluation SET relevant_chunks = '["sop_order_019"]'
WHERE merchant_id = 1 AND question = '如何合并多个订单一起发货？';
UPDATE agent_rag_evaluation SET relevant_chunks = '["sop_order_020"]'
WHERE merchant_id = 1 AND question = '订单拆分发货怎么操作？';
UPDATE agent_rag_evaluation SET relevant_chunks = '["sop_order_021"]'
WHERE merchant_id = 1 AND question = '客户要求修改订单商品数量怎么办？';
UPDATE agent_rag_evaluation SET relevant_chunks = '["sop_order_022"]'
WHERE merchant_id = 1 AND question = '如何查询订单的操作日志？';
UPDATE agent_rag_evaluation SET relevant_chunks = '["sop_order_023"]'
WHERE merchant_id = 1 AND question = '如何设置订单自动审核？';
UPDATE agent_rag_evaluation SET relevant_chunks = '["sop_order_024"]'
WHERE merchant_id = 1 AND question = '订单锁定状态是什么意思？';
UPDATE agent_rag_evaluation SET relevant_chunks = '["sop_order_025"]'
WHERE merchant_id = 1 AND question = '订单金额计算错误怎么处理？';
UPDATE agent_rag_evaluation SET relevant_chunks = '["sop_refund_019"]'
WHERE merchant_id = 1 AND question = '换货流程是怎样的？';
UPDATE agent_rag_evaluation SET relevant_chunks = '["sop_refund_020"]'
WHERE merchant_id = 1 AND question = '运费险怎么理赔？';
UPDATE agent_rag_evaluation SET relevant_chunks = '["sop_refund_021"]'
WHERE merchant_id = 1 AND question = '商品过了保修期还能维修吗？';
UPDATE agent_rag_evaluation SET relevant_chunks = '["sop_refund_022"]'
WHERE merchant_id = 1 AND question = '售后单可以关闭后重新申请吗？';
UPDATE agent_rag_evaluation SET relevant_chunks = '["sop_refund_023"]'
WHERE merchant_id = 1 AND question = '客户申请退款但商品已使用过怎么办？';
UPDATE agent_rag_evaluation SET relevant_chunks = '["sop_logistics_014"]'
WHERE merchant_id = 1 AND question = '怎么设置默认物流公司？';
UPDATE agent_rag_evaluation SET relevant_chunks = '["sop_logistics_015"]'
WHERE merchant_id = 1 AND question = '物流单号填错了怎么办？';
UPDATE agent_rag_evaluation SET relevant_chunks = '["sop_logistics_016"]'
WHERE merchant_id = 1 AND question = '运费模板怎么配置？';
UPDATE agent_rag_evaluation SET relevant_chunks = '["sop_logistics_017"]'
WHERE merchant_id = 1 AND question = '如何批量发货？';
UPDATE agent_rag_evaluation SET relevant_chunks = '["sop_inventory_016"]'
WHERE merchant_id = 1 AND question = '怎么导出库存报表？';
UPDATE agent_rag_evaluation SET relevant_chunks = '["sop_member_011"]'
WHERE merchant_id = 1 AND question = '怎么给会员发优惠券？';
UPDATE agent_rag_evaluation SET relevant_chunks = '["sop_member_012"]'
WHERE merchant_id = 1 AND question = '怎么查看客户购买历史？';
UPDATE agent_rag_evaluation SET relevant_chunks = '["sop_member_013"]'
WHERE merchant_id = 1 AND question = '客户黑名单怎么设置？';
UPDATE agent_rag_evaluation SET relevant_chunks = '["sop_member_014"]'
WHERE merchant_id = 1 AND question = '客户标签怎么用？';
UPDATE agent_rag_evaluation SET relevant_chunks = '["sop_purchase_011"]'
WHERE merchant_id = 1 AND question = '采购样品怎么管理？';
UPDATE agent_rag_evaluation SET relevant_chunks = '["sop_system_001"]'
WHERE merchant_id = 1 AND question = '系统数据备份策略应该怎么制定才能防止误删和勒索软件？';
