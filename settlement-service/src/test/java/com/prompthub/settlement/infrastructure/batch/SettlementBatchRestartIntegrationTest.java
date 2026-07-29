package com.prompthub.settlement.infrastructure.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;

import com.prompthub.settlement.application.dto.CalculateSettlementCommand;
import com.prompthub.settlement.application.dto.RestartSettlementBatchCommand;
import com.prompthub.settlement.application.dto.RunSettlementBatchCommand;
import com.prompthub.settlement.application.dto.SellerSettlementRegistrationCommand;
import com.prompthub.settlement.application.dto.SellerSettlementStoredSnapshot;
import com.prompthub.settlement.application.dto.SettlementJobResult;
import com.prompthub.settlement.application.dto.SettlementSourceReconciliationResult;
import com.prompthub.settlement.application.port.SellerSettlementRegistrationPort;
import com.prompthub.settlement.application.service.SettlementCalculationApplicationService;
import com.prompthub.settlement.application.usecase.LoadSettlementSourceUseCase;
import com.prompthub.settlement.application.usecase.ReconcileSettlementSourceUseCase;
import com.prompthub.settlement.application.usecase.RestartSettlementBatchUseCase;
import com.prompthub.settlement.application.usecase.RunSettlementBatchUseCase;
import com.prompthub.settlement.domain.model.Settlement;
import com.prompthub.settlement.domain.model.SettlementBatch;
import com.prompthub.settlement.domain.model.SettlementCalculationReconciliation;
import com.prompthub.settlement.domain.model.SettlementDelivery;
import com.prompthub.settlement.domain.model.SettlementPeriod;
import com.prompthub.settlement.domain.model.SettlementSourceLine;
import com.prompthub.settlement.domain.model.enums.SettlementBatchStatus;
import com.prompthub.settlement.domain.model.enums.SettlementCalculationReconciliationStatus;
import com.prompthub.settlement.domain.model.enums.SettlementDeliveryStatus;
import com.prompthub.settlement.domain.repository.SettlementSourceAggregate;
import com.prompthub.settlement.infrastructure.persistence.SettlementBatchJpaRepository;
import com.prompthub.settlement.infrastructure.persistence.SettlementCalculationReconciliationJpaRepository;
import com.prompthub.settlement.infrastructure.persistence.SettlementJpaRepository;
import com.prompthub.settlement.infrastructure.persistence.SettlementSourceLineJpaRepository;
import com.prompthub.settlement.infrastructure.persistence.delivery.SettlementDeliveryJpaRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;

@SpringBootTest(properties = {
    "spring.cloud.config.enabled=false",
    "spring.cloud.config.fail-fast=false",
    "settlement.execution.mode=service",
    "settlement.batch.chunk-size=1"
})
@ActiveProfiles("test")
class SettlementBatchRestartIntegrationTest {

    private static final SettlementPeriod SETTLEMENT_FAILURE_PERIOD = SettlementPeriod.of(
            LocalDate.of(2030, 1, 7),
            LocalDate.of(2030, 1, 13));
    private static final SettlementPeriod SOURCE_LOAD_FAILURE_PERIOD = SettlementPeriod.of(
            LocalDate.of(2030, 1, 14),
            LocalDate.of(2030, 1, 20));
    private static final SettlementPeriod RECONCILIATION_FAILURE_PERIOD = SettlementPeriod.of(
            LocalDate.of(2030, 2, 4),
            LocalDate.of(2030, 2, 10));
    private static final UUID ACTOR_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000601");

    @Autowired
    private RunSettlementBatchUseCase runSettlementBatchUseCase;

    @Autowired
    private RestartSettlementBatchUseCase restartSettlementBatchUseCase;

    @Autowired
    private SettlementBatchJpaRepository settlementBatchJpaRepository;

    @Autowired
    private SettlementJpaRepository settlementJpaRepository;

    @Autowired
    private SettlementCalculationReconciliationJpaRepository reconciliationJpaRepository;

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

    @MockitoSpyBean
    private SettlementCalculationApplicationService calculationService;

