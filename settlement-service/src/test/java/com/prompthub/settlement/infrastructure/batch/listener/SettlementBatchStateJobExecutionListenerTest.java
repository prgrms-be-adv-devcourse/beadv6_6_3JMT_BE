package com.prompthub.settlement.infrastructure.batch.listener;

import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import com.prompthub.settlement.application.usecase.SettlementBatchLifecycleUseCase;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;

class SettlementBatchStateJobExecutionListenerTest {

    private SettlementBatchLifecycleUseCase lifecycleUseCase;
    private SettlementBatchStateJobExecutionListener listener;

    @BeforeEach
    void setUp() {
        lifecycleUseCase = mock(SettlementBatchLifecycleUseCase.class);
        listener = new SettlementBatchStateJobExecutionListener(lifecycleUseCase);
    }

    @Test
    @DisplayName("재시작 실행 전 context의 배치 재시작을 생명주기 UseCase에 요청한다")
    void beforeJob_withBatchContext_startsRetry() {
        UUID batchId = UUID.randomUUID();

        listener.beforeJob(execution(BatchStatus.STARTING, batchId));

        then(lifecycleUseCase).should().startRetry(batchId);
    }

    @Test
    @DisplayName("최초 실행처럼 배치 context가 없으면 beforeJob은 상태를 바꾸지 않는다")
    void beforeJob_withoutBatchContext_doesNothing() {
        listener.beforeJob(executionWithoutBatchContext(BatchStatus.STARTING));

        then(lifecycleUseCase).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("Job 실패 사유를 배치 생명주기 UseCase에 전달한다")
    void afterJob_failedJob_marksFailed() {
        UUID batchId = UUID.randomUUID();
        JobExecution execution = execution(BatchStatus.FAILED, batchId);
        execution.addFailureException(new IllegalStateException("DB 연결 실패"));

        listener.afterJob(execution);

        then(lifecycleUseCase).should().fail(batchId, "DB 연결 실패");
    }

    @Test
    @DisplayName("실패 예외가 없으면 기본 실패 사유를 전달한다")
    void afterJob_withoutFailureException_usesDefaultReason() {
        UUID batchId = UUID.randomUUID();

        listener.afterJob(execution(BatchStatus.FAILED, batchId));

        then(lifecycleUseCase).should().fail(batchId, "정산 배치 실행 실패");
    }

    @Test
    @DisplayName("정상 완료한 Job은 배치 실패 처리를 하지 않는다")
    void afterJob_completedJob_doesNothing() {
        listener.afterJob(execution(BatchStatus.COMPLETED, UUID.randomUUID()));

        then(lifecycleUseCase).shouldHaveNoInteractions();
    }

    private JobExecution execution(BatchStatus status, UUID batchId) {
        JobExecution execution = executionWithoutBatchContext(status);
        execution.getExecutionContext().putString("settlementBatchId", batchId.toString());
        return execution;
    }

    private JobExecution executionWithoutBatchContext(BatchStatus status) {
        JobExecution execution = new JobExecution(
                1L,
                new JobInstance(1L, "settlementJob"),
                new JobParameters());
        execution.setStatus(status);
        return execution;
    }
}
