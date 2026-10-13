package cn.qihangerp.agent.tenant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 租户上下文 - 使用ThreadLocal存储当前租户ID
 *
 * 在多租户环境下，确保每个请求都携带正确的租户ID，防止跨租户数据访问
 *
 * @author AI Code Generator
 * @since 2026-10-12
 */
public class TenantContext {
    private static final Logger log = LoggerFactory.getLogger(TenantContext.class);

    /**
     * ThreadLocal存储当前线程的租户ID
     */
    private static final ThreadLocal<Long> TENANT_ID_HOLDER = new ThreadLocal<>();

    /**
     * 设置当前租户ID
     *
     * @param tenantId 租户ID
     */
    public static void setTenantId(Long tenantId) {
        if (tenantId == null) {
            log.warn("尝试设置null作为租户ID");
            return;
        }
        TENANT_ID_HOLDER.set(tenantId);
        log.debug("设置租户ID: {}", tenantId);
    }

    /**
     * 获取当前租户ID
     *
     * @return 租户ID，如果未设置返回null
     */
    public static Long getTenantId() {
        return TENANT_ID_HOLDER.get();
    }

    /**
     * 获取当前租户ID，如果未设置抛出异常
     *
     * @return 租户ID
     * @throws TenantContextException 租户ID未设置时抛出
     */
    public static Long getRequiredTenantId() throws TenantContextException {
        Long tenantId = TENANT_ID_HOLDER.get();
        if (tenantId == null) {
            throw new TenantContextException("租户ID未设置，无法执行需要租户隔离的操作");
        }
        return tenantId;
    }

    /**
     * 检查是否已设置租户ID
     *
     * @return true表示已设置，false表示未设置
     */
    public static boolean hasTenantId() {
        return TENANT_ID_HOLDER.get() != null;
    }

    /**
     * 清除当前租户ID
     */
    public static void clear() {
        Long tenantId = TENANT_ID_HOLDER.get();
        if (tenantId != null) {
            log.debug("清除租户ID: {}", tenantId);
        }
        TENANT_ID_HOLDER.remove();
    }

    /**
     * 租户上下文异常
     */
    public static class TenantContextException extends RuntimeException {
        public TenantContextException(String message) {
            super(message);
        }
    }
}