    @BeforeEach
    void setUp() {
        settlementDeliveryJpaRepository.deleteAll();
        reconciliationJpaRepository.deleteAll();
        settlementJpaRepository.deleteAll();
        sourceLineJpaRepository.deleteAll();
        settlementBatchJpaRepository.deleteAll();
        SettlementSourceAggregate emptyAggregate = SettlementSourceAggregate.zero();
        given(reconcileSettlementSourceUseCase.reconcile(any(SettlementPeriod.class)))
                .willReturn(SettlementSourceReconciliationResult.compare(
                        emptyAggregate,
                        emptyAggregate));
        given(sellerSettlementRegistrationPort.register(
                any(SellerSettlementRegistrationCommand.class)))
                .willAnswer(invocation -> snapshotOf(invocation.getArgument(0)));
    }

    @Test
    @DisplayName("계산 대사 실패 후 같은 배치를 재시작하면 실패 정산을 다시 계산하고 결과를 누적한다")
    void restart_afterReconciliationFailure_recalculatesMismatchedSettlement() {
        saveSourceLines(RECONCILIATION_FAILURE_PERIOD, 1);
        AtomicBoolean corruptCalculation = new AtomicBoolean(true);
        doAnswer(invocation -> {
            Settlement settlement = (Settlement) invocation.callRealMethod();
            if (corruptCalculation.get() && settlement != null) {
                ReflectionTestUtils.setField(
                        settlement,
                        "totalAmount",
                        settlement.getTotalAmount().add(BigDecimal.ONE));
            }
            return settlement;
        }).when(calculationService).calculate(any(CalculateSettlementCommand.class));

        SettlementJobResult firstResult = runSettlementBatchUseCase.run(
                RunSettlementBatchCommand.scheduled(RECONCILIATION_FAILURE_PERIOD));

        entityManager.clear();
        SettlementBatch failedBatch = onlyBatch();
        UUID originalBatchId = failedBatch.getId();
        long originalJobInstanceId = failedBatch.getJobInstanceId();
        assertThat(firstResult.status()).isEqualTo("FAILED");
        assertThat(failedBatch.getStatus())
                .isEqualTo(SettlementBatchStatus.RECONCILIATION_FAILED);
        assertThat(settlementJpaRepository.count()).isZero();
        assertThat(sourceLineJpaRepository.findAll())
                .noneMatch(SettlementSourceLine::isSettled);
        assertThat(settlementDeliveryJpaRepository.count()).isZero();
        assertThat(reconciliationJpaRepository.findAll())
                .singleElement()
                .extracting(SettlementCalculationReconciliation::getStatus)
                .isEqualTo(SettlementCalculationReconciliationStatus.MISMATCHED);
        then(sellerSettlementRegistrationPort).shouldHaveNoInteractions();

        failedBatch.requestRetry();
        settlementBatchJpaRepository.saveAndFlush(failedBatch);
        entityManager.clear();
        corruptCalculation.set(false);

        SettlementJobResult restartedResult = restartSettlementBatchUseCase.restart(
                new RestartSettlementBatchCommand(originalBatchId, ACTOR_ID));

        entityManager.clear();
        SettlementBatch completedBatch =
                settlementBatchJpaRepository.findById(originalBatchId).orElseThrow();
        JobInstance jobInstance = jobRepository.getJobInstance(originalJobInstanceId);
        assertThat(restartedResult.status()).isEqualTo("COMPLETED");
        assertThat(completedBatch.getStatus()).isEqualTo(SettlementBatchStatus.COMPLETED);
        assertThat(jobRepository.getJobExecutions(jobInstance)).hasSize(2);
        assertThat(settlementJpaRepository.findBySettlementBatchId(originalBatchId))
                .hasSize(1);
        assertThat(sourceLineJpaRepository.findAll())
                .allMatch(SettlementSourceLine::isSettled);
        assertThat(settlementDeliveryJpaRepository.findAll())
                .singleElement()
                .extracting(SettlementDelivery::getStatus)
                .isEqualTo(SettlementDeliveryStatus.RECONCILED);
        assertThat(reconciliationJpaRepository
                .findBySettlementBatchIdOrderByVerifiedAtAsc(originalBatchId))
                .extracting(SettlementCalculationReconciliation::getStatus)
                .containsExactly(
                        SettlementCalculationReconciliationStatus.MISMATCHED,
                        SettlementCalculationReconciliationStatus.MATCHED);
        then(calculationService).should(times(2))
                .calculate(any(CalculateSettlementCommand.class));
        then(sellerSettlementRegistrationPort).should()
                .register(any(SellerSettlementRegistrationCommand.class));
    }

