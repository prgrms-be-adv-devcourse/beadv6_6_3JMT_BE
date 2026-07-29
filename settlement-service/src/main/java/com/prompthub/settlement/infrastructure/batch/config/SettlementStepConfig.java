package com.prompthub.settlement.infrastructure.batch.config;

import com.prompthub.settlement.domain.model.Settlement;
import com.prompthub.settlement.infrastructure.batch.settlement.SettlementProcessor;
import com.prompthub.settlement.infrastructure.batch.settlement.SettlementTarget;
import com.prompthub.settlement.infrastructure.batch.settlement.SettlementTargetReader;
import com.prompthub.settlement.infrastructure.batch.settlement.SettlementWriter;
import com.prompthub.settlement.infrastructure.batch.tasklet.CompleteSettlementBatchTasklet;
import com.prompthub.settlement.infrastructure.batch.tasklet.CreateSettlementBatchTasklet;
import com.prompthub.settlement.infrastructure.batch.tasklet.DeliverSellerSettlementsTasklet;
import com.prompthub.settlement.infrastructure.batch.tasklet.LoadSettlementSourceTasklet;
import com.prompthub.settlement.infrastructure.batch.tasklet.ReconcileSettlementCalculationTasklet;
import com.prompthub.settlement.infrastructure.batch.tasklet.ReconcileSettlementSourceTasklet;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class SettlementStepConfig {

	private final JobRepository jobRepository;
	private final PlatformTransactionManager transactionManager;
	private final int chunkSize;

	public SettlementStepConfig(
		JobRepository jobRepository,
		PlatformTransactionManager transactionManager,
		@Value("${settlement.batch.chunk-size:100}") int chunkSize
	) {
		if (chunkSize <= 0) {
			throw new IllegalArgumentException("settlement.batch.chunk-size는 1 이상이어야 합니다.");
		}
		this.jobRepository = jobRepository;
		this.transactionManager = transactionManager;
		this.chunkSize = chunkSize;
	}

	@Bean
	public Step createSettlementBatchStep(CreateSettlementBatchTasklet createSettlementBatchTasklet) {
		return new StepBuilder("createSettlementBatchStep", jobRepository)
			.tasklet(createSettlementBatchTasklet, transactionManager)
			.build();
	}

	@Bean
	public Step loadSettlementSourceStep(LoadSettlementSourceTasklet loadSettlementSourceTasklet) {
		return new StepBuilder("loadSettlementSourceStep", jobRepository)
			.tasklet(loadSettlementSourceTasklet, transactionManager)
			.build();
	}

	@Bean
	public Step reconcileSettlementSourceStep(
		ReconcileSettlementSourceTasklet reconcileSettlementSourceTasklet
	) {
		return new StepBuilder("reconcileSettlementSourceStep", jobRepository)
			.tasklet(reconcileSettlementSourceTasklet, transactionManager)
			.build();
	}

	@Bean
	public Step settlementStep(
		SettlementTargetReader settlementTargetReader,
		SettlementProcessor settlementProcessor,
		SettlementWriter settlementWriter
	) {
		return new StepBuilder("settlementStep", jobRepository)
			.<SettlementTarget, Settlement>chunk(chunkSize)
			.reader(settlementTargetReader)
			.processor(settlementProcessor)
			.writer(settlementWriter)
			.transactionManager(transactionManager)
			.allowStartIfComplete(true)
			.build();
	}

	@Bean
	public Step reconcileSettlementCalculationStep(
		ReconcileSettlementCalculationTasklet reconcileSettlementCalculationTasklet
	) {
		return new StepBuilder("reconcileSettlementCalculationStep", jobRepository)
			.tasklet(reconcileSettlementCalculationTasklet, transactionManager)
			.build();
	}

	@Bean
	public Step completeSettlementBatchStep(CompleteSettlementBatchTasklet completeSettlementBatchTasklet) {
		return new StepBuilder("completeSettlementBatchStep", jobRepository)
			.tasklet(completeSettlementBatchTasklet, transactionManager)
			.build();
	}

	@Bean
	public Step deliverSellerSettlementsStep(
		DeliverSellerSettlementsTasklet deliverSellerSettlementsTasklet
	) {
		return new StepBuilder("deliverSellerSettlementsStep", jobRepository)
			.tasklet(deliverSellerSettlementsTasklet, transactionManager)
			.build();
	}
}
