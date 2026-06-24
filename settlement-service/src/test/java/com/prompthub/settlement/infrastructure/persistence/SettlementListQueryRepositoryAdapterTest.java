package com.prompthub.settlement.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.prompthub.settlement.domain.model.Settlement;
import com.prompthub.settlement.domain.model.SettlementDetail;
import com.prompthub.settlement.domain.model.enums.PayoutStatus;
import com.prompthub.settlement.domain.model.enums.SettlementDisplayStatus;
import com.prompthub.settlement.domain.model.enums.SettlementStatus;
import com.prompthub.settlement.domain.repository.SettlementListQueryRepository.SettlementPage;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

/**
 * 도커 컴포즈로 띄운 Postgres(전용 테스트 DB)에 대해 상태 필터·페이징 쿼리를 검증한다.
 * 표시 상태 필터가 실제 SQL 로 올바른 행만 선택하는지가 핵심 검증 대상이다.
 */
@SpringBootTest
@Transactional
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:5432/prompthub_test",
        "spring.datasource.username=promptHub",
        "spring.datasource.password=promptHub",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class SettlementListQueryRepositoryAdapterTest {

    private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 6, 15, 10, 0);

    @Autowired
    private SettlementListQueryRepositoryAdapter adapter;

    @Autowired
    private EntityManager em;

    private void persist(SettlementStatus settlementStatus, PayoutStatus payoutStatus) {
        SettlementDetail detail = SettlementDetail.sale(
                UUID.randomUUID(), new BigDecimal("100.00"), new BigDecimal("0.15"), OCCURRED_AT);
        Settlement settlement = Settlement.create(
                UUID.randomUUID(), UUID.randomUUID(), YearMonth.of(2026, 6), List.of(detail));
        ReflectionTestUtils.setField(settlement, "settlementStatus", settlementStatus);
        ReflectionTestUtils.setField(settlement, "payoutStatus", payoutStatus);
        em.persist(settlement);
    }

    @Test
    @DisplayName("표시 상태 필터는 해당 상태로 파생되는 행만 선택한다")
    void findPage_filtersByDisplayStatus() {
        // given : WAITING 2건, APPROVED 1건, PAID 1건, PAYOUT_ON_HOLD 1건
        persist(SettlementStatus.PENDING_APPROVAL, PayoutStatus.NOT_READY);
        persist(SettlementStatus.PENDING_APPROVAL, PayoutStatus.NOT_READY);
        persist(SettlementStatus.APPROVED, PayoutStatus.READY);
        persist(SettlementStatus.APPROVED, PayoutStatus.PAID);
        persist(SettlementStatus.APPROVED, PayoutStatus.PAYOUT_ON_HOLD);
        em.flush();
        em.clear();

        // when
        SettlementPage waiting = adapter.findPage(SettlementDisplayStatus.WAITING, 0, 10);
        SettlementPage approved = adapter.findPage(SettlementDisplayStatus.APPROVED, 0, 10);

        // then
        assertThat(waiting.totalElements()).isEqualTo(2L);
        assertThat(waiting.content())
                .allMatch(s -> s.displayStatus() == SettlementDisplayStatus.WAITING);
        assertThat(approved.totalElements()).isEqualTo(1L);
        assertThat(approved.content())
                .allMatch(s -> s.displayStatus() == SettlementDisplayStatus.APPROVED);
    }

    @Test
    @DisplayName("status 가 null 이면 전체를 페이징 조회한다")
    void findPage_nullStatus_returnsAllPaged() {
        // given : 총 5건
        persist(SettlementStatus.PENDING_APPROVAL, PayoutStatus.NOT_READY);
        persist(SettlementStatus.SETTLEMENT_ON_HOLD, PayoutStatus.NOT_READY);
        persist(SettlementStatus.APPROVED, PayoutStatus.READY);
        persist(SettlementStatus.APPROVED, PayoutStatus.PAID);
        persist(SettlementStatus.CANCELLED, PayoutStatus.NOT_READY);
        em.flush();
        em.clear();

        // when : 첫 페이지(size 2)
        SettlementPage firstPage = adapter.findPage(null, 0, 2);

        // then : 전체 수는 5, 첫 페이지 내용은 2건
        assertThat(firstPage.totalElements()).isEqualTo(5L);
        assertThat(firstPage.content()).hasSize(2);
    }
}
