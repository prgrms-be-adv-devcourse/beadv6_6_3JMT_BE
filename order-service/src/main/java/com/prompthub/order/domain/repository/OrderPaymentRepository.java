package com.prompthub.order.domain.repository;

import com.prompthub.order.application.dto.OrderPaymentListProjection;
import com.prompthub.order.domain.model.OrderPayment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;
import java.util.Optional;

public interface OrderPaymentRepository {

	OrderPayment save(OrderPayment orderPayment);

	boolean existsByOrderId(UUID orderId);

	boolean existsByPaymentId(UUID paymentId);

	Optional<OrderPayment> findByOrderId(UUID orderId);

	Page<OrderPaymentListProjection> searchOrderPayments(UUID buyerId, Pageable pageable);
}
