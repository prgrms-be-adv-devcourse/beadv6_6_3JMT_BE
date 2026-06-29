package com.prompthub.paymentservice.infrastructure.persistence;

import com.prompthub.paymentservice.domain.model.Refund;
import com.prompthub.paymentservice.domain.model.RefundStatus;
import com.prompthub.paymentservice.support.AbstractJpaTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class RefundJpaRepositoryTest extends AbstractJpaTest {

    @Autowired
    RefundJpaRepository refundJpaRepository;

    @Test
    void refund_save_findById_round_trip() {
        UUID paymentId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID orderProductId = UUID.randomUUID();

        Refund refund = Refund.create(paymentId, userId, 5_000, "단순 변심", orderProductId);

        Refund saved = refundJpaRepository.saveAndFlush(refund);

        Refund found = refundJpaRepository.findById(saved.getId())
            .orElseThrow(() -> new AssertionError("Refund not found"));

        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getPaymentId()).isEqualTo(paymentId);
        assertThat(found.getOrderProductId()).isEqualTo(orderProductId);
        assertThat(found.getRefundAmount()).isEqualTo(5_000);
        assertThat(found.getStatus()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(found.getRequestedAt()).isNotNull();
        assertThat(found.getCompletedAt()).isNull();
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }

    @Test
    void refund_without_order_product_id() {
        Refund refund = Refund.create(
            UUID.randomUUID(), UUID.randomUUID(),
            10_000, "전체 환불", null
        );

        Refund saved = refundJpaRepository.saveAndFlush(refund);

        Refund found = refundJpaRepository.findById(saved.getId())
            .orElseThrow(() -> new AssertionError("Refund not found"));

        assertThat(found.getOrderProductId()).isNull();
    }
}
