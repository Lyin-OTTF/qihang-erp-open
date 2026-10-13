package cn.qihangerp.agent.tenant;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 租户拦截器 - 自动从请求中提取租户ID并设置到TenantContext
 *
 * 支持多种方式传递租户ID：
 * 1. HTTP Header: X-Tenant-Id
 * 2. 请求参数: merchantId
 * 3. Spring Security上下文（TODO: 待实现）
 *
 * @author AI Code Generator
 * @since 2026-10-12
 */
@Component
public class TenantInterceptor implements HandlerInterceptor {
    private static final Logger log = LoggerFactory.getLogger(TenantInterceptor.class);

    /**
     * HTTP Header名称
     */
    private static final String TENANT_ID_HEADER = "X-Tenant-Id";

    /**
     * 请求参数名称
     */
    private static final String TENANT_ID_PARAM = "merchantId";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 方式1: 从HTTP Header获取
        String tenantIdHeader = request.getHeader(TENANT_ID_HEADER);
        if (tenantIdHeader != null && !tenantIdHeader.isEmpty()) {
            try {
                Long tenantId = Long.parseLong(tenantIdHeader);
                TenantContext.setTenantId(tenantId);
                log.debug("从Header提取租户ID: {}", tenantId);
                return true;
            } catch (NumberFormatException e) {
                log.warn("Header中的租户ID格式错误: {}", tenantIdHeader);
            }
        }

        // 方式2: 从请求参数获取
        String tenantIdParam = request.getParameter(TENANT_ID_PARAM);
        if (tenantIdParam != null && !tenantIdParam.isEmpty()) {
            try {
                Long tenantId = Long.parseLong(tenantIdParam);
                TenantContext.setTenantId(tenantId);
                log.debug("从请求参数提取租户ID: {}", tenantId);
                return true;
            } catch (NumberFormatException e) {
                log.warn("请求参数中的租户ID格式错误: {}", tenantIdParam);
            }
        }

        // 方式3: 从Spring Security上下文获取（TODO: 待实现）
        // Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        // if (authentication != null && authentication.getPrincipal() instanceof UserDetails) {
        //     UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        //     Long tenantId = extractTenantIdFromUserDetails(userDetails);
        //     TenantContext.setTenantId(tenantId);
        //     return true;
        // }

        // 未提取到租户ID，打印警告但不阻止请求
        log.debug("请求未携带租户ID: {} {}", request.getMethod(), request.getRequestURI());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // 请求完成后清除租户上下文，防止线程池复用导致的数据泄露
        TenantContext.clear();
    }
}
