package cn.qihangerp.erp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@ComponentScan(basePackages={"cn.qihangerp"})
@SpringBootApplication(excludeName = {
        // 排除Spring AI的PGVector自动配置：它默认用主DataSource(MySQL)且无法配独立PG连接。
        // 由PgVectorManualConfig接管，并用agent.vector-store.enabled开关控制（关闭时不依赖PostgreSQL）。
        "org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration"
})
@EnableScheduling
public class ErpApi {
    public static void main( String[] args )
    {
        System.out.println( "Hello erp-api!" );
        SpringApplication.run(ErpApi.class, args);

    }
}
