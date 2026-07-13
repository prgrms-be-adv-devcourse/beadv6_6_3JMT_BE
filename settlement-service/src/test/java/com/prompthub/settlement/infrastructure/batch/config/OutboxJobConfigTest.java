package com.prompthub.settlement.infrastructure.batch.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.prompthub.settlement.infrastructure.batch.listener.SettlementBatchFailureListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.AbstractJob;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;

class OutboxJobConfigTest {

    @Test
    @DisplayName("settlementJob은 과거 retry부터 현재 배치 flush까지 정해진 순서로 실행한다")
    void settlementJob_hasExpectedStepOrder() {
        // given
        SettlementJobConfig config = new SettlementJobConfig(mock(JobRepository.class));

        // when
        Job job = config.settlementJob(
                step("retryPendingOutboxStep"),
                step("loadSettlementSourceStep"),
                step("createSettlementBatchStep"),
                step("settlementStep"),
                step("completeSettlementBatchStep"),
                step("flushCurrentBatchOutboxStep"),
                mock(SettlementBatchFailureListener.class));

        // then
        assertThat(((AbstractJob) job).getStepNames()).containsExactly(
                "retryPendingOutboxStep",
                "loadSettlementSourceStep",
                "createSettlementBatchStep",
                "settlementStep",
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
