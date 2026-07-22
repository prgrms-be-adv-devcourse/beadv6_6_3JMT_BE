package com.prompthub.settlement.infrastructure.batch.config;

import com.prompthub.settlement.domain.model.Settlement;
import com.prompthub.settlement.infrastructure.batch.model.SettlementTarget;
import com.prompthub.settlement.infrastructure.batch.processor.SettlementProcessor;
import com.prompthub.settlement.infrastructure.batch.reader.SettlementTargetReader;
import com.prompthub.settlement.infrastructure.batch.tasklet.CompleteSettlementBatchTasklet;
import com.prompthub.settlement.infrastructure.batch.tasklet.CreateSettlementBatchTasklet;
import com.prompthub.settlement.infrastructure.batch.tasklet.FlushCurrentBatchOutboxTasklet;
import com.prompthub.settlement.infrastructure.batch.tasklet.LoadSettlementSourceTasklet;
import com.prompthub.settlement.infrastructure.batch.tasklet.RedriveOutboxTasklet;
import com.prompthub.settlement.infrastructure.batch.tasklet.RetryPendingOutboxTasklet;
import com.prompthub.settlement.infrastructure.batch.writer.SettlementWriter;
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
	public Step retryPendingOutboxStep(RetryPendingOutboxTasklet retryPendingOutboxTasklet) {
		return new StepBuilder("retryPendingOutboxStep", jobRepository)
			.tasklet(retryPendingOutboxTasklet, transactionManager)
			.build();
	}

	@Bean
	public Step loadSettlementSourceStep(LoadSettlementSourceTasklet loadSettlementSourceTasklet) {
		return new StepBuilder("loadSettlementSourceStep", jobRepository)
			.tasklet(loadSettlementSourceTasklet, transactionManager)
			.build();
	}

	@Bean
	public Step createSettlementBatchStep(CreateSettlementBatchTasklet createSettlementBatchTasklet) {
		return new StepBuilder("createSettlementBatchStep", jobRepository)
			.tasklet(createSettlementBatchTasklet, transactionManager)
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
			.build();
	}

	@Bean
	public Step completeSettlementBatchStep(CompleteSettlementBatchTasklet completeSettlementBatchTasklet) {
		return new StepBuilder("completeSettlementBatchStep", jobRepository)
			.tasklet(completeSettlementBatchTasklet, transactionManager)
			.build();
	}

	@Bean
	public Step flushCurrentBatchOutboxStep(
		FlushCurrentBatchOutboxTasklet flushCurrentBatchOutboxTasklet
	) {
		return new StepBuilder("flushCurrentBatchOutboxStep", jobRepository)
			.tasklet(flushCurrentBatchOutboxTasklet, transactionManager)
			.build();
	}

	@Bean
	public Step redriveOutboxStep(RedriveOutboxTasklet redriveOutboxTasklet) {
		return new StepBuilder("redriveOutboxStep", jobRepository)
			.tasklet(redriveOutboxTasklet, transactionManager)
			.build();
	}
}
