package com.prompthub.payment.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentTest {

    @Test
    void create_후_READY_상태() {
        Payment payment = 결제_생성();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.READY);
        assertThat(payment.getApprovedAmount()).isNull();
        assertThat(payment.getPgTxId()).isEqualTo("pg-key-001");
    }

    @Test
    void markRequested_후_REQUESTED_상태() {
        Payment payment = 결제_생성();
        OffsetDateTime requestedAt = OffsetDateTime.now();

        payment.markRequested(requestedAt);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REQUESTED);
        assertThat(payment.getRequestedAt()).isEqualTo(requestedAt);
    }

    @Test
    void approve_후_PAID_상태() {
        Payment payment = 결제_생성();
        payment.markRequested(OffsetDateTime.now());
        OffsetDateTime approvedAt = OffsetDateTime.now();

        payment.approve(10_000, "카드", "{\"paymentKey\":\"pg-key-001\"}", "{}", approvedAt);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(payment.getApprovedAmount()).isEqualTo(10_000);
        assertThat(payment.getPaymentMethod()).isEqualTo("카드");
        assertThat(payment.getRequestPayload()).isEqualTo("{\"paymentKey\":\"pg-key-001\"}");
        assertThat(payment.getApprovedAt()).isEqualTo(approvedAt);
    }

    @Test
    void fail_후_FAILED_상태() {
        Payment payment = 결제_생성();
        payment.markRequested(OffsetDateTime.now());

        payment.fail("REJECT", "카드 거절", null, "{}", OffsetDateTime.now());

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureCode()).isEqualTo("REJECT");
        assertThat(payment.getFailureReason()).isEqualTo("카드 거절");
    }

    @Test
    void READY_아닌_상태에서_markRequested_실패() {
        Payment payment = 결제_생성();
        payment.markRequested(OffsetDateTime.now());

        assertThatThrownBy(() -> payment.markRequested(OffsetDateTime.now()))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void REQUESTED_아닌_상태에서_approve_실패() {
        Payment payment = 결제_생성();

        assertThatThrownBy(() -> payment.approve(10_000, "카드", "{}", "{}", OffsetDateTime.now()))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void READY_상태에서_바로_fail_가능() {
        Payment payment = 결제_생성();

        payment.fail("AMOUNT_MISMATCH", "금액 불일치", null, null, OffsetDateTime.now());

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureCode()).isEqualTo("AMOUNT_MISMATCH");
    }

    @Test
    void PAID_상태에서_fail_실패() {
        Payment payment = 결제_생성_후_승인(10_000);

        assertThatThrownBy(() -> payment.fail("REJECT", "거절", null, "{}", OffsetDateTime.now()))
            .isInstanceOf(IllegalStateException.class);
    }

    private Payment 결제_생성_후_승인(int amount) {
        Payment payment = Payment.create(
            UUID.randomUUID(), UUID.randomUUID(),
            "pg-key-002", "TOSS_PAYMENTS", "CARD", amount
        );
        payment.markRequested(OffsetDateTime.now());
        payment.approve(amount, "카드", "{}", "{}", OffsetDateTime.now());
        return payment;
    }

    private Payment 결제_생성() {
        return Payment.create(
            UUID.randomUUID(), UUID.randomUUID(),
            "pg-key-001", "TOSS_PAYMENTS", "UNKNOWN",
            10_000
        );
    }
}
