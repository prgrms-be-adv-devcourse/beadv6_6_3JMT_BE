package com.prompthub.settlement.infrastructure.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.prompthub.settlement.application.dto.RunSettlementBatchCommand;
import com.prompthub.settlement.application.dto.SettlementJobResult;
import com.prompthub.settlement.application.dto.SettlementSourceReconciliationResult;
import com.prompthub.settlement.application.port.SellerSettlementRegistrationPort;
import com.prompthub.settlement.application.usecase.LoadSettlementSourceUseCase;
import com.prompthub.settlement.application.usecase.ReconcileSettlementSourceUseCase;
import com.prompthub.settlement.application.usecase.RunSettlementBatchUseCase;
import com.prompthub.settlement.domain.model.SettlementBatch;
import com.prompthub.settlement.domain.model.SettlementPeriod;
import com.prompthub.settlement.domain.model.enums.SettlementBatchStatus;
import com.prompthub.settlement.domain.repository.SettlementSourceAggregate;
import com.prompthub.settlement.infrastructure.batch.tasklet.ReconcileSettlementSourceTasklet;
import com.prompthub.settlement.infrastructure.persistence.SettlementBatchJpaRepository;
import com.prompthub.settlement.infrastructure.persistence.SettlementJpaRepository;
import com.prompthub.settlement.infrastructure.persistence.SettlementSourceLineJpaRepository;
import com.prompthub.settlement.infrastructure.persistence.delivery.SettlementDeliveryJpaRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
    "spring.cloud.config.enabled=false",
    "spring.cloud.config.fail-fast=false",
    "settlement.execution.mode=service"
})
@ActiveProfiles("test")
class SettlementSourceReconciliationBatchIntegrationTest {

    private static final SettlementPeriod PERIOD = SettlementPeriod.of(
            LocalDate.of(2030, 1, 21),
            LocalDate.of(2030, 1, 27));
    private static final SettlementPeriod TECHNICAL_FAILURE_PERIOD = SettlementPeriod.of(
            LocalDate.of(2030, 1, 28),
            LocalDate.of(2030, 2, 3));

    @Autowired
    private RunSettlementBatchUseCase runSettlementBatchUseCase;

    @Autowired
    private SettlementBatchJpaRepository settlementBatchJpaRepository;

    @Autowired
    private SettlementJpaRepository settlementJpaRepository;

    @Autowired
    private SettlementSourceLineJpaRepository sourceLineJpaRepository;

    @Autowired
    private SettlementDeliveryJpaRepository settlementDeliveryJpaRepository;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private EntityManager entityManager;

    @MockitoBean
    private LoadSettlementSourceUseCase loadSettlementSourceUseCase;

    @MockitoBean
    private ReconcileSettlementSourceUseCase reconcileSettlementSourceUseCase;

    @MockitoBean
    private SellerSettlementRegistrationPort sellerSettlementRegistrationPort;

    @BeforeEach
    void setUp() {
        settlementDeliveryJpaRepository.deleteAll();
        settlementJpaRepository.deleteAll();
        sourceLineJpaRepository.deleteAll();
        settlementBatchJpaRepository.deleteAll();
    }

    @Test
    @DisplayName("원천 집계가 불일치하면 대사 결과를 보존하고 정산 계산 전에 배치를 실패시킨다")
    void mismatch_stopsBeforeCalculationAndPersistsStepContext() {
        SettlementSourceAggregate orderAggregate = new SettlementSourceAggregate(
                2L,
                new BigDecimal("3000.00"),
                1L,
                new BigDecimal("500.00"));
        SettlementSourceAggregate sourceAggregate = new SettlementSourceAggregate(
                1L,
                new BigDecimal("1000.00"),
                1L,
                new BigDecimal("500.00"));
        SettlementSourceReconciliationResult mismatch =
                SettlementSourceReconciliationResult.compare(orderAggregate, sourceAggregate);
        given(reconcileSettlementSourceUseCase.reconcile(PERIOD)).willReturn(mismatch);

        SettlementJobResult result = runSettlementBatchUseCase.run(
                RunSettlementBatchCommand.scheduled(PERIOD));

        entityManager.clear();
        SettlementBatch batch = settlementBatchJpaRepository.findAll().getFirst();
        JobExecution jobExecution = jobRepository.getJobExecution(result.jobExecutionId());
        StepExecution reconciliationStep = jobExecution.getStepExecutions().stream()
                .filter(step -> "reconcileSettlementSourceStep".equals(step.getStepName()))
                .findFirst()
                .orElseThrow();
        ExecutionContext context = reconciliationStep.getExecutionContext();

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(batch.getStatus()).isEqualTo(SettlementBatchStatus.RECONCILIATION_FAILED);
        assertThat(batch.getFailureReason()).isEqualTo(mismatch.failureReason());
        assertThat(batch.getExecutedAt()).isNotNull();
        assertThat(settlementJpaRepository.count()).isZero();
        assertThat(settlementDeliveryJpaRepository.count()).isZero();
        assertThat(reconciliationStep.getExitStatus().getExitCode())
                .isEqualTo(ReconcileSettlementSourceTasklet.RECONCILIATION_FAILED_EXIT_CODE);
        assertThat(context.getString("reconciliationStatus")).isEqualTo("MISMATCHED");
        assertThat(context.getLong("orderPaidCount")).isEqualTo(2L);
        assertThat(context.getString("orderPaidAmount")).isEqualTo("3000");
        assertThat(context.getLong("sourcePaidCount")).isEqualTo(1L);
        assertThat(context.getString("sourcePaidAmount")).isEqualTo("1000");
        assertThat(context.getLong("orderRefundCount")).isEqualTo(1L);
        assertThat(context.getString("orderRefundAmount")).isEqualTo("500");
        assertThat(context.getLong("sourceRefundCount")).isEqualTo(1L);
        assertThat(context.getString("sourceRefundAmount")).isEqualTo("500");
        then(reconcileSettlementSourceUseCase).should().reconcile(PERIOD);
        then(sellerSettlementRegistrationPort).shouldHaveNoInteractions();
        then(loadSettlementSourceUseCase).should().load(any(SettlementPeriod.class));
    }

    @Test
    @DisplayName("대사 중 기술 오류가 발생하면 기존 FAILED 상태로 계산 전에 종료한다")
    void technicalFailure_stopsBeforeCalculationWithFailedStatus() {
        given(reconcileSettlementSourceUseCase.reconcile(TECHNICAL_FAILURE_PERIOD))
                .willThrow(new IllegalStateException("order-service 조회 실패"));

        SettlementJobResult result = runSettlementBatchUseCase.run(
                RunSettlementBatchCommand.scheduled(TECHNICAL_FAILURE_PERIOD));

        entityManager.clear();
        SettlementBatch batch = settlementBatchJpaRepository.findAll().getFirst();
        JobExecution jobExecution = jobRepository.getJobExecution(result.jobExecutionId());
        StepExecution reconciliationStep = jobExecution.getStepExecutions().stream()
                .filter(step -> "reconcileSettlementSourceStep".equals(step.getStepName()))
                .findFirst()
                .orElseThrow();

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(batch.getStatus()).isEqualTo(SettlementBatchStatus.FAILED);
        assertThat(batch.getFailureReason()).contains("order-service 조회 실패");
        assertThat(settlementJpaRepository.count()).isZero();
        assertThat(settlementDeliveryJpaRepository.count()).isZero();
        assertThat(reconciliationStep.getExitStatus().getExitCode())
                .isEqualTo("FAILED");
        then(sellerSettlementRegistrationPort).shouldHaveNoInteractions();
    }
}
