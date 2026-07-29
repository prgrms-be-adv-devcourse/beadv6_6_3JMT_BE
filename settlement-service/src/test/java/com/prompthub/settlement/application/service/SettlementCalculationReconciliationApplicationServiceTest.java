package com.prompthub.settlement.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;

import com.prompthub.settlement.application.dto.SettlementCalculationReconciliationReport;
import com.prompthub.settlement.domain.model.Settlement;
import com.prompthub.settlement.domain.model.SettlementCalculationReconciliation;
import com.prompthub.settlement.domain.model.SettlementDetail;
import com.prompthub.settlement.domain.model.SettlementPeriod;
import com.prompthub.settlement.domain.model.SettlementSourceLine;
import com.prompthub.settlement.domain.model.enums.SettlementCalculationReconciliationStatus;
import com.prompthub.settlement.domain.repository.SettlementCalculationReconciliationRepository;
import com.prompthub.settlement.domain.repository.SettlementDeliveryRepository;
import com.prompthub.settlement.domain.repository.SettlementRepository;
import com.prompthub.settlement.domain.repository.SettlementSourceRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class SettlementCalculationReconciliationApplicationServiceTest {

    private static final UUID BATCH_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final SettlementPeriod PERIOD = SettlementPeriod.of(
            LocalDate.of(2026, 7, 13),
            LocalDate.of(2026, 7, 19));

    @Mock
    private SettlementRepository settlementRepository;

    @Mock
    private SettlementSourceRepository sourceRepository;

    @Mock
    private SettlementCalculationReconciliationRepository reconciliationRepository;

    @Mock
    private SettlementDeliveryRepository deliveryRepository;

    private SettlementCalculationReconciliationApplicationService service;

    @BeforeEach
    void setUp() {
        service = new SettlementCalculationReconciliationApplicationService(
                settlementRepository,
                sourceRepository,
                reconciliationRepository,
                deliveryRepository);
    }

    @Test
    @DisplayName("배치의 모든 정산을 대사하고 성공·실패 결과를 함께 누적한다")
    void reconcile_checksEverySettlementAndSavesEveryResult() {
        SettlementSourceLine matchedSource = sourceLine("100.00");
        Settlement matched = settlement(matchedSource, "100.00");
        SettlementSourceLine mismatchedSource = sourceLine("200.00");
        Settlement mismatched = settlement(mismatchedSource, "200.00");
        ReflectionTestUtils.setField(
                mismatched, "totalAmount", new BigDecimal("201.00"));
        given(settlementRepository.findBySettlementBatchId(BATCH_ID))
                .willReturn(List.of(matched, mismatched));
        given(sourceRepository.findBySettlementId(matched.getId()))
                .willReturn(List.of(matchedSource));
        given(sourceRepository.findBySettlementId(mismatched.getId()))
                .willReturn(List.of(mismatchedSource));

        SettlementCalculationReconciliationReport report =
                service.reconcile(BATCH_ID);

        assertThat(report.matched()).isFalse();
        assertThat(report.totalCount()).isEqualTo(2);
        assertThat(report.mismatchedSettlementIds())
                .containsExactly(mismatched.getId());
        then(reconciliationRepository).should().saveAll(argThat(results ->
                results.size() == 2
                        && statuses(results).containsAll(List.of(
                                SettlementCalculationReconciliationStatus.MATCHED,
                                SettlementCalculationReconciliationStatus.MISMATCHED))));
    }

    @Test
    @DisplayName("불일치 정산만 Delivery와 source 연결과 계산 산출물을 정리한다")
    void reconcile_mismatch_cleansOnlyMismatchedSettlementArtifacts() {
        SettlementSourceLine matchedSource = sourceLine("100.00");
        Settlement matched = settlement(matchedSource, "100.00");
        matchedSource.markSettled(matched.getId());
        SettlementSourceLine mismatchedSource = sourceLine("200.00");
        Settlement mismatched = settlement(mismatchedSource, "200.00");
        mismatchedSource.markSettled(mismatched.getId());
        ReflectionTestUtils.setField(mismatched, "productCount", 2);
        given(settlementRepository.findBySettlementBatchId(BATCH_ID))
                .willReturn(List.of(matched, mismatched));
        given(sourceRepository.findBySettlementId(matched.getId()))
                .willReturn(List.of(matchedSource));
        given(sourceRepository.findBySettlementId(mismatched.getId()))
                .willReturn(List.of(mismatchedSource));

        service.reconcile(BATCH_ID);

        assertThat(matchedSource.isSettled()).isTrue();
        assertThat(mismatchedSource.isSettled()).isFalse();
        then(deliveryRepository).should()
                .deleteBySettlementIds(List.of(mismatched.getId()));
        then(settlementRepository).should()
                .deleteAll(List.of(mismatched));
        InOrder inOrder = inOrder(
                reconciliationRepository,
                deliveryRepository,
                settlementRepository);
        inOrder.verify(reconciliationRepository).saveAll(argThat(
                results -> results.size() == 2));
        inOrder.verify(deliveryRepository)
                .deleteBySettlementIds(List.of(mismatched.getId()));
        inOrder.verify(settlementRepository).deleteAll(List.of(mismatched));
    }

    @Test
    @DisplayName("모든 정산이 일치하면 계산 산출물을 정리하지 않는다")
    void reconcile_allMatched_keepsCalculationArtifacts() {
        SettlementSourceLine source = sourceLine("100.00");
        Settlement settlement = settlement(source, "100.00");
        source.markSettled(settlement.getId());
        given(settlementRepository.findBySettlementBatchId(BATCH_ID))
                .willReturn(List.of(settlement));
        given(sourceRepository.findBySettlementId(settlement.getId()))
                .willReturn(List.of(source));

        SettlementCalculationReconciliationReport report =
                service.reconcile(BATCH_ID);

        assertThat(report.matched()).isTrue();
        assertThat(source.isSettled()).isTrue();
        then(deliveryRepository).shouldHaveNoInteractions();
        then(settlementRepository).shouldHaveNoMoreInteractions();
    }

    private List<SettlementCalculationReconciliationStatus> statuses(
            List<SettlementCalculationReconciliation> results) {
        return results.stream()
                .map(SettlementCalculationReconciliation::getStatus)
                .toList();
    }

    private Settlement settlement(SettlementSourceLine source, String amount) {
        SettlementDetail detail = SettlementDetail.sale(
                source.getId(),
                source.getOrderProductId(),
                new BigDecimal(amount),
                new BigDecimal("0.15"),
                source.getOccurredAt());
        Settlement settlement = Settlement.create(
                BATCH_ID,
                source.getSellerId(),
                PERIOD,
                List.of(detail));
        ReflectionTestUtils.setField(settlement, "id", UUID.randomUUID());
        return settlement;
    }

    private SettlementSourceLine sourceLine(String amount) {
        SettlementSourceLine source = SettlementSourceLine.paid(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BigDecimal(amount),
                LocalDateTime.of(2026, 7, 14, 10, 0));
        ReflectionTestUtils.setField(source, "id", UUID.randomUUID());
        return source;
    }
}
