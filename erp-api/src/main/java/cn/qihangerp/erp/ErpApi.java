package cn.qihangerp.erp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@ComponentScan(basePackages={"cn.qihangerp"})
@SpringBootApplication(exclude = {
    org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration.class
})
@EnableScheduling
public class ErpApi {
    public static void main( String[] args )
    {
        System.out.println( "Hello erp-api!" );
        SpringApplication.run(ErpApi.class, args);

    }
}
