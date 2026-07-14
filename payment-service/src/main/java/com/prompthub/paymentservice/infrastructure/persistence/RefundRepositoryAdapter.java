package com.prompthub.paymentservice.infrastructure.persistence;

import com.prompthub.paymentservice.domain.repository.RefundRepository;
import com.prompthub.paymentservice.domain.model.Refund;
import com.prompthub.paymentservice.domain.model.RefundStatus;
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
    public Optional<Refund> findByPaymentIdAndOrderProductId(UUID paymentId, UUID orderProductId) {
        return jpaRepository.findByPaymentIdAndOrderProductId(paymentId, orderProductId);
    }
}
