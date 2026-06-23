package com.prompthub.settlement.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.prompthub.settlement.domain.model.enums.SettlementLineType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SettlementDetailTest {

    private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 6, 15, 10, 0);

    @Test
    @DisplayName("판매 상세는 수수료와 실정산액을 계산한다")
    void sale_calculatesFeeAndSettlementAmount() {
        // when
        SettlementDetail detail = SettlementDetail.sale(
                UUID.randomUUID(),
                new BigDecimal("100.00"),
                new BigDecimal("0.15"),
                OCCURRED_AT);

        // then
        assertThat(detail.getFeeAmount()).isEqualByComparingTo("15.00");
        assertThat(detail.getLineSettlementAmount()).isEqualByComparingTo("85.00");
        assertThat(detail.getLineAmount()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("수수료는 소수 둘째 자리에서 HALF_UP으로 반올림한다")
    void sale_roundsFeeHalfUp() {
        // given 10.00 * 0.1525 = 1.525000 -> HALF_UP scale 2 = 1.53
        // when
        SettlementDetail detail = SettlementDetail.sale(
                UUID.randomUUID(),
                new BigDecimal("10.00"),
                new BigDecimal("0.1525"),
                OCCURRED_AT);

        // then
        assertThat(detail.getFeeAmount()).isEqualByComparingTo("1.53");
        assertThat(detail.getLineSettlementAmount()).isEqualByComparingTo("8.47");
    }

    @Test
    @DisplayName("판매 상세의 라인 타입은 SALE이다")
    void sale_lineTypeIsSale() {
        // when
        SettlementDetail detail = SettlementDetail.sale(
                UUID.randomUUID(),
                new BigDecimal("50.00"),
                new BigDecimal("0.15"),
                OCCURRED_AT);

        // then
        assertThat(detail.getLineType()).isEqualTo(SettlementLineType.SALE);
        assertThat(detail.getOccurredAt()).isEqualTo(OCCURRED_AT);
    }
}
