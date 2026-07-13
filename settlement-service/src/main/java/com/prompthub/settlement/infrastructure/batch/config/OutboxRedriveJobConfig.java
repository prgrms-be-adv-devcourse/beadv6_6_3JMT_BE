package com.prompthub.settlement.infrastructure.batch.config;

import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class OutboxRedriveJobConfig {

    public static final String OUTBOX_REDRIVE_JOB_NAME = "outboxRedriveJob";

    private final JobRepository jobRepository;

    @Bean
    public Job outboxRedriveJob(Step redriveOutboxStep) {
        return new JobBuilder(OUTBOX_REDRIVE_JOB_NAME, jobRepository)
                .start(redriveOutboxStep)
                .build();
    }
}
