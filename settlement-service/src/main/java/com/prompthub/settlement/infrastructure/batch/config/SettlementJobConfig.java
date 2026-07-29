package com.prompthub.settlement.infrastructure.batch.config;

import com.prompthub.settlement.infrastructure.batch.listener.SettlementBatchStateJobExecutionListener;
import com.prompthub.settlement.infrastructure.batch.tasklet.ReconcileSettlementSourceTasklet;
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
		SettlementBatchStateJobExecutionListener settlementBatchStateJobExecutionListener,
		Step createSettlementBatchStep,
		Step retryPendingOutboxStep,
		Step loadSettlementSourceStep,
		Step reconcileSettlementSourceStep,
		Step settlementStep,
		Step completeSettlementBatchStep,
		Step flushCurrentBatchOutboxStep
	) {
		return new JobBuilder(SETTLEMENT_JOB_NAME, jobRepository)
			.listener(settlementBatchStateJobExecutionListener)
			.start(createSettlementBatchStep)
			.next(retryPendingOutboxStep)
			.next(loadSettlementSourceStep)
			.next(reconcileSettlementSourceStep)
			.on(ReconcileSettlementSourceTasklet.RECONCILIATION_FAILED_EXIT_CODE)
			.fail()
			.from(reconcileSettlementSourceStep)
			.on("COMPLETED")
			.to(settlementStep)
			.next(completeSettlementBatchStep)
			.next(flushCurrentBatchOutboxStep)
			.from(reconcileSettlementSourceStep)
			.on("*")
			.fail()
			.end()
			.build();
	}
}
