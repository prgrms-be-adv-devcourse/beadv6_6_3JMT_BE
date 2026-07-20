package com.prompthub.payment.infrastructure.persistence;

import com.prompthub.payment.domain.model.Refund;
import com.prompthub.payment.domain.model.RefundStatus;
import com.prompthub.payment.support.AbstractJpaTest;
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
        UUID refundRequestId = UUID.randomUUID();

        Refund refund = Refund.create(paymentId, refundRequestId, 5_000, "단순 변심");

        Refund saved = refundJpaRepository.saveAndFlush(refund);

        Refund found = refundJpaRepository.findById(saved.getId())
            .orElseThrow(() -> new AssertionError("Refund not found"));

        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getPaymentId()).isEqualTo(paymentId);
        assertThat(found.getRefundRequestId()).isEqualTo(refundRequestId);
        assertThat(found.getRefundAmount()).isEqualTo(5_000);
        assertThat(found.getStatus()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(found.getRequestedAt()).isNotNull();
        assertThat(found.getCompletedAt()).isNull();
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }

    @Test
    void 같은_refundRequestId_중복_저장_시_유니크_제약_위반() {
        UUID paymentId = UUID.randomUUID();
        UUID refundRequestId = UUID.randomUUID();

        Refund first = Refund.create(paymentId, refundRequestId, 5_000, null);
        refundJpaRepository.saveAndFlush(first);

        Refund duplicate = Refund.create(paymentId, refundRequestId, 3_000, null);

        assertThatThrownBy(() -> refundJpaRepository.saveAndFlush(duplicate))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void 같은_결제_같은_상품도_다른_refundRequestId면_재환불_허용() {
        UUID paymentId = UUID.randomUUID();

        Refund first = Refund.create(paymentId, UUID.randomUUID(), 5_000, null);
        Refund second = Refund.create(paymentId, UUID.randomUUID(), 3_000, null);

        refundJpaRepository.saveAndFlush(first);
        refundJpaRepository.saveAndFlush(second);

        assertThat(refundJpaRepository.findById(first.getId())).isPresent();
        assertThat(refundJpaRepository.findById(second.getId())).isPresent();
    }

    @Test
    void findByPaymentIdAndStatus_COMPLETED_건만_조회() {
        UUID paymentId = UUID.randomUUID();
        Refund completed = Refund.create(paymentId, UUID.randomUUID(), 3_000, null);
        completed.complete(java.time.OffsetDateTime.now());
        Refund requested = Refund.create(paymentId, UUID.randomUUID(), 2_000, null);
        refundJpaRepository.saveAndFlush(completed);
        refundJpaRepository.saveAndFlush(requested);

        java.util.List<Refund> found = refundJpaRepository.findByPaymentIdAndStatus(paymentId, RefundStatus.COMPLETED);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).getRefundAmount()).isEqualTo(3_000);
    }

    @Test
    void existsByRefundRequestId_존재하면_true() {
        UUID refundRequestId = UUID.randomUUID();
        Refund refund = Refund.create(UUID.randomUUID(), refundRequestId, 4_000, null);
        refundJpaRepository.saveAndFlush(refund);

        assertThat(refundJpaRepository.existsByRefundRequestId(refundRequestId)).isTrue();
    }

    @Test
    void existsByRefundRequestId_없으면_false() {
        assertThat(refundJpaRepository.existsByRefundRequestId(UUID.randomUUID())).isFalse();
    }
}
