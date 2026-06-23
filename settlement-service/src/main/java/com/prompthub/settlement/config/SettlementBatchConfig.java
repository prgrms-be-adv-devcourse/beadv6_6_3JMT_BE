package com.prompthub.settlement.config;

import org.springframework.batch.core.configuration.JobRegistry;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.support.TaskExecutorJobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

@Configuration
public class SettlementBatchConfig {

    public static final String ASYNC_JOB_OPERATOR = "asyncSettlementJobOperator";

    /**
     * 기본(동기) JobOperator. start() 가 잡이 끝날 때까지 블로킹한다.
     * 스케줄 실행처럼 호출 스레드를 붙잡아도 되는 경우에 쓴다.
     */
    @Bean
    @Primary
    public JobOperator jobOperator(JobRepository jobRepository, JobRegistry jobRegistry) throws Exception {
        TaskExecutorJobOperator operator = new TaskExecutorJobOperator();
        operator.setJobRepository(jobRepository);
        operator.setJobRegistry(jobRegistry);
        operator.afterPropertiesSet();
        return operator;
    }

    /**
     * 비동기 JobOperator. 별도 스레드에 잡을 던지고 start() 는 즉시 반환한다.
     * 반환된 JobExecution 은 아직 실행 중(STARTING/STARTED)이며, 완료 여부는 별도로 조회한다.
     * 수동(HTTP) 실행처럼 요청 스레드를 오래 붙잡으면 안 되는 경우에 쓴다.
     */
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