    @Test
    @DisplayName("커밋된 청크를 유지하고 같은 배치에서 미처리 청크만 재시작한다")
    void restart_afterCommittedChunk_processesOnlyRemainingSources() {
        saveSourceLines(SETTLEMENT_FAILURE_PERIOD, 3);
        AtomicInteger calculationCount = new AtomicInteger();
        AtomicBoolean failSecondCalculation = new AtomicBoolean(true);
        doAnswer(invocation -> {
            int current = calculationCount.incrementAndGet();
            if (failSecondCalculation.get() && current == 2) {
                throw new IllegalStateException("두 번째 청크 실패");
            }
            return invocation.callRealMethod();
        }).when(calculationService).calculate(any(CalculateSettlementCommand.class));

        SettlementJobResult firstResult = runSettlementBatchUseCase.run(
                RunSettlementBatchCommand.scheduled(SETTLEMENT_FAILURE_PERIOD));

        entityManager.clear();
        SettlementBatch failedBatch = onlyBatch();
        UUID originalBatchId = failedBatch.getId();
        long originalJobInstanceId = failedBatch.getJobInstanceId();
        assertThat(firstResult.status()).isEqualTo("FAILED");
        assertThat(failedBatch.getStatus()).isEqualTo(SettlementBatchStatus.FAILED);
        assertThat(settlementJpaRepository.count()).isEqualTo(1);
        assertThat(sourceLineJpaRepository.findAll())
                .filteredOn(SettlementSourceLine::isSettled)
                .hasSize(1);
        assertThat(settlementDeliveryJpaRepository.count()).isEqualTo(1);
        assertThat(settlementDeliveryJpaRepository.findAll())
                .allMatch(delivery ->
                        delivery.getStatus() == SettlementDeliveryStatus.CALCULATED);
        then(sellerSettlementRegistrationPort).shouldHaveNoInteractions();

        failedBatch.requestRetry();
        settlementBatchJpaRepository.saveAndFlush(failedBatch);
        entityManager.clear();
        failSecondCalculation.set(false);

        SettlementJobResult restartedResult = restartSettlementBatchUseCase.restart(
                new RestartSettlementBatchCommand(originalBatchId, ACTOR_ID));

        entityManager.clear();
        SettlementBatch completedBatch = settlementBatchJpaRepository.findById(originalBatchId)
                .orElseThrow();
        JobInstance jobInstance = jobRepository.getJobInstance(originalJobInstanceId);
        List<Settlement> settlements = settlementJpaRepository.findBySettlementBatchId(originalBatchId);
        List<SettlementSourceLine> sourceLines = sourceLineJpaRepository.findAll();
        List<SettlementDelivery> deliveries = settlementDeliveryJpaRepository.findAll();
        assertThat(restartedResult.status()).isEqualTo("COMPLETED");
        assertThat(completedBatch.getStatus()).isEqualTo(SettlementBatchStatus.COMPLETED);
        assertThat(completedBatch.getJobInstanceId()).isEqualTo(originalJobInstanceId);
        assertThat(jobRepository.getJobExecutions(jobInstance)).hasSize(2);
        assertThat(settlements)
                .hasSize(3)
                .extracting(Settlement::getSellerId)
                .doesNotHaveDuplicates();
        assertThat(sourceLines)
                .hasSize(3)
                .allMatch(SettlementSourceLine::isSettled)
                .extracting(SettlementSourceLine::getSettlementId)
                .doesNotHaveDuplicates();
        assertThat(deliveries)
                .hasSize(3)
                .allMatch(delivery ->
                        delivery.getStatus() == SettlementDeliveryStatus.RECONCILED)
                .extracting(SettlementDelivery::getSettlementId)
                .doesNotHaveDuplicates();
        then(sellerSettlementRegistrationPort).should(times(3))
                .register(any(SellerSettlementRegistrationCommand.class));
    }

