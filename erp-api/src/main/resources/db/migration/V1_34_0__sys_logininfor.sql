-- V1_34_0: 系统访问记录表（登录审计）
--
-- 背景：P1-2 修复恢复登录审计落库。此前整仓无 sys_logininfor 表、无实体/Mapper/Service，
-- SysLoginService 中 AsyncFactory.recordLogininfor 调用全部被注释，登录失败无痕迹。

CREATE TABLE sys_logininfor (
    info_id        bigint NOT NULL AUTO_INCREMENT COMMENT '访问ID',
    user_name      varchar(30)  DEFAULT '' COMMENT '用户账号',
    ipaddr         varchar(128) DEFAULT '' COMMENT '登录IP地址',
    login_location varchar(255) DEFAULT '' COMMENT '登录地点',
    browser        varchar(50)  DEFAULT '' COMMENT '浏览器类型',
    os             varchar(50)  DEFAULT '' COMMENT '操作系统',
    status         varchar(10)  DEFAULT '0' COMMENT '状态（Success 成功 / Error 失败）',
    msg            varchar(255) DEFAULT '' COMMENT '提示消息',
    login_time     datetime     DEFAULT NULL COMMENT '访问时间',
    PRIMARY KEY (info_id) USING BTREE,
    KEY idx_logininfor_s (status)
) ENGINE=InnoDB COMMENT='系统访问记录';
