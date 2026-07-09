package com.prompthub.paymentservice.infrastructure.persistence;

import com.prompthub.paymentservice.domain.model.OrderSnapshot;
import com.prompthub.paymentservice.domain.repository.OrderSnapshotRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OrderSnapshotRepositoryAdapter implements OrderSnapshotRepository {

    private final OrderSnapshotJpaRepository jpaRepository;

    @Override
    public OrderSnapshot save(OrderSnapshot orderSnapshot) {
        return jpaRepository.save(orderSnapshot);
    }

    @Override
    public Optional<OrderSnapshot> findByOrderId(UUID orderId) {
        return jpaRepository.findByOrderId(orderId);
    }
}
