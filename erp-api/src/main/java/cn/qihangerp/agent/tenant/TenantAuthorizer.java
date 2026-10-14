package cn.qihangerp.agent.tenant;

import cn.qihangerp.security.LoginUser;
import cn.qihangerp.security.common.SecurityUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * 租户授权辅助：管理接口的租户一律以"登录态绑定租户"为准。
 *
 * <ul>
 *   <li>商户用户：请求里的 merchantId 只能等于自己绑定的租户，否则抛 403（堵水平越权）。</li>
 *   <li>平台管理员（merchantId 为 null）：可显式指定要操作的租户。</li>
 * </ul>
 */
@Component
public class TenantAuthorizer {

    /** 平台管理员 user_type */
    private static final String ADMIN_USER_TYPE = "00";

    /**
     * 校验请求中的租户是否被允许，返回最终生效的租户。
     *
     * <ul>
     *   <li>商户用户（merchantId != null）：请求里的 merchantId 只能等于自己绑定的租户，否则抛 403。</li>
     *   <li>平台管理员（merchantId == null 且 user_type='00'）：可显式指定要操作的租户。</li>
     *   <li>merchantId == null 但非管理员：fail-closed，拒绝（防止新建用户未绑定租户时被当成管理员）。</li>
     * </ul>
     */
    public Long authorize(Long requestedTenant) {
        LoginUser loginUser = SecurityUtils.getLoginUser();
        Long bound = loginUser.getMerchantId();

        if (bound != null) {
            if (requestedTenant != null && !requestedTenant.equals(bound)) {
                throw new AccessDeniedException("禁止访问其他租户的数据（水平越权）");
            }
            return bound;
        }
        // merchantId 为 null 时，必须是平台管理员才允许跨租户
        if (loginUser.getUser() == null || !ADMIN_USER_TYPE.equals(loginUser.getUser().getUserType())) {
            throw new AccessDeniedException("当前用户未绑定租户且非平台管理员，拒绝访问（fail-closed）");
        }
        // 平台管理员：返回其显式指定的租户（可空）
        return requestedTenant;
    }

    /**
     * 要求必须有一个确定的租户（用于必须租户隔离的操作）。
     */
    public Long requireTenant(Long requestedTenant) {
        Long tenant = authorize(requestedTenant);
        if (tenant == null) {
            throw new AccessDeniedException("缺少租户上下文，拒绝执行");
        }
        return tenant;
    }

    /**
     * 获取当前登录用户绑定的租户（不接受请求参数）。
     */
    public Long currentTenant() {
        return SecurityUtils.getLoginUser().getMerchantId();
    }
}
