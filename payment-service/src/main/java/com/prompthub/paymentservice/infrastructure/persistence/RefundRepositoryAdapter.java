package com.prompthub.paymentservice.infrastructure.persistence;

import com.prompthub.paymentservice.domain.repository.RefundRepository;
import com.prompthub.paymentservice.domain.model.Refund;
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
    public Optional<Refund> findByPaymentId(UUID paymentId) {
        return jpaRepository.findByPaymentId(paymentId);
    }
}
