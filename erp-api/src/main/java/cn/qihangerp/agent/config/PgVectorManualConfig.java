package cn.qihangerp.agent.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * PGVector手动配置（可开关）。
 *
 * <p>Spring AI的PgVectorStoreAutoConfiguration默认使用主DataSource（MySQL），
 * 不支持通过yml配置独立的PostgreSQL连接，因此手动创建PostgreSQL数据源和PgVectorStore。</p>
 *
 * <p><b>开关</b>：仅当 {@code agent.vector-store.enabled=true} 时才创建 PgVectorStore。
 * 开关关闭（默认）或 PostgreSQL 不可用时，不创建该 bean ——
 * PgVectorRetriever 的 VectorStore 为 null，自动降级到 MySQL FULLTEXT → BM25，
 * 应用启动不再强依赖 PostgreSQL。</p>
 *
 * <p>连接信息通过 {@code agent.vector-store.*} 配置，默认值指向本机 Docker pgvector。</p>
 *
 * @author 回溯开发-PGVector接线
 * @since 2026-10-13
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "agent.vector-store.enabled", havingValue = "true", matchIfMissing = false)
public class PgVectorManualConfig {

    @Bean
    public PgVectorStore pgVectorStore(
            EmbeddingModel embeddingModel,
            @org.springframework.beans.factory.annotation.Value("${agent.vector-store.host:localhost}") String host,
            @org.springframework.beans.factory.annotation.Value("${agent.vector-store.port:5432}") int port,
            @org.springframework.beans.factory.annotation.Value("${agent.vector-store.database:erp_vector}") String database,
            @org.springframework.beans.factory.annotation.Value("${agent.vector-store.username:postgres}") String username,
            @org.springframework.beans.factory.annotation.Value("${agent.vector-store.password:1234}") String password,
            @org.springframework.beans.factory.annotation.Value("${agent.vector-store.dimensions:1024}") int dimensions) {

        log.info("创建PgVectorStore: host={}:{}, database={}, dimensions={}", host, port, database, dimensions);
        // 局部创建PostgreSQL数据源，不暴露为bean，避免抢占主MySQL数据源
        DataSource pgDataSource = DataSourceBuilder.create()
                .url("jdbc:postgresql://" + host + ":" + port + "/" + database)
                .username(username)
                .password(password)
                .driverClassName("org.postgresql.Driver")
                .build();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(pgDataSource);
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .dimensions(dimensions)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.HNSW)
                .initializeSchema(true)
                .build();
    }
}
