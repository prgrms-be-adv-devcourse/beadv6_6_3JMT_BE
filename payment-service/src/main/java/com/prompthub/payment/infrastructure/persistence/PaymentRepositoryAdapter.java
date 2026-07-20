package com.prompthub.payment.infrastructure.persistence;

import com.prompthub.payment.domain.repository.PaymentRepository;
import com.prompthub.payment.domain.model.Payment;
import com.prompthub.payment.domain.model.PaymentStatus;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PaymentRepositoryAdapter implements PaymentRepository {

    private final PaymentJpaRepository jpaRepository;

    @Override
    public Payment save(Payment payment) {
        return jpaRepository.save(payment);
    }

    @Override
    public Payment saveAndFlush(Payment payment) {
        return jpaRepository.saveAndFlush(payment);
    }

    @Override
    public Optional<Payment> findById(UUID id) {
        return jpaRepository.findById(id);
    }

    @Override
    public Optional<Payment> findByIdForUpdate(UUID id) {
        return jpaRepository.findByIdForUpdate(id);
    }

    @Override
    public boolean existsByPgTxId(String pgTxId) {
        return jpaRepository.existsByPgTxId(pgTxId);
    }

    @Override
    public boolean existsByOrderIdAndStatusIn(UUID orderId, Collection<PaymentStatus> statuses) {
        return jpaRepository.existsByOrderIdAndStatusIn(orderId, statuses);
    }

    @Override
    public Optional<Payment> findByOrderIdAndStatusInForUpdate(UUID orderId, Collection<PaymentStatus> statuses) {
        return jpaRepository.findByOrderIdAndStatusInForUpdate(orderId, statuses);
    }
}
