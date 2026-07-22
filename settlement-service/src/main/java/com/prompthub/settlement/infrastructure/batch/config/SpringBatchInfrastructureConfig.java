package com.prompthub.settlement.infrastructure.batch.config;

import org.springframework.batch.core.configuration.JobRegistry;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.configuration.annotation.EnableJdbcJobRepository;
import org.springframework.batch.core.configuration.support.MapJobRegistry;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.support.TaskExecutorJobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.jdbc.datasource.init.DataSourceInitializer;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;

@Configuration
@EnableBatchProcessing
@EnableJdbcJobRepository(dataSourceRef = "dataSource", transactionManagerRef = "transactionManager")
public class SpringBatchInfrastructureConfig {

    public static final String ASYNC_JOB_OPERATOR = "asyncSettlementJobOperator";

    /**
     * Spring Batch 6 은 기본이 ResourcelessJobRepository(in-memory)라 잡 메타데이터를 영속하지 않는다.
     * @EnableJdbcJobRepository 로 JDBC 영속 저장소를 켰지만, @EnableBatchProcessing 이 Spring Boot 의
     * batch 스키마 자동초기화를 backoff 시키므로 메타 테이블(BATCH_JOB_*, BATCH_STEP_*) 생성을 여기서 직접 한다.
     * 없으면 생성하고 있으면 무시한다.
     */
    @Bean
    public DataSourceInitializer batchSchemaInitializer(DataSource dataSource) {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.addScript(new ClassPathResource("org/springframework/batch/core/schema-postgresql.sql"));
        populator.setContinueOnError(true);
        DataSourceInitializer initializer = new DataSourceInitializer();
        initializer.setDataSource(dataSource);
        initializer.setDatabasePopulator(populator);
        return initializer;
    }

    @Bean
    public JobRegistry jobRegistry() {
        return new MapJobRegistry();
    }

    @Bean(ASYNC_JOB_OPERATOR)
    public JobOperator asyncSettlementJobOperator(JobRepository jobRepository, JobRegistry jobRegistry)
            throws Exception {
        TaskExecutorJobOperator operator = new TaskExecutorJobOperator();
        operator.setJobRepository(jobRepository);
        operator.setJobRegistry(jobRegistry);
        operator.setTaskExecutor(new SimpleAsyncTaskExecutor("settlement-job-"));
        operator.afterPropertiesSet();
        return operator;
    }
}
