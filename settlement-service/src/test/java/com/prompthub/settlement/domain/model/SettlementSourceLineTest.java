package com.prompthub.settlement.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.prompthub.settlement.domain.exception.SettlementSourceLineAlreadySettledException;
import com.prompthub.settlement.domain.model.enums.SettlementSourceEventType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SettlementSourceLineTest {

    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final UUID ORDER_ID = UUID.randomUUID();
    private static final UUID ORDER_PRODUCT_ID = UUID.randomUUID();
    private static final UUID SELLER_ID = UUID.randomUUID();
    private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 6, 15, 10, 0);

    @Test
    @DisplayName("결제 라인은 PAID 유형과 양수 금액으로 생성되고 미정산 상태다")
    void paid_createsPositivePaidLine() {
        SettlementSourceLine line = SettlementSourceLine.paid(
                EVENT_ID, ORDER_ID, ORDER_PRODUCT_ID, SELLER_ID, new BigDecimal("100.00"), OCCURRED_AT);

        assertThat(line.getEventType()).isEqualTo(SettlementSourceEventType.PAID);
        assertThat(line.getEventId()).isEqualTo(EVENT_ID);
        assertThat(line.getLineAmount()).isEqualByComparingTo("100.00");
        assertThat(line.getSettlementId()).isNull();
        assertThat(line.isSettled()).isFalse();
    }

    @Test
    @DisplayName("환불 라인은 REFUND 유형과 양수 금액으로 생성된다 (가산/차감은 event_type으로 구분)")
    void refunded_createsPositiveRefundLine() {
        SettlementSourceLine line = SettlementSourceLine.refunded(
                EVENT_ID, ORDER_ID, ORDER_PRODUCT_ID, SELLER_ID, new BigDecimal("100.00"), OCCURRED_AT);

        assertThat(line.getEventType()).isEqualTo(SettlementSourceEventType.REFUND);
        assertThat(line.getLineAmount()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("필수값이 없으면 생성에 실패한다")
    void paid_nullRequired_throws() {
        assertThatThrownBy(() -> SettlementSourceLine.paid(
                null, ORDER_ID, ORDER_PRODUCT_ID, SELLER_ID, new BigDecimal("100.00"), OCCURRED_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SettlementSourceLine.paid(
                EVENT_ID, ORDER_ID, null, SELLER_ID, new BigDecimal("100.00"), OCCURRED_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SettlementSourceLine.paid(
                EVENT_ID, ORDER_ID, ORDER_PRODUCT_ID, null, new BigDecimal("100.00"), OCCURRED_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SettlementSourceLine.paid(
                EVENT_ID, ORDER_ID, ORDER_PRODUCT_ID, SELLER_ID, new BigDecimal("100.00"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("금액이 0 이하이면 생성에 실패한다")
    void paid_nonPositiveAmount_throws() {
        assertThatThrownBy(() -> SettlementSourceLine.paid(
                EVENT_ID, ORDER_ID, ORDER_PRODUCT_ID, SELLER_ID, BigDecimal.ZERO, OCCURRED_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SettlementSourceLine.paid(
                EVENT_ID, ORDER_ID, ORDER_PRODUCT_ID, SELLER_ID, new BigDecimal("-1.00"), OCCURRED_AT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("미정산 라인을 정산에 연결하면 settlementId를 가진다")
    void markSettled_linksSettlement() {
        SettlementSourceLine line = SettlementSourceLine.paid(
                EVENT_ID, ORDER_ID, ORDER_PRODUCT_ID, SELLER_ID, new BigDecimal("100.00"), OCCURRED_AT);
        UUID settlementId = UUID.randomUUID();

        line.markSettled(settlementId);

        assertThat(line.getSettlementId()).isEqualTo(settlementId);
        assertThat(line.isSettled()).isTrue();
    }

    @Test
    @DisplayName("이미 정산에 포함된 라인은 다시 정산에 연결할 수 없다")
    void markSettled_alreadySettled_throws() {
        SettlementSourceLine line = SettlementSourceLine.paid(
                EVENT_ID, ORDER_ID, ORDER_PRODUCT_ID, SELLER_ID, new BigDecimal("100.00"), OCCURRED_AT);
        line.markSettled(UUID.randomUUID());

        assertThatThrownBy(() -> line.markSettled(UUID.randomUUID()))
                .isInstanceOf(SettlementSourceLineAlreadySettledException.class);
    }
}
