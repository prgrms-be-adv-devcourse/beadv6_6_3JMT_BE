package com.prompthub.settlement.infrastructure.batch.config;

import com.prompthub.settlement.infrastructure.batch.listener.SettlementBatchFailureListener;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class SettlementJobConfig {

	public static final String SETTLEMENT_JOB_NAME = "settlementJob";

	private final JobRepository jobRepository;

	@Bean
	public Job settlementJob(
		Step createSettlementBatchStep,
		Step settlementStep,
		Step completeSettlementBatchStep,
		SettlementBatchFailureListener settlementBatchFailureListener
	) {
		return new JobBuilder(SETTLEMENT_JOB_NAME, jobRepository)
			.listener(settlementBatchFailureListener)
			.start(createSettlementBatchStep)
			.next(settlementStep)
			.next(completeSettlementBatchStep)
			.build();
	}
}
