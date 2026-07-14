package com.prompthub.paymentservice.infrastructure.persistence;

import com.prompthub.paymentservice.domain.model.Refund;
import com.prompthub.paymentservice.domain.model.RefundStatus;
import com.prompthub.paymentservice.support.AbstractJpaTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void 같은_결제_같은_상품_중복_환불_시_유니크_제약_위반() {
        UUID paymentId = UUID.randomUUID();
        UUID orderProductId = UUID.randomUUID();

        Refund first = Refund.create(paymentId, UUID.randomUUID(), 5_000, null, orderProductId);
        refundJpaRepository.saveAndFlush(first);

        Refund duplicate = Refund.create(paymentId, UUID.randomUUID(), 3_000, null, orderProductId);

        assertThatThrownBy(() -> refundJpaRepository.saveAndFlush(duplicate))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void findByPaymentIdAndStatus_COMPLETED_건만_조회() {
        UUID paymentId = UUID.randomUUID();
        Refund completed = Refund.create(paymentId, UUID.randomUUID(), 3_000, null, UUID.randomUUID());
        completed.complete(java.time.OffsetDateTime.now());
        Refund requested = Refund.create(paymentId, UUID.randomUUID(), 2_000, null, UUID.randomUUID());
        refundJpaRepository.saveAndFlush(completed);
        refundJpaRepository.saveAndFlush(requested);

        java.util.List<Refund> found = refundJpaRepository.findByPaymentIdAndStatus(paymentId, RefundStatus.COMPLETED);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).getRefundAmount()).isEqualTo(3_000);
    }

    @Test
    void findByPaymentIdAndOrderProductId_정상_조회() {
        UUID paymentId = UUID.randomUUID();
        UUID orderProductId = UUID.randomUUID();
        Refund refund = Refund.create(paymentId, UUID.randomUUID(), 4_000, null, orderProductId);
        refundJpaRepository.saveAndFlush(refund);

        java.util.Optional<Refund> found = refundJpaRepository.findByPaymentIdAndOrderProductId(paymentId, orderProductId);

        assertThat(found).isPresent();
        assertThat(found.get().getRefundAmount()).isEqualTo(4_000);
    }

    @Test
    void findByPaymentIdAndOrderProductId_없으면_empty() {
        java.util.Optional<Refund> found = refundJpaRepository
            .findByPaymentIdAndOrderProductId(UUID.randomUUID(), UUID.randomUUID());

        assertThat(found).isEmpty();
    }
}
