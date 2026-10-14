package cn.qihangerp.agent.tenant;

import cn.qihangerp.security.LoginUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 租户拦截器 —— 租户ID只从"登录态"获取，不再由客户端说了算。
 *
 * <p>规则（第2期 堵越权）：</p>
 * <ol>
 *   <li>普通商户用户：租户 = 登录用户绑定的 merchant_id，{@code X-Tenant-Id} 请求头一律忽略（防水平越权）。</li>
 *   <li>平台管理员（merchant_id 为 null）：默认无租户（可跨租户，由后端显式逻辑控制）；
 *       仅对其允许通过 {@code X-Tenant-Id} 显式指定要查看的商户（受信任主体的受控覆盖）。</li>
 * </ol>
 *
 * <p>注意：本拦截器必须通过 {@link TenantWebMvcConfig} 注册到拦截器链才会生效。</p>
 */
@Component
public class TenantInterceptor implements HandlerInterceptor {
    private static final Logger log = LoggerFactory.getLogger(TenantInterceptor.class);

    private static final String TENANT_ID_HEADER = "X-Tenant-Id";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof LoginUser loginUser) {
            Long boundMerchantId = loginUser.getMerchantId();

            if (boundMerchantId != null) {
                // 普通商户用户：只信绑定租户，忽略客户端传入的 X-Tenant-Id
                TenantContext.setTenantId(boundMerchantId);
                log.debug("租户来自登录态: userId={} merchantId={}", loginUser.getUserId(), boundMerchantId);
                return true;
            }

            // 平台管理员：允许受控地用 X-Tenant-Id 指定商户，否则无租户
            String header = request.getHeader(TENANT_ID_HEADER);
            if (header != null && !header.isEmpty()) {
                try {
                    TenantContext.setTenantId(Long.parseLong(header));
                    log.debug("平台管理员显式指定租户: {}", TenantContext.getTenantId());
                } catch (NumberFormatException e) {
                    log.warn("平台管理员传入的 X-Tenant-Id 格式错误: {}", header);
                }
            }
            return true;
        }

        // 未认证（理论上被 SecurityConfig 的 anyRequest().authenticated() 拦截，到这里属于极少数放行路径）
        // 不设置任何租户，交由下游按 fail-closed 处理。
        log.debug("未认证请求，不注入租户: {} {}", request.getMethod(), request.getRequestURI());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        TenantContext.clear();
    }
}
