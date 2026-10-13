package cn.qihangerp.agent.tenant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC配置 - 注册租户拦截器
 *
 * @author AI Code Generator
 * @since 2026-10-12
 */
@Configuration
public class TenantWebMvcConfig implements WebMvcConfigurer {
    private static final Logger log = LoggerFactory.getLogger(TenantWebMvcConfig.class);

    private final TenantInterceptor tenantInterceptor;

    public TenantWebMvcConfig(TenantInterceptor tenantInterceptor) {
        this.tenantInterceptor = tenantInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tenantInterceptor)
                .addPathPatterns("/**")  // 拦截所有请求
                .excludePathPatterns(    // 排除不需要租户隔离的路径
                        "/error",
                        "/actuator/**",
                        "/swagger-ui/**",
                        "/v3/api-docs/**",
                        "/favicon.ico"
                );
        log.info("租户拦截器已注册");
    }
}
