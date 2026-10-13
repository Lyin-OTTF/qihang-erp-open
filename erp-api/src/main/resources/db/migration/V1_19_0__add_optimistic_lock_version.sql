-- 乐观锁 version 字段添加
-- 为订单、售后、库存核心表添加 version 列，用于 MyBatis-Plus @Version 乐观锁
-- 回溯开发-搁置项攻关：任务2 乐观锁version字段

ALTER TABLE `oms_shop_order` ADD COLUMN `version` int DEFAULT 1 COMMENT '乐观锁版本号';
ALTER TABLE `oms_shop_refund` ADD COLUMN `version` int DEFAULT 1 COMMENT '乐观锁版本号';
ALTER TABLE `o_goods_inventory` ADD COLUMN `version` int DEFAULT 1 COMMENT '乐观锁版本号';
ALTER TABLE `erp_purchase_order` ADD COLUMN `version` int DEFAULT 1 COMMENT '乐观锁版本号';
