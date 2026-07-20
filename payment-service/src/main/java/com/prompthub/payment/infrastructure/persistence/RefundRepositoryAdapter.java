package com.prompthub.payment.infrastructure.persistence;

import com.prompthub.payment.domain.repository.RefundRepository;
import com.prompthub.payment.domain.model.Refund;
import com.prompthub.payment.domain.model.RefundStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;


@Component
@RequiredArgsConstructor
public class RefundRepositoryAdapter implements RefundRepository {

    private final RefundJpaRepository jpaRepository;

    @Override
    public Refund save(Refund refund) {
        return jpaRepository.save(refund);
    }

    @Override
    public Optional<Refund> findById(UUID id) {
        return jpaRepository.findById(id);
    }

    @Override
    public List<Refund> findByPaymentIdAndStatus(UUID paymentId, RefundStatus status) {
        return jpaRepository.findByPaymentIdAndStatus(paymentId, status);
    }

    @Override
    public boolean existsByRefundRequestId(UUID refundRequestId) {
        return jpaRepository.existsByRefundRequestId(refundRequestId);
    }
}
