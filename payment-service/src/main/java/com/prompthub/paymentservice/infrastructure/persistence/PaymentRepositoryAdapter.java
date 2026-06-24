package com.prompthub.paymentservice.infrastructure.persistence;

import com.prompthub.paymentservice.application.gateway.persistence.PaymentRepository;
import com.prompthub.paymentservice.domain.model.Payment;
import com.prompthub.paymentservice.domain.model.PaymentStatus;
import java.time.OffsetDateTime;
import java.util.List;
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
    public Optional<Payment> findByIdempotencyKey(String idempotencyKey) {
        return jpaRepository.findByIdempotencyKey(idempotencyKey);
    }

    @Override
    public List<Payment> findByStatusAndUpdatedAtBefore(PaymentStatus status, OffsetDateTime threshold) {
        return jpaRepository.findByStatusAndUpdatedAtBefore(status, threshold);
    }
}
