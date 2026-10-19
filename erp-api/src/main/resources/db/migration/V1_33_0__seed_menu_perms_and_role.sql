-- V1_33_0: 补齐菜单权限标识(perms)与按钮级(F)菜单，并给超级管理员角色补全菜单关联
--
-- 背景：P0-1 权限失效修复（恢复 SysPermissionService 真实加载 + @EnableMethodSecurity）后，
-- getMenuPermission 非 admin 用户改从 sys_menu.perms 取权限。修复前 sys_menu.perms 67 条仅 3 条非空，
-- 普通角色将拿到空权限集合导致全部 403，且 @PreAuthorize 的 34 个权限字符无对应菜单数据。
-- 本迁移按 RuoYi 规范补齐：C 菜单挂 list 权限、新增 F 按钮菜单挂 add/edit/remove/query 权限、
-- 新建缺失的"部门管理"菜单、role 1（超级管理员）显式持有全量菜单。

-- 1) C 菜单挂列表权限（与控制器 @PreAuthorize 字符对齐）
UPDATE sys_menu SET perms = 'shop:order:list' WHERE menu_id IN (100, 102);
UPDATE sys_menu SET perms = 'api:returned:list' WHERE menu_id IN (204, 206, 292);
UPDATE sys_menu SET perms = 'shop:shop:list' WHERE menu_id IN (308, 336);
UPDATE sys_menu SET perms = 'goods:goods:list' WHERE menu_id = 401; -- 原值 'goods' 非标准权限字符
UPDATE sys_menu SET perms = 'system:user:list' WHERE menu_id = 516;
UPDATE sys_menu SET perms = 'system:menu:list' WHERE menu_id = 517;
UPDATE sys_menu SET perms = 'system:role:list' WHERE menu_id = 2090; -- 原值为字符串 'NULL'
UPDATE sys_menu SET perms = 'system:dict:list' WHERE menu_id = 579;

-- 2) 新建缺失的"部门管理"C 菜单（system:dept:* 权限字符此前无菜单载体）
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, menu_type, visible, status, perms, icon, create_time)
VALUES (2200, '部门管理', 5, 4, 'dept', 'system/dept/index', 'C', '0', '0', 'system:dept:list', 'tree', NOW());

-- 3) F 按钮菜单（权限字符落库；visible=0 与 RuoYi 一致，前端按 menu_type 过滤不显示按钮）
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, menu_type, visible, status, perms, icon, create_time) VALUES
-- 用户管理
(2201, '用户查询', 516, 1, '', '', 'F', '0', '0', 'system:user:query', '#', NOW()),
(2202, '新增用户', 516, 2, '', '', 'F', '0', '0', 'system:user:add', '#', NOW()),
(2203, '修改用户', 516, 3, '', '', 'F', '0', '0', 'system:user:edit', '#', NOW()),
(2204, '删除用户', 516, 4, '', '', 'F', '0', '0', 'system:user:remove', '#', NOW()),
(2205, '重置密码', 516, 5, '', '', 'F', '0', '0', 'system:user:resetPwd', '#', NOW()),
-- 菜单管理
(2206, '菜单查询', 517, 1, '', '', 'F', '0', '0', 'system:menu:query', '#', NOW()),
(2207, '新增菜单', 517, 2, '', '', 'F', '0', '0', 'system:menu:add', '#', NOW()),
(2208, '修改菜单', 517, 3, '', '', 'F', '0', '0', 'system:menu:edit', '#', NOW()),
(2209, '删除菜单', 517, 4, '', '', 'F', '0', '0', 'system:menu:remove', '#', NOW()),
-- 角色管理
(2210, '角色查询', 2090, 1, '', '', 'F', '0', '0', 'system:role:query', '#', NOW()),
-- 字典管理
(2211, '字典查询', 579, 1, '', '', 'F', '0', '0', 'system:dict:query', '#', NOW()),
(2212, '新增字典', 579, 2, '', '', 'F', '0', '0', 'system:dict:add', '#', NOW()),
(2213, '修改字典', 579, 3, '', '', 'F', '0', '0', 'system:dict:edit', '#', NOW()),
(2214, '删除字典', 579, 4, '', '', 'F', '0', '0', 'system:dict:remove', '#', NOW()),
-- 部门管理
(2215, '部门查询', 2200, 1, '', '', 'F', '0', '0', 'system:dept:query', '#', NOW()),
-- 店铺管理
(2216, '店铺查询', 308, 1, '', '', 'F', '0', '0', 'shop:shop:query', '#', NOW()),
(2217, '新增店铺', 308, 2, '', '', 'F', '0', '0', 'shop:shop:add', '#', NOW()),
(2218, '修改店铺', 308, 3, '', '', 'F', '0', '0', 'shop:shop:edit', '#', NOW()),
(2219, '删除店铺', 308, 4, '', '', 'F', '0', '0', 'shop:shop:remove', '#', NOW()),
-- 订单售后
(2220, '售后查询', 204, 1, '', '', 'F', '0', '0', 'api:returned:query', '#', NOW()),
-- 订单库
(2221, '订单查询', 100, 1, '', '', 'F', '0', '0', 'shop:order:query', '#', NOW()),
-- 商品库
(2222, '商品查询', 401, 1, '', '', 'F', '0', '0', 'goods:goods:query', '#', NOW()),
(2223, '新增商品', 401, 2, '', '', 'F', '0', '0', 'goods:goods:add', '#', NOW()),
(2224, '修改商品', 401, 3, '', '', 'F', '0', '0', 'goods:goods:edit', '#', NOW()),
(2225, '删除商品', 401, 4, '', '', 'F', '0', '0', 'goods:goods:remove', '#', NOW());

-- 4) role 1（超级管理员）显式持有全量菜单。
-- 虽然 admin 用户在 getMenuPermission 中走 isAdmin() 短路拿 *:*:*，
-- 显式关联保证"角色-菜单"数据完整，RBAC 体系可审计、可复用。
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, m.menu_id FROM sys_menu m
WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 1 AND rm.menu_id = m.menu_id);
