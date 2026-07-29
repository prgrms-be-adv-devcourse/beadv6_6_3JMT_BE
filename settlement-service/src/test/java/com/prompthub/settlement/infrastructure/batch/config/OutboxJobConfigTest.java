package com.prompthub.settlement.infrastructure.batch.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.prompthub.settlement.infrastructure.batch.listener.SettlementBatchStateJobExecutionListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.AbstractJob;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;

class OutboxJobConfigTest {

    @Test
    @DisplayName("settlementJob은 원천 대사와 계산 대사를 포함한 Step을 모두 등록한다")
    void settlementJob_hasExpectedSteps() {
        // given
        SettlementJobConfig config = new SettlementJobConfig(mock(JobRepository.class));

        // when
        Job job = config.settlementJob(
                mock(SettlementBatchStateJobExecutionListener.class),
                step("createSettlementBatchStep"),
                step("retryPendingOutboxStep"),
                step("loadSettlementSourceStep"),
                step("reconcileSettlementSourceStep"),
                step("settlementStep"),
                step("reconcileSettlementCalculationStep"),
                step("completeSettlementBatchStep"),
                step("flushCurrentBatchOutboxStep"));

        // then
        assertThat(((AbstractJob) job).getStepNames()).containsExactlyInAnyOrder(
                "createSettlementBatchStep",
                "retryPendingOutboxStep",
                "loadSettlementSourceStep",
                "reconcileSettlementSourceStep",
                "settlementStep",
                "reconcileSettlementCalculationStep",
                "completeSettlementBatchStep",
                "flushCurrentBatchOutboxStep");
    }

    @Test
    @DisplayName("outboxRedriveJob은 지정 이벤트를 재처리하는 Step 하나만 실행한다")
    void outboxRedriveJob_hasOnlyRedriveStep() {
        // given
        OutboxRedriveJobConfig config = new OutboxRedriveJobConfig(mock(JobRepository.class));

        // when
        Job job = config.outboxRedriveJob(step("redriveOutboxStep"));

        // then
        assertThat(job.getName()).isEqualTo(OutboxRedriveJobConfig.OUTBOX_REDRIVE_JOB_NAME);
        assertThat(((AbstractJob) job).getStepNames()).containsExactly("redriveOutboxStep");
    }

    private Step step(String name) {
        Step step = mock(Step.class);
        given(step.getName()).willReturn(name);
        return step;
    }
}
