package com.prompthub.order.domain.repository;

import com.prompthub.order.application.dto.OrderPaymentListProjection;
import com.prompthub.order.domain.model.OrderPayment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface OrderPaymentRepository {

	OrderPayment save(OrderPayment orderPayment);

	boolean existsByOrderId(UUID orderId);

	Page<OrderPaymentListProjection> searchOrderPayments(UUID buyerId, Pageable pageable);
}
