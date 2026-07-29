package com.prompthub.settlement.infrastructure.batch.tasklet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import com.prompthub.settlement.application.dto.CreateSettlementBatchCommand;
import com.prompthub.settlement.application.usecase.SettlementBatchLifecycleUseCase;
import com.prompthub.settlement.domain.model.enums.TriggerType;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.test.util.ReflectionTestUtils;

class CreateSettlementBatchTaskletTest {

    private static final UUID BATCH_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000303");

    @Test
    void execute_createsWeeklyBatchAndStoresItsId() throws Exception {
        SettlementBatchLifecycleUseCase lifecycleUseCase = mock(SettlementBatchLifecycleUseCase.class);
        given(lifecycleUseCase.create(org.mockito.ArgumentMatchers.any()))
                .willReturn(BATCH_ID);
        JobExecution jobExecution = new JobExecution(
                42L, new JobInstance(1L, "settlementJob"), new JobParameters());
        StepExecution stepExecution = new StepExecution(2L, "createSettlementBatchStep", jobExecution);
        ChunkContext chunkContext = new ChunkContext(new StepContext(stepExecution));
        CreateSettlementBatchTasklet tasklet = new CreateSettlementBatchTasklet(lifecycleUseCase);
        ReflectionTestUtils.setField(tasklet, "periodStartParam", "2026-07-13");
        ReflectionTestUtils.setField(tasklet, "periodEndParam", "2026-07-19");
        ReflectionTestUtils.setField(tasklet, "triggerTypeParam", "SCHEDULED");

        tasklet.execute(null, chunkContext);

        ArgumentCaptor<CreateSettlementBatchCommand> captor =
                ArgumentCaptor.forClass(CreateSettlementBatchCommand.class);
        then(lifecycleUseCase).should().create(captor.capture());
        CreateSettlementBatchCommand command = captor.getValue();
        assertThat(command.batchNo()).isEqualTo("SETTLE-20260713-20260719-SCHEDULED-42");
        assertThat(command.jobInstanceId()).isEqualTo(1L);
        assertThat(command.period().periodStart()).isEqualTo(LocalDate.of(2026, 7, 13));
        assertThat(command.period().periodEnd()).isEqualTo(LocalDate.of(2026, 7, 19));
        assertThat(command.triggerType()).isEqualTo(TriggerType.SCHEDULED);
        assertThat(jobExecution.getExecutionContext().getString("settlementBatchId"))
                .isEqualTo(BATCH_ID.toString());
    }
}
