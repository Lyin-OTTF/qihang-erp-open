package cn.qihangerp.agent.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * PGVector手动配置。
 *
 * <p>Spring AI的PgVectorStoreAutoConfiguration默认使用主DataSource（MySQL），
 * 不支持通过yml配置独立的PostgreSQL连接。因此手动创建PostgreSQL数据源
 * 和PgVectorStore bean。</p>
 *
 * <p>同时禁用自动配置（spring.ai.vectorstore.pgvector.enabled=false），
 * 避免重复创建。</p>
 */
@Slf4j
@Configuration
public class PgVectorManualConfig {

    @Bean
    public PgVectorStore pgVectorStore(EmbeddingModel embeddingModel) {
        log.info("创建PgVectorStore: dimensions=1024, indexType=HNSW, distanceType=COSINE_DISTANCE");
        // 局部创建PostgreSQL数据源，不暴露为bean，避免抢占主MySQL数据源
        DataSource pgDataSource = DataSourceBuilder.create()
                .url("jdbc:postgresql://localhost:5432/erp_vector")
                .username("postgres")
                .password("1234")
                .driverClassName("org.postgresql.Driver")
                .build();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(pgDataSource);
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .dimensions(1024)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.HNSW)
                .initializeSchema(true)
                .build();
    }
}
