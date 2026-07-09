package com.prompthub.settlement.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.prompthub.settlement.domain.model.Settlement;
import com.prompthub.settlement.domain.model.SettlementDetail;
import com.prompthub.settlement.domain.model.enums.PayoutStatus;
import com.prompthub.settlement.domain.model.enums.SettlementDisplayStatus;
import com.prompthub.settlement.domain.model.enums.SettlementStatus;
import com.prompthub.settlement.domain.repository.SettlementQueryRepository.SettlementPage;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SettlementQueryRepositoryAdapterTest {

    private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 6, 15, 10, 0);

    @Autowired
    private SettlementQueryRepositoryAdapter adapter;

    @Autowired
    private EntityManager em;

    private UUID persist(SettlementStatus settlementStatus, PayoutStatus payoutStatus) {
        return persist(UUID.randomUUID(), YearMonth.of(2026, 6), settlementStatus, payoutStatus);
    }

    private UUID persist(UUID sellerId, YearMonth period, SettlementStatus settlementStatus, PayoutStatus payoutStatus) {
        SettlementDetail detail = SettlementDetail.sale(
                UUID.randomUUID(), new BigDecimal("100.00"), new BigDecimal("0.15"), OCCURRED_AT);
        Settlement settlement = Settlement.create(UUID.randomUUID(), sellerId, period, List.of(detail));
        ReflectionTestUtils.setField(settlement, "settlementStatus", settlementStatus);
        ReflectionTestUtils.setField(settlement, "payoutStatus", payoutStatus);
        em.persist(settlement);
        return sellerId;
    }

    @Test
    @DisplayName("표시 상태 필터는 해당 상태로 파생되는 행만 선택한다")
    void findPage_filtersByDisplayStatus() {
        UUID waitingA = persist(SettlementStatus.PENDING_APPROVAL, PayoutStatus.NOT_READY);
        UUID waitingB = persist(SettlementStatus.PENDING_APPROVAL, PayoutStatus.NOT_READY);
        UUID approvedSeller = persist(SettlementStatus.APPROVED, PayoutStatus.READY);
        persist(SettlementStatus.APPROVED, PayoutStatus.PAID);
        persist(SettlementStatus.APPROVED, PayoutStatus.PAYOUT_ON_HOLD);
        em.flush();
        em.clear();

        SettlementPage waiting = adapter.findPage(SettlementDisplayStatus.WAITING, 0, 100);
        SettlementPage approved = adapter.findPage(SettlementDisplayStatus.APPROVED, 0, 100);

        assertThat(waiting.content()).allMatch(s -> s.displayStatus() == SettlementDisplayStatus.WAITING);
        assertThat(approved.content()).allMatch(s -> s.displayStatus() == SettlementDisplayStatus.APPROVED);
        assertThat(waiting.content()).extracting(Settlement::getSellerId)
                .contains(waitingA, waitingB).doesNotContain(approvedSeller);
        assertThat(approved.content()).extracting(Settlement::getSellerId)
                .contains(approvedSeller).doesNotContain(waitingA, waitingB);
    }

    @Test
    @DisplayName("status 가 null 이면 전체를 페이지 크기만큼 잘라 조회한다")
    void findPage_nullStatus_returnsAllPaged() {
        persist(SettlementStatus.PENDING_APPROVAL, PayoutStatus.NOT_READY);
        persist(SettlementStatus.SETTLEMENT_ON_HOLD, PayoutStatus.NOT_READY);
        persist(SettlementStatus.APPROVED, PayoutStatus.READY);
        persist(SettlementStatus.APPROVED, PayoutStatus.PAID);
        persist(SettlementStatus.CANCELLED, PayoutStatus.NOT_READY);
        em.flush();
        em.clear();

        SettlementPage firstPage = adapter.findPage(null, 0, 2);

        assertThat(firstPage.content()).hasSize(2);
        assertThat(firstPage.totalElements()).isGreaterThanOrEqualTo(5L);
    }

    @Test
    @DisplayName("판매자 조회: 본인 sellerId 정산만 조회하고 타인 정산은 제외한다")
    void findPageBySeller_filtersBySeller() {
        UUID me = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        persist(me, YearMonth.of(2026, 6), SettlementStatus.PENDING_APPROVAL, PayoutStatus.NOT_READY);
        persist(me, YearMonth.of(2026, 5), SettlementStatus.APPROVED, PayoutStatus.READY);
        persist(other, YearMonth.of(2026, 6), SettlementStatus.PENDING_APPROVAL, PayoutStatus.NOT_READY);
        em.flush();
        em.clear();

        SettlementPage page = adapter.findPageBySeller(me, null, null, 0, 100);

        assertThat(page.totalElements()).isEqualTo(2L);
        assertThat(page.content()).extracting(Settlement::getSellerId).containsOnly(me);
    }

    @Test
    @DisplayName("판매자 조회: period로 해당 기준 월 정산만 조회한다")
    void findPageBySeller_filtersByPeriod() {
        UUID me = UUID.randomUUID();
        persist(me, YearMonth.of(2026, 6), SettlementStatus.PENDING_APPROVAL, PayoutStatus.NOT_READY);
        persist(me, YearMonth.of(2026, 5), SettlementStatus.PENDING_APPROVAL, PayoutStatus.NOT_READY);
        em.flush();
        em.clear();

        SettlementPage page = adapter.findPageBySeller(me, null, YearMonth.of(2026, 6), 0, 100);

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().get(0).getPeriodStart()).isEqualTo(LocalDate.of(2026, 6, 1));
    }

    @Test
    @DisplayName("판매자 조회: 표시 상태 필터와 sellerId 필터가 함께 적용된다")
    void findPageBySeller_filtersBySellerAndDisplayStatus() {
        UUID me = UUID.randomUUID();
        persist(me, YearMonth.of(2026, 6), SettlementStatus.PENDING_APPROVAL, PayoutStatus.NOT_READY);
        persist(me, YearMonth.of(2026, 5), SettlementStatus.APPROVED, PayoutStatus.READY);
        em.flush();
        em.clear();

        SettlementPage approved = adapter.findPageBySeller(me, SettlementDisplayStatus.APPROVED, null, 0, 100);

        assertThat(approved.content()).hasSize(1);
        assertThat(approved.content().get(0).displayStatus()).isEqualTo(SettlementDisplayStatus.APPROVED);
    }
}
