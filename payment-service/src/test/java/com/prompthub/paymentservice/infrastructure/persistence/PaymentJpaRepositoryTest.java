package com.prompthub.paymentservice.infrastructure.persistence;

import com.prompthub.paymentservice.domain.model.Payment;
import com.prompthub.paymentservice.domain.model.PaymentStatus;
import com.prompthub.paymentservice.support.AbstractJpaTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

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
}
