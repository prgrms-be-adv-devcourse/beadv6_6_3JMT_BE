package com.prompthub.payment.infrastructure.persistence;

import com.prompthub.payment.domain.model.Payment;
import com.prompthub.payment.domain.model.PaymentStatus;
import com.prompthub.payment.support.AbstractJpaTest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentJpaRepositoryTest extends AbstractJpaTest {

    @Autowired
    PaymentJpaRepository paymentJpaRepository;

    @Test
    void payment_save_findById_round_trip() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        Payment payment = Payment.create(
            orderId, userId,
            "pg-tx-001", "TOSS_PAYMENTS", "CARD", true,
            9_000
        );

        Payment saved = paymentJpaRepository.saveAndFlush(payment);

        Payment found = paymentJpaRepository.findById(saved.getId())
            .orElseThrow(() -> new AssertionError("Payment not found"));

        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getOrderId()).isEqualTo(orderId);
        assertThat(found.getPgTxId()).isEqualTo("pg-tx-001");
        assertThat(found.getTotalAmount()).isEqualTo(9_000);
        assertThat(found.getStatus()).isEqualTo(PaymentStatus.READY);
        assertThat(found.getApprovedAmount()).isNull();
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }

    @Test
    @Transactional
    void findByOrderIdAndStatusInForUpdate_PAID_상태_조회() {
        UUID orderId = UUID.randomUUID();
        Payment payment = Payment.create(
            orderId, UUID.randomUUID(), "pg-key", "TOSS_PAYMENTS", "CARD", false, 10_000);
        payment.markRequested(OffsetDateTime.now());
        payment.approve(10_000, "카드", "{}", OffsetDateTime.now());
        paymentJpaRepository.saveAndFlush(payment);

        Optional<Payment> found = paymentJpaRepository.findByOrderIdAndStatusInForUpdate(
            orderId, List.of(PaymentStatus.PAID, PaymentStatus.PARTIAL_REFUNDED));

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(payment.getId());
    }

    @Test
    @Transactional
    void findByOrderIdAndStatusInForUpdate_대상_상태_아니면_빈값() {
        UUID orderId = UUID.randomUUID();
        Payment payment = Payment.create(
            orderId, UUID.randomUUID(), "pg-key2", "TOSS_PAYMENTS", "CARD", false, 10_000);
        paymentJpaRepository.saveAndFlush(payment); // READY 상태

        Optional<Payment> found = paymentJpaRepository.findByOrderIdAndStatusInForUpdate(
            orderId, List.of(PaymentStatus.PAID, PaymentStatus.PARTIAL_REFUNDED));

        assertThat(found).isEmpty();
    }

    @Test
    void existsByPgTxId_존재_여부_확인() {
        Payment payment = Payment.create(
            UUID.randomUUID(), UUID.randomUUID(), "pg-tx-exists", "TOSS_PAYMENTS", "CARD", false, 10_000);
        paymentJpaRepository.saveAndFlush(payment);

        assertThat(paymentJpaRepository.existsByPgTxId("pg-tx-exists")).isTrue();
        assertThat(paymentJpaRepository.existsByPgTxId("pg-tx-missing")).isFalse();
    }
}
