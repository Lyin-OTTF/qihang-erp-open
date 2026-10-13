package cn.qihangerp.erp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Jackson 配置。
 *
 * <p>显式提供 ObjectMapper bean（项目中 common 模块的 tools.jackson 可能干扰
 * Spring Boot 默认 Jackson 自动配置）。通过 findAndRegisterModules() SPI 方式
 * 注册 classpath 上的 jackson-datatype-jsr310（runtime 作用域），避免编译期依赖。</p>
 *
 * @author 回溯开发-启动修复
 * @since 2026-10-12
 */
@Configuration
public class JacksonConfig {

    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper()
                .findAndRegisterModules();
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }
}
