-- =====================================================================
-- migration_v4.3.0_seed_menu_perms.sql
-- 用途：P0-1 权限失效修复的数据配套——补齐 sys_menu.perms 与 F 按钮菜单，
--       给超级管理员角色补全菜单关联。带信息模式守卫，可重复执行。
-- 说明：与 Flyway V1_33_0__seed_menu_perms_and_role.sql 内容等价；
--       已手工改过结构的库执行本脚本后，需在 flyway_schema_history 登记 1.33.0。
-- =====================================================================

-- 1) C 菜单挂列表权限（幂等，UPDATE 天然可重复）
UPDATE sys_menu SET perms = 'shop:order:list' WHERE menu_id IN (100, 102);
UPDATE sys_menu SET perms = 'api:returned:list' WHERE menu_id IN (204, 206, 292);
UPDATE sys_menu SET perms = 'shop:shop:list' WHERE menu_id IN (308, 336);
UPDATE sys_menu SET perms = 'goods:goods:list' WHERE menu_id = 401;
UPDATE sys_menu SET perms = 'system:user:list' WHERE menu_id = 516;
UPDATE sys_menu SET perms = 'system:menu:list' WHERE menu_id = 517;
UPDATE sys_menu SET perms = 'system:role:list' WHERE menu_id = 2090;
UPDATE sys_menu SET perms = 'system:dict:list' WHERE menu_id = 579;

-- 2) 部门管理 C 菜单（守卫：仅当不存在时插入）
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, menu_type, visible, status, perms, icon, create_time)
SELECT 2200, '部门管理', 5, 4, 'dept', 'system/dept/index', 'C', '0', '0', 'system:dept:list', 'tree', NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM information_schema.TABLES t
                  WHERE t.TABLE_SCHEMA = DATABASE() AND t.TABLE_NAME = 'sys_menu'
                    AND EXISTS (SELECT 1 FROM sys_menu m WHERE m.menu_id = 2200));

-- 3) F 按钮菜单（守卫：按 menu_id 不存在才插入）
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, menu_type, visible, status, perms, icon, create_time)
SELECT * FROM (
    SELECT 2201 AS menu_id, '用户查询' AS menu_name, 516 AS parent_id, 1 AS order_num, '' AS path, '' AS component, 'F' AS menu_type, '0' AS visible, '0' AS status, 'system:user:query' AS perms, '#' AS icon, NOW() AS create_time
    UNION ALL SELECT 2202, '新增用户', 516, 2, '', '', 'F', '0', '0', 'system:user:add', '#', NOW()
    UNION ALL SELECT 2203, '修改用户', 516, 3, '', '', 'F', '0', '0', 'system:user:edit', '#', NOW()
    UNION ALL SELECT 2204, '删除用户', 516, 4, '', '', 'F', '0', '0', 'system:user:remove', '#', NOW()
    UNION ALL SELECT 2205, '重置密码', 516, 5, '', '', 'F', '0', '0', 'system:user:resetPwd', '#', NOW()
    UNION ALL SELECT 2206, '菜单查询', 517, 1, '', '', 'F', '0', '0', 'system:menu:query', '#', NOW()
    UNION ALL SELECT 2207, '新增菜单', 517, 2, '', '', 'F', '0', '0', 'system:menu:add', '#', NOW()
    UNION ALL SELECT 2208, '修改菜单', 517, 3, '', '', 'F', '0', '0', 'system:menu:edit', '#', NOW()
    UNION ALL SELECT 2209, '删除菜单', 517, 4, '', '', 'F', '0', '0', 'system:menu:remove', '#', NOW()
    UNION ALL SELECT 2210, '角色查询', 2090, 1, '', '', 'F', '0', '0', 'system:role:query', '#', NOW()
    UNION ALL SELECT 2211, '字典查询', 579, 1, '', '', 'F', '0', '0', 'system:dict:query', '#', NOW()
    UNION ALL SELECT 2212, '新增字典', 579, 2, '', '', 'F', '0', '0', 'system:dict:add', '#', NOW()
    UNION ALL SELECT 2213, '修改字典', 579, 3, '', '', 'F', '0', '0', 'system:dict:edit', '#', NOW()
    UNION ALL SELECT 2214, '删除字典', 579, 4, '', '', 'F', '0', '0', 'system:dict:remove', '#', NOW()
    UNION ALL SELECT 2215, '部门查询', 2200, 1, '', '', 'F', '0', '0', 'system:dept:query', '#', NOW()
    UNION ALL SELECT 2216, '店铺查询', 308, 1, '', '', 'F', '0', '0', 'shop:shop:query', '#', NOW()
    UNION ALL SELECT 2217, '新增店铺', 308, 2, '', '', 'F', '0', '0', 'shop:shop:add', '#', NOW()
    UNION ALL SELECT 2218, '修改店铺', 308, 3, '', '', 'F', '0', '0', 'shop:shop:edit', '#', NOW()
    UNION ALL SELECT 2219, '删除店铺', 308, 4, '', '', 'F', '0', '0', 'shop:shop:remove', '#', NOW()
    UNION ALL SELECT 2220, '售后查询', 204, 1, '', '', 'F', '0', '0', 'api:returned:query', '#', NOW()
    UNION ALL SELECT 2221, '订单查询', 100, 1, '', '', 'F', '0', '0', 'shop:order:query', '#', NOW()
    UNION ALL SELECT 2222, '商品查询', 401, 1, '', '', 'F', '0', '0', 'goods:goods:query', '#', NOW()
    UNION ALL SELECT 2223, '新增商品', 401, 2, '', '', 'F', '0', '0', 'goods:goods:add', '#', NOW()
    UNION ALL SELECT 2224, '修改商品', 401, 3, '', '', 'F', '0', '0', 'goods:goods:edit', '#', NOW()
    UNION ALL SELECT 2225, '删除商品', 401, 4, '', '', 'F', '0', '0', 'goods:goods:remove', '#', NOW()
) seed
WHERE NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.menu_id = seed.menu_id);

-- 4) role 1 显式持有全量菜单（幂等）
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, m.menu_id FROM sys_menu m
WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 1 AND rm.menu_id = m.menu_id);

-- 5) Flyway 登记（若 Flyway 启用且尚未登记 1.33.0；已由 Flyway 自动执行则跳过）
INSERT INTO flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success)
SELECT (SELECT COALESCE(MAX(installed_rank), 0) + 1 FROM flyway_schema_history f), '1.33.0',
       'seed menu perms and role', 'SQL', 'V1_33_0__seed_menu_perms_and_role.sql', NULL, 'manual', NOW(), 0, 1
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM flyway_schema_history f WHERE f.version = '1.33.0')
  AND EXISTS (SELECT 1 FROM information_schema.TABLES t WHERE t.TABLE_SCHEMA = DATABASE() AND t.TABLE_NAME = 'flyway_schema_history');
