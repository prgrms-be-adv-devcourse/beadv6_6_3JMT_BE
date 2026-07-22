package com.prompthub.settlement.infrastructure.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;

import com.prompthub.settlement.application.dto.CalculateSettlementCommand;
import com.prompthub.settlement.application.dto.RestartSettlementBatchCommand;
import com.prompthub.settlement.application.dto.RunSettlementJobCommand;
import com.prompthub.settlement.application.dto.SettlementJobResult;
import com.prompthub.settlement.application.port.SettlementEventPublisher;
import com.prompthub.settlement.application.service.SettlementCalculationApplicationService;
import com.prompthub.settlement.application.usecase.LoadSettlementSourceUseCase;
import com.prompthub.settlement.application.usecase.RestartSettlementBatchUseCase;
import com.prompthub.settlement.application.usecase.RunSettlementBatchUseCase;
import com.prompthub.settlement.domain.model.Settlement;
import com.prompthub.settlement.domain.model.SettlementBatch;
import com.prompthub.settlement.domain.model.SettlementOutboxEvent;
import com.prompthub.settlement.domain.model.SettlementPeriod;
import com.prompthub.settlement.domain.model.SettlementSourceLine;
import com.prompthub.settlement.domain.model.enums.OutboxEventStatus;
import com.prompthub.settlement.domain.model.enums.SettlementBatchStatus;
import com.prompthub.settlement.domain.repository.OutboxEventRepository;
import com.prompthub.settlement.infrastructure.persistence.SettlementBatchJpaRepository;
import com.prompthub.settlement.infrastructure.persistence.SettlementJpaRepository;
import com.prompthub.settlement.infrastructure.persistence.SettlementSourceLineJpaRepository;
import com.prompthub.settlement.infrastructure.persistence.outbox.OutboxEventJpaRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
    private SettlementSourceLineJpaRepository sourceLineJpaRepository;

    @Autowired
    private OutboxEventJpaRepository outboxEventJpaRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private EntityManager entityManager;

    @MockitoBean
    private LoadSettlementSourceUseCase loadSettlementSourceUseCase;

    @MockitoBean
    private SettlementEventPublisher settlementEventPublisher;

    @MockitoSpyBean
    private SettlementCalculationApplicationService calculationService;

    @BeforeEach
    void setUp() {
        outboxEventJpaRepository.deleteAll();
        settlementJpaRepository.deleteAll();
        sourceLineJpaRepository.deleteAll();
        settlementBatchJpaRepository.deleteAll();
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
                RunSettlementJobCommand.scheduled(SETTLEMENT_FAILURE_PERIOD));

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
        assertThat(outboxEventJpaRepository.count()).isEqualTo(1);
        assertThat(outboxEventRepository.findPendingBefore(
                LocalDateTime.now().plusDays(1), null, null, 10)).isEmpty();
        then(settlementEventPublisher).shouldHaveNoInteractions();

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
        List<SettlementOutboxEvent> outboxEvents = outboxEventJpaRepository.findAll();
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
        assertThat(outboxEvents)
                .hasSize(3)
                .allMatch(event -> event.getStatus() == OutboxEventStatus.PUBLISHED)
                .extracting(SettlementOutboxEvent::getAggregateId)
                .doesNotHaveDuplicates();
        then(settlementEventPublisher).should(times(3))
                .publish(any(String.class), any(UUID.class), any(String.class));
    }

    @Test
    @DisplayName("소스 적재 실패도 먼저 생성한 같은 배치에서 적재 Step부터 재시작한다")
    void restart_afterSourceLoadFailure_reusesCreatedBatch() {
        saveSourceLines(SOURCE_LOAD_FAILURE_PERIOD, 1);
        given(loadSettlementSourceUseCase.load(SOURCE_LOAD_FAILURE_PERIOD))
                .willThrow(new IllegalStateException("order-service 조회 실패"))
                .willReturn(0);

        SettlementJobResult firstResult = runSettlementBatchUseCase.run(
                RunSettlementJobCommand.scheduled(SOURCE_LOAD_FAILURE_PERIOD));

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
        then(settlementEventPublisher).should().publish(
                any(String.class),
                any(UUID.class),
                any(String.class));
    }

    private SettlementBatch onlyBatch() {
        return settlementBatchJpaRepository.findAll().getFirst();
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
