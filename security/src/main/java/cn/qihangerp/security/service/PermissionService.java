package cn.qihangerp.security.service;

import cn.qihangerp.security.LoginUser;
import cn.qihangerp.security.common.SecurityUtils;
import org.springframework.stereotype.Service;

/**
 * 权限校验服务（Spring Security 方法级安全表达式根对象，bean 名 "ss"）。
 *
 * <p>配合 SecurityConfig 上的 @EnableMethodSecurity 生效，供控制器注解使用：
 * {@code @PreAuthorize("@ss.hasPermi('system:user:add')")}。
 *
 * <p>历史缺陷（P0-1）：开启方法安全前 @PreAuthorize 从未被求值；此前也没有任何
 * 名为 "ss" 的 bean，直接加 @EnableMethodSecurity 会因 NoSuchBeanDefinitionException
 * 启动失败，故此类为开启方法安全的前置必备件。
 *
 * @author qihang
 */
@Service("ss")
public class PermissionService
{
    /** 超级管理员权限通配符（SysPermissionService 对 admin 用户返回该值） */
    private static final String ALL_PERMISSION = "*:*:*";

    /**
     * 判断当前登录用户是否拥有指定权限标识。
     *
     * @param permission 权限标识，如 system:user:add
     * @return 拥有返回 true；未登录、无权限或标识为空返回 false
     */
    public boolean hasPermi(String permission)
    {
        if (permission == null || permission.isEmpty())
        {
            return false;
        }
        try
        {
            LoginUser loginUser = SecurityUtils.getLoginUser();
            if (loginUser == null || loginUser.getPermissions() == null)
            {
                return false;
            }
            return loginUser.getPermissions().contains(ALL_PERMISSION)
                    || loginUser.getPermissions().contains(permission);
        }
        catch (Exception e)
        {
            // 无安全上下文（如内部调用/异步线程）时按无权限处理，fail-closed
            return false;
        }
    }

    /**
     * 判断当前登录用户是否拥有指定角色。
     *
     * 当前项目所有 @PreAuthorize 均使用 hasPermi，暂无 hasRole 调用点；
     * 预留实现，避免后续使用时静默放行。
     */
    public boolean hasRole(String role)
    {
        if (role == null || role.isEmpty())
        {
            return false;
        }
        try
        {
            LoginUser loginUser = SecurityUtils.getLoginUser();
            if (loginUser == null)
            {
                return false;
            }
            // LoginUser 当前只承载 permissions（角色标识未纳入），暂按 admin 通配等价判断
            return loginUser.getPermissions() != null
                    && loginUser.getPermissions().contains(ALL_PERMISSION);
        }
        catch (Exception e)
        {
            return false;
        }
    }
}
