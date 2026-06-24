package com.prompthub.settlement.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.prompthub.settlement.application.dto.SettlementListQuery;
import com.prompthub.settlement.domain.exception.SettlementInvalidStateException;
import com.prompthub.settlement.domain.model.Settlement;
import com.prompthub.settlement.domain.model.SettlementDetail;
import com.prompthub.settlement.domain.model.enums.PayoutStatus;
import com.prompthub.settlement.domain.model.enums.SettlementDisplayStatus;
import com.prompthub.settlement.domain.model.enums.SettlementStatus;
import com.prompthub.settlement.domain.model.SettlementSourceLine;
import com.prompthub.settlement.domain.repository.SettlementQueryRepository;
import com.prompthub.settlement.domain.repository.SettlementQueryRepository.SettlementPage;
import com.prompthub.settlement.domain.repository.SettlementRepository;
import com.prompthub.settlement.domain.repository.SettlementSourceRepository;
import com.prompthub.settlement.domain.repository.SettlementStatusAggregate;
import com.prompthub.settlement.global.exception.SettlementException;
import com.prompthub.settlement.presentation.dto.response.SettlementListResponse;
import com.prompthub.settlement.presentation.dto.response.SettlementResponse;
import com.prompthub.settlement.presentation.dto.response.SettlementStatusResponse;
import com.prompthub.settlement.presentation.dto.response.SettlementSummaryResponse;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class SettlementApplicationServiceTest {

    @Mock
    private SettlementQueryRepository settlementQueryRepository;

    @Mock
    private SettlementRepository settlementRepository;

    @Mock
    private SettlementSourceRepository settlementSourceRepository;

    @InjectMocks
    private SettlementApplicationService settlementApplicationService;

    private Settlement settlement(UUID sellerId) {
        SettlementDetail detail = SettlementDetail.sale(
                UUID.randomUUID(), new BigDecimal("100.00"), new BigDecimal("0.15"),
                LocalDateTime.of(2026, 6, 15, 10, 0));
        return Settlement.create(UUID.randomUUID(), sellerId, YearMonth.of(2026, 6), List.of(detail));
    }

    @Test
    @DisplayName("요약: 상태쌍 집계를 카드 버킷으로 접어 고정 4카드를 순서대로 반환한다")
    void getSummary_foldsAggregatesIntoFourCards() {
        given(settlementQueryRepository.aggregateByStatus()).willReturn(List.of(
                new SettlementStatusAggregate(SettlementStatus.PENDING_APPROVAL, PayoutStatus.NOT_READY,
                        new BigDecimal("719000"), 2L),
                new SettlementStatusAggregate(SettlementStatus.SETTLEMENT_ON_HOLD, PayoutStatus.NOT_READY,
                        new BigDecimal("416500"), 2L),
                new SettlementStatusAggregate(SettlementStatus.APPROVED, PayoutStatus.NOT_READY,
                        new BigDecimal("1448500"), 4L),
                new SettlementStatusAggregate(SettlementStatus.APPROVED, PayoutStatus.PAYOUT_ON_HOLD,
                        new BigDecimal("757000"), 2L),
                new SettlementStatusAggregate(SettlementStatus.APPROVED, PayoutStatus.PAID,
                        new BigDecimal("1224000"), 4L)));

        SettlementSummaryResponse response = settlementApplicationService.getSummary();

        assertThat(response.cards())
                .extracting(c -> c.status(), c -> c.totalAmount().stripTrailingZeros(), c -> c.count())
                .containsExactly(
                        tuple("WAITING", new BigDecimal("1135500").stripTrailingZeros(), 4L),
                        tuple("APPROVED", new BigDecimal("1448500").stripTrailingZeros(), 4L),
                        tuple("PAYOUT_ON_HOLD", new BigDecimal("757000").stripTrailingZeros(), 2L),
                        tuple("PAID", new BigDecimal("1224000").stripTrailingZeros(), 4L));
    }

    @Test
    @DisplayName("요약: APPROVED와 PAYOUT_REQUESTED는 같은 승인 완료 카드로 합산된다")
    void getSummary_mergesApprovedAndPayoutRequestedIntoApprovedCard() {
        given(settlementQueryRepository.aggregateByStatus()).willReturn(List.of(
                new SettlementStatusAggregate(SettlementStatus.APPROVED, PayoutStatus.NOT_READY,
                        new BigDecimal("1000000"), 3L),
                new SettlementStatusAggregate(SettlementStatus.APPROVED, PayoutStatus.PAYOUT_REQUESTED,
                        new BigDecimal("250000"), 1L)));

        SettlementSummaryResponse response = settlementApplicationService.getSummary();

        SettlementSummaryResponse.Card approved = response.cards().stream()
                .filter(card -> card.status().equals("APPROVED"))
                .findFirst()
                .orElseThrow();
        assertThat(approved.totalAmount()).isEqualByComparingTo("1250000");
        assertThat(approved.count()).isEqualTo(4L);
    }

    @Test
    @DisplayName("요약: 취소(CANCELLED)는 어느 카드에도 합산되지 않는다")
    void getSummary_excludesCancelled() {
        given(settlementQueryRepository.aggregateByStatus()).willReturn(List.of(
                new SettlementStatusAggregate(SettlementStatus.CANCELLED, PayoutStatus.NOT_READY,
                        new BigDecimal("178000"), 2L)));

        SettlementSummaryResponse response = settlementApplicationService.getSummary();

        assertThat(response.cards()).extracting(c -> c.status())
                .containsExactly("WAITING", "APPROVED", "PAYOUT_ON_HOLD", "PAID");
        assertThat(response.cards()).allMatch(c -> c.count() == 0L);
        assertThat(response.cards()).allMatch(c -> c.totalAmount().signum() == 0);
    }

    @Test
    @DisplayName("요약: 집계가 비어도 0건 4카드를 반환한다")
    void getSummary_emptyAggregates_returnsZeroCards() {
        given(settlementQueryRepository.aggregateByStatus()).willReturn(List.of());

        SettlementSummaryResponse response = settlementApplicationService.getSummary();

        assertThat(response.cards()).hasSize(4);
        assertThat(response.cards()).allMatch(c -> c.count() == 0L && c.totalAmount().signum() == 0);
    }

    @Test
    @DisplayName("목록: 조회한 정산을 응답 항목으로 매핑하고 페이징 정보를 조립한다")
    void getList_mapsSettlementsAndAssemblesPaging() {
        UUID sellerA = UUID.randomUUID();
        UUID sellerB = UUID.randomUUID();
        when(settlementQueryRepository.findPage(SettlementDisplayStatus.WAITING, 0, 20))
                .thenReturn(new SettlementPage(List.of(settlement(sellerA), settlement(sellerB)), 5L));

        SettlementListResponse response = settlementApplicationService.getList(
                new SettlementListQuery(SettlementDisplayStatus.WAITING, 0, 20));

        assertThat(response.totalElements()).isEqualTo(5L);
        assertThat(response.page()).isEqualTo(0);
        assertThat(response.size()).isEqualTo(20);
        assertThat(response.items()).hasSize(2);

        SettlementListResponse.Item first = response.items().get(0);
        assertThat(first.sellerId()).isEqualTo(sellerA);
        assertThat(first.sellerName()).isNull();
        assertThat(first.productCount()).isEqualTo(1);
        assertThat(first.totalAmount()).isEqualByComparingTo("100.00");
        assertThat(first.feeTotalAmount()).isEqualByComparingTo("15.00");
        assertThat(first.settlementTotalAmount()).isEqualByComparingTo("85.00");
        assertThat(first.displayStatus()).isEqualTo("WAITING");
        assertThat(response.items().get(1).sellerId()).isEqualTo(sellerB);
    }

    @Test
    @DisplayName("승인: 정산을 APPROVED/READY로 바꾸고 변경된 상태를 응답으로 반환한다")
    void approve_returnsApprovedResponse() {
        UUID settlementId = UUID.randomUUID();
        Settlement target = settlement(UUID.randomUUID());
        ReflectionTestUtils.setField(target, "id", settlementId);
        given(settlementRepository.findById(settlementId)).willReturn(Optional.of(target));

        SettlementStatusResponse response = settlementApplicationService.approve(settlementId);

        assertThat(response.settlementId()).isEqualTo(settlementId);
        assertThat(response.settlementStatus()).isEqualTo(SettlementStatus.APPROVED);
        assertThat(response.payoutStatus()).isEqualTo(PayoutStatus.READY);
        assertThat(response.confirmedAt()).isNotNull();
        assertThat(response.displayStatus()).isEqualTo(SettlementDisplayStatus.APPROVED);
        then(settlementRepository).should().save(target);
    }

    @Test
    @DisplayName("승인: 정산이 없으면 SettlementException을 던진다")
    void approve_notFound_throws() {
        UUID settlementId = UUID.randomUUID();
        given(settlementRepository.findById(settlementId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> settlementApplicationService.approve(settlementId))
                .isInstanceOf(SettlementException.class);
    }

    @Test
    @DisplayName("승인: 잘못된 상태면 도메인 예외가 전파된다")
    void approve_invalidState_propagates() {
        UUID settlementId = UUID.randomUUID();
        Settlement target = settlement(UUID.randomUUID());
        ReflectionTestUtils.setField(target, "settlementStatus", SettlementStatus.APPROVED);
        given(settlementRepository.findById(settlementId)).willReturn(Optional.of(target));

        assertThatThrownBy(() -> settlementApplicationService.approve(settlementId))
                .isInstanceOf(SettlementInvalidStateException.class);
    }

    @Test
    @DisplayName("승인 보류: SETTLEMENT_ON_HOLD 응답을 반환한다")
    void hold_returnsOnHold() {
        UUID settlementId = UUID.randomUUID();
        Settlement target = settlement(UUID.randomUUID());
        given(settlementRepository.findById(settlementId)).willReturn(Optional.of(target));

        SettlementStatusResponse response = settlementApplicationService.hold(settlementId);

        assertThat(response.settlementStatus()).isEqualTo(SettlementStatus.SETTLEMENT_ON_HOLD);
        assertThat(response.displayStatus()).isEqualTo(SettlementDisplayStatus.APPROVAL_ON_HOLD);
        then(settlementRepository).should().save(target);
    }

    @Test
    @DisplayName("승인 보류 해제: PENDING_APPROVAL 응답을 반환한다")
    void releaseHold_returnsPending() {
        UUID settlementId = UUID.randomUUID();
        Settlement target = settlement(UUID.randomUUID());
        ReflectionTestUtils.setField(target, "settlementStatus", SettlementStatus.SETTLEMENT_ON_HOLD);
        given(settlementRepository.findById(settlementId)).willReturn(Optional.of(target));

        SettlementStatusResponse response = settlementApplicationService.releaseHold(settlementId);

        assertThat(response.settlementStatus()).isEqualTo(SettlementStatus.PENDING_APPROVAL);
        then(settlementRepository).should().save(target);
    }

    @Test
    @DisplayName("지급: payout PAID 응답을 반환한다")
    void payout_returnsPaid() {
        UUID settlementId = UUID.randomUUID();
        Settlement target = settlement(UUID.randomUUID());
        ReflectionTestUtils.setField(target, "settlementStatus", SettlementStatus.APPROVED);
        ReflectionTestUtils.setField(target, "payoutStatus", PayoutStatus.READY);
        given(settlementRepository.findById(settlementId)).willReturn(Optional.of(target));

        SettlementStatusResponse response = settlementApplicationService.payout(settlementId);

        assertThat(response.payoutStatus()).isEqualTo(PayoutStatus.PAID);
        assertThat(response.paidAt()).isNotNull();
        assertThat(response.displayStatus()).isEqualTo(SettlementDisplayStatus.PAID);
        then(settlementRepository).should().save(target);
    }

    @Test
    @DisplayName("지급 보류: payout PAYOUT_ON_HOLD 응답을 반환한다")
    void payoutHold_returnsOnHold() {
        UUID settlementId = UUID.randomUUID();
        Settlement target = settlement(UUID.randomUUID());
        ReflectionTestUtils.setField(target, "settlementStatus", SettlementStatus.APPROVED);
        ReflectionTestUtils.setField(target, "payoutStatus", PayoutStatus.READY);
        given(settlementRepository.findById(settlementId)).willReturn(Optional.of(target));

        SettlementStatusResponse response = settlementApplicationService.payoutHold(settlementId);

        assertThat(response.payoutStatus()).isEqualTo(PayoutStatus.PAYOUT_ON_HOLD);
        then(settlementRepository).should().save(target);
    }

    @Test
    @DisplayName("지급 보류 해제: payout READY 응답을 반환한다")
    void releasePayoutHold_returnsReady() {
        UUID settlementId = UUID.randomUUID();
        Settlement target = settlement(UUID.randomUUID());
        ReflectionTestUtils.setField(target, "settlementStatus", SettlementStatus.APPROVED);
        ReflectionTestUtils.setField(target, "payoutStatus", PayoutStatus.PAYOUT_ON_HOLD);
        given(settlementRepository.findById(settlementId)).willReturn(Optional.of(target));

        SettlementStatusResponse response = settlementApplicationService.releasePayoutHold(settlementId);

        assertThat(response.payoutStatus()).isEqualTo(PayoutStatus.READY);
        then(settlementRepository).should().save(target);
    }

    @Test
    @DisplayName("지급: 정산이 없으면 SettlementException을 던진다")
    void payout_notFound_throws() {
        UUID settlementId = UUID.randomUUID();
        given(settlementRepository.findById(settlementId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> settlementApplicationService.payout(settlementId))
                .isInstanceOf(SettlementException.class);
    }

    @Test
    @DisplayName("취소: 정산을 CANCELLED로 바꾸고 묶인 소스 라인을 모두 푼다")
    void cancel_cancelsSettlementAndReleasesLines() {
        UUID settlementId = UUID.randomUUID();
        Settlement target = settlement(UUID.randomUUID());
        ReflectionTestUtils.setField(target, "id", settlementId);

        SettlementSourceLine line1 = settledLine(settlementId);
        SettlementSourceLine line2 = settledLine(settlementId);
        given(settlementRepository.findById(settlementId)).willReturn(Optional.of(target));
        given(settlementSourceRepository.findBySettlementId(settlementId))
                .willReturn(List.of(line1, line2));

        SettlementResponse result = settlementApplicationService.cancel(settlementId);

        assertThat(result.displayStatus()).isEqualTo("CANCELLED");
        assertThat(result.canceledAt()).isNotNull();
        assertThat(line1.isSettled()).isFalse();
        assertThat(line2.isSettled()).isFalse();
        then(settlementRepository).should().save(target);
    }

    @Test
    @DisplayName("취소: 정산이 없으면 SettlementException(SETTLEMENT_NOT_FOUND)을 던지고 소스 라인을 조회하지 않는다")
    void cancel_notFound_throws() {
        UUID settlementId = UUID.randomUUID();
        given(settlementRepository.findById(settlementId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> settlementApplicationService.cancel(settlementId))
                .isInstanceOf(SettlementException.class);

        then(settlementSourceRepository).should(never()).findBySettlementId(settlementId);
    }

    private SettlementSourceLine settledLine(UUID settlementId) {
        SettlementSourceLine line = SettlementSourceLine.paid(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("100.00"), LocalDateTime.of(2026, 6, 15, 10, 0));
        line.markSettled(settlementId);
        return line;
    }
}
