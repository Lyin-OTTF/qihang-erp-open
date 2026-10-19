package cn.qihangerp.security;

import cn.qihangerp.common.ServiceException;
import cn.qihangerp.enums.UserStatus;
import cn.qihangerp.common.utils.MessageUtils;
import cn.qihangerp.common.utils.StringUtils;
import cn.qihangerp.model.entity.SysUser;
import cn.qihangerp.service.ISysUserService;
import cn.qihangerp.service.SysPermissionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * 用户验证处理
 *
 * @author qihang
 */
@Service
public class UserDetailsServiceImpl implements UserDetailsService
{
    private static final Logger log = LoggerFactory.getLogger(UserDetailsServiceImpl.class);

    @Autowired
    private ISysUserService userService;

    /**
     * 权限服务：按 sys_menu.perms + 角色关联解析真实权限。
     *
     * 历史缺陷（P0-1）：此类原先把 permissionService 注入注释掉，并在 createLoginUser 中
     * 硬编码 perms.add("*:*:*")——所有登录用户无论角色都拿到全量权限，导致
     * @PreAuthorize("@ss.hasPermi(...)") 恒真、接口级授权完全失效。
     */
    @Autowired
    private SysPermissionService permissionService;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException
    {
        SysUser user = userService.selectUserByUserName(username);
        if (StringUtils.isNull(user))
        {
            log.info("登录用户：{} 不存在.", username);
            throw new ServiceException(MessageUtils.message("user.not.exists"));
        }
        else if (UserStatus.DELETED.getCode().equals(user.getDelFlag()))
        {
            log.info("登录用户：{} 已被删除.", username);
            throw new ServiceException(MessageUtils.message("user.password.delete"));
        }
        else if (UserStatus.DISABLE.getCode().equals(user.getStatus()))
        {
            log.info("登录用户：{} 已被停用.", username);
            throw new ServiceException(MessageUtils.message("user.blocked"));
        }

        return createLoginUser(user);
    }

    public UserDetails createLoginUser(SysUser user)
    {
        // 从权限服务解析真实权限（admin 走 isAdmin 短路拿 *:*:*，其余按 sys_role_menu → sys_menu.perms）
        return new LoginUser(user.getUserId(), user.getDeptId(), user,
                permissionService.getMenuPermission(user));
    }
}