    @Test
    @DisplayName("소스 적재 실패도 먼저 생성한 같은 배치에서 적재 Step부터 재시작한다")
    void restart_afterSourceLoadFailure_reusesCreatedBatch() {
        saveSourceLines(SOURCE_LOAD_FAILURE_PERIOD, 1);
        given(loadSettlementSourceUseCase.load(SOURCE_LOAD_FAILURE_PERIOD))
                .willThrow(new IllegalStateException("order-service 조회 실패"))
                .willReturn(0);

        SettlementJobResult firstResult = runSettlementBatchUseCase.run(
                RunSettlementBatchCommand.scheduled(SOURCE_LOAD_FAILURE_PERIOD));

        entityManager.clear();
        SettlementBatch failedBatch = onlyBatch();
        UUID originalBatchId = failedBatch.getId();
        long originalJobInstanceId = failedBatch.getJobInstanceId();
        assertThat(firstResult.status()).isEqualTo("FAILED");
        assertThat(failedBatch.getStatus()).isEqualTo(SettlementBatchStatus.FAILED);
        assertThat(settlementJpaRepository.count()).isZero();

        failedBatch.requestRetry();
        settlementBatchJpaRepository.saveAndFlush(failedBatch);
        entityManager.clear();

        SettlementJobResult restartedResult = restartSettlementBatchUseCase.restart(
                new RestartSettlementBatchCommand(originalBatchId, ACTOR_ID));

        entityManager.clear();
        SettlementBatch completedBatch = settlementBatchJpaRepository.findById(originalBatchId)
                .orElseThrow();
        JobInstance jobInstance = jobRepository.getJobInstance(originalJobInstanceId);
        assertThat(restartedResult.status()).isEqualTo("COMPLETED");
        assertThat(completedBatch.getStatus()).isEqualTo(SettlementBatchStatus.COMPLETED);
        assertThat(settlementBatchJpaRepository.count()).isEqualTo(1);
        assertThat(jobRepository.getJobExecutions(jobInstance)).hasSize(2);
        assertThat(settlementJpaRepository.findBySettlementBatchId(originalBatchId)).hasSize(1);
        then(loadSettlementSourceUseCase).should(times(2)).load(SOURCE_LOAD_FAILURE_PERIOD);
        then(sellerSettlementRegistrationPort).should()
                .register(any(SellerSettlementRegistrationCommand.class));
    }

    private SettlementBatch onlyBatch() {
        return settlementBatchJpaRepository.findAll().getFirst();
    }

    private SellerSettlementStoredSnapshot snapshotOf(
            SellerSettlementRegistrationCommand command) {
        return new SellerSettlementStoredSnapshot(
                command.deliveryRequestId(),
                command.settlementId(),
                command.sellerId(),
                command.periodStart(),
                command.periodEnd(),
                command.productCount(),
                command.grossSalesAmount(),
                command.refundAmount(),
                command.feeTotalAmount(),
                command.settlementTotalAmount(),
                command.calculatedAt(),
                command.details().stream()
                        .map(detail -> new SellerSettlementStoredSnapshot.Detail(
                                detail.settlementDetailId(),
                                detail.orderProductId(),
                                detail.lineType(),
                                detail.lineAmount(),
                                detail.feeRate(),
                                detail.feeAmount(),
                                detail.lineSettlementAmount(),
                                detail.occurredAt()))
                        .toList());
    }

    private void saveSourceLines(SettlementPeriod period, int count) {
        for (int index = 0; index < count; index++) {
            sourceLineJpaRepository.save(SettlementSourceLine.paid(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    new BigDecimal("10000.00"),
                    period.periodStart().atTime(10, index)));
        }
        sourceLineJpaRepository.flush();
    }
}
