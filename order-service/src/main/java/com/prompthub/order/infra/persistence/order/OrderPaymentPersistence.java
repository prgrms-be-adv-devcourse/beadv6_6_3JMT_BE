package com.prompthub.order.infra.persistence.order;

import com.prompthub.order.domain.model.OrderPayment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;
import java.util.Optional;

public interface OrderPaymentPersistence extends JpaRepository<OrderPayment, UUID>, OrderPaymentPersistenceCustom {

	boolean existsByOrderId(UUID orderId);

	boolean existsByPaymentId(UUID paymentId);

	Optional<OrderPayment> findByOrderId(UUID orderId);
}
