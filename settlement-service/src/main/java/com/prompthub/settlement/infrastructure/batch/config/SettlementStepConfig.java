package com.prompthub.settlement.infrastructure.batch.config;

import com.prompthub.settlement.domain.model.Settlement;
import com.prompthub.settlement.infrastructure.batch.model.SettlementTarget;
import com.prompthub.settlement.infrastructure.batch.processor.SettlementProcessor;
import com.prompthub.settlement.infrastructure.batch.reader.SettlementTargetReader;
import com.prompthub.settlement.infrastructure.batch.tasklet.CompleteSettlementBatchTasklet;
import com.prompthub.settlement.infrastructure.batch.tasklet.CreateSettlementBatchTasklet;
import com.prompthub.settlement.infrastructure.batch.writer.SettlementWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@RequiredArgsConstructor
public class SettlementStepConfig {

	private static final int CHUNK_SIZE = 100;

	private final JobRepository jobRepository;
	private final PlatformTransactionManager transactionManager;

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
			.<SettlementTarget, Settlement>chunk(CHUNK_SIZE)
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
}
