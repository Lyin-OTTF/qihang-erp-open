package cn.qihangerp.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import jakarta.servlet.DispatcherType;

@Configuration
@EnableWebSecurity
// P0-1 修复：开启方法级安全，使控制器 @PreAuthorize("@ss.hasPermi(...)") 真正生效。
// 此前未开启时注解从不被求值，接口级授权形同虚设（所有登录用户可调用全部管理接口）。
@EnableMethodSecurity
public class SecurityConfig {

    @Autowired
    private UserDetailsServiceImpl userDetailsService;

    @Autowired
    private AuthenticationExceptionHandler invalidAuthenticationEntryPoint;
    /**
     * 退出处理类
     */
    @Autowired
    private LogoutSuccessHandlerImpl logoutSuccessHandler;
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public JwtAuthenticationTokenFilter authenticationJwtTokenFilter() {
        return new JwtAuthenticationTokenFilter();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {

        http
                // CORS跨域配置
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                // 禁用basic明文验证
                .httpBasic(AbstractHttpConfigurer::disable)
                // 前后端分离架构不需要csrf保护
                .csrf(AbstractHttpConfigurer::disable)
                // 禁用默认登录页
                .formLogin(AbstractHttpConfigurer::disable)
                // 禁用默认登出页
//                .logout().disable()
                // 设置异常的EntryPoint，如果不设置，默认使用Http403ForbiddenEntryPoint
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(invalidAuthenticationEntryPoint))
                // 前后端分离是无状态的，不需要session了，直接禁用。
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorizeHttpRequests -> authorizeHttpRequests
                        // 放行异步调度（SseEmitter等）
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        // 允许所有OPTIONS请求
                        .requestMatchers("/home").permitAll()
                        .requestMatchers(HttpMethod.GET, "/favicon.ico").permitAll()
                        // P0-3 修复：以下为历史遗留死规则（全仓库无对应 Controller 映射，
                        // 仅此处放行），已删除，避免形成未登记的匿名入口：
                        //   /tao/order/get_detail、/*/order/get_detail、/refund/get_detail、
                        //   /images/preview/**、/system/config/**（无 /api/sys-api 前缀）
                        // 真实的订单/退款详情接口位于 /api/erp-api/order/**、/api/erp-api/refund/**，
                        // 由 @PreAuthorize 保护，统一走 anyRequest().authenticated()。
                        .requestMatchers(HttpMethod.GET, "/captchaImage").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/sys-api/captchaImage").permitAll()
                        // P0-3 修复：系统参数表(configValue)含密钥类配置，原 /** 通配可被匿名遍历。
                        // 收窄为登录页标题所需的固定 key（前端登录页 getConfig('sys.name')）。
                        .requestMatchers(HttpMethod.GET,
                                "/api/sys-api/system/config/getConfigValue/sys.name",
                                "/api/sys-api/system/config/configKey/sys.name").permitAll()
                        .requestMatchers("/pdd/oauth_callback").permitAll()
                        // 允许直接访问授权登录接口
                        .requestMatchers(HttpMethod.POST, "/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/sys-api/login").permitAll()
                        // P0-3 修复：SSE 推送与 AI 简报原为匿名放行。SSE 无任何鉴权即可
                        // 接收全局广播（新订单提醒等）；AI 简报为内部经营数据。两者收回登录态，
                        // 前端 EventSource 已带 ?token=...（TokenService.getToken 支持参数读取）。
                        // 允许 SpringMVC 的默认错误地址匿名访问
                        .requestMatchers("/error").permitAll()
                        // 监控端点：仅放行健康检查与 Prometheus 抓取，其余 actuator 端点仍需登录
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/prometheus").permitAll()
                        // 其他所有接口必须登录。接口级权限由 @EnableMethodSecurity +
                        // @PreAuthorize("@ss.hasPermi(...)") 校验（P0-1 修复），不在此处展开。
                        .anyRequest().authenticated())

                .authenticationProvider(authenticationProvider())
                // 加我们自定义的过滤器，替代UsernamePasswordAuthenticationFilter
                .addFilterBefore(authenticationJwtTokenFilter(), UsernamePasswordAuthenticationFilter.class);
        // 添加Logout filter
        http.logout(logout -> logout.logoutUrl("/api/sys-api/logout").logoutSuccessHandler(logoutSuccessHandler));
        return http.build();
    }

    @Bean
    public UserDetailsService userDetailsService() {
        // 调用 JwtUserDetailService实例执行实际校验
        return username -> userDetailsService.loadUserByUsername(username);
    }

    /**
     * 调用loadUserByUsername获得UserDetail信息，在AbstractUserDetailsAuthenticationProvider里执行用户状态检查
     *
     * @return
     */
    @Bean
    public AuthenticationProvider authenticationProvider() {
        // DaoAuthenticationProvider 从自定义的 userDetailsService.loadUserByUsername 方法获取UserDetails
        // Spring Security 7.x: 构造器直接传入 UserDetailsService
        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider(userDetailsService());
        // 设置密码编辑器
        authProvider.setPasswordEncoder(passwordEncoder());
        return authProvider;
    }

    /**
     * 登录时需要调用AuthenticationManager.authenticate执行一次校验
     *
     * @param config
     * @return
     * @throws Exception
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    /**
     * CORS配置 - Spring Boot 4.1 / Spring Security 7.x 需要显式配置
     *
     * P1-1 修复：原 addAllowedOriginPattern("*") + setAllowCredentials(true) 是
     * "任意源 + 携带凭据"的高危组合（任意网站可跨域携带用户 Token 调 API）。
     * 现收缩为显式白名单：开发前端 vite dev server(88) 与后端(6149) 本机来源；
     * 生产经 nginx 同源代理（/prod-api），不依赖 CORS。
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(java.util.List.of(
                "http://localhost:6149", "http://127.0.0.1:6149",
                "http://localhost:88", "http://127.0.0.1:88"));
        configuration.addAllowedMethod("*");
        configuration.addAllowedHeader("*");
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }


}
