package com.prompthub.order.infra.persistence.refund;

import com.prompthub.order.domain.model.OrderRefund;
import com.prompthub.order.domain.repository.OrderRefundRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class OrderRefundAdapter implements OrderRefundRepository {

    private final OrderRefundPersistence orderRefundPersistence;

    @Override
    public OrderRefund save(OrderRefund refund) {
        return orderRefundPersistence.save(refund);
    }

    @Override
    public Optional<OrderRefund> findByIdForUpdate(UUID refundRequestId) {
        return orderRefundPersistence.findByIdForUpdate(refundRequestId);
    }

    @Override
    public List<OrderRefund> findDueRefunds(LocalDateTime now, int batchSize) {
        return orderRefundPersistence.findDueRefunds(now, batchSize);
    }

    @Override
    public List<OrderRefund> findAllByOrderIdWithProducts(UUID orderId) {
        return orderRefundPersistence.findAllByOrderIdWithProducts(orderId);
    }
}
