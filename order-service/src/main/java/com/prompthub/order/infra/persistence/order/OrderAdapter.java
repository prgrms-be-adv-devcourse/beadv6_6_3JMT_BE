package com.prompthub.order.infra.persistence.order;

import com.prompthub.order.application.dto.OrderListProjection;
import com.prompthub.order.application.dto.OrderListProductProjection;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.repository.OrderRepository;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class OrderAdapter implements OrderRepository {
	private static final String PENDING_PRODUCT_UNIQUENESS =
		"uk_order_product_buyer_product_pending";

	private final OrderPersistence orderPersistence;
	private final OrderProductPersistence orderProductPersistence;

	@Override
	public Order saveAndFlush(Order order) {
		try {
			return orderPersistence.saveAndFlush(order);
		} catch (DataIntegrityViolationException exception) {
			if (hasConstraint(exception, PENDING_PRODUCT_UNIQUENESS)) {
				throw new OrderException(ErrorCode.ORDER_PRODUCT_ALREADY_OWNED);
			}
			throw exception;
		}
	}

	@Override
	public Optional<Order> findByIdWithOrderProducts(UUID orderId) {
		return orderPersistence.findByIdWithOrderProducts(orderId);
	}

	@Override
	public Optional<Order> findByIdWithOrderProductsForUpdate(UUID orderId) {
		if (orderPersistence.findByIdForUpdate(orderId).isEmpty()) {
			return Optional.empty();
		}
		orderProductPersistence.findAllByOrderIdForUpdate(orderId);
		return orderPersistence.findByIdWithOrderProducts(orderId);
	}

	@Override
	public Optional<Order> findByOrderNumber(String orderNumber) {
		return orderPersistence.findByOrderNumber(orderNumber);
	}

	@Override
	public boolean existsAccessiblePaidOrderProductByBuyerIdAndProductId(UUID buyerId, UUID productId) {
		return orderPersistence.existsAccessiblePaidOrderProductByBuyerIdAndProductId(buyerId, productId);
	}

	@Override
	public boolean isAccessiblePaidProductDownloaded(UUID buyerId, UUID productId) {
		return orderPersistence.isAccessiblePaidProductDownloaded(buyerId, productId);
	}

	@Override
	public boolean existsBlockingOrderProductByBuyerIdAndProductId(UUID buyerId, UUID productId) {
		return orderPersistence.existsBlockingOrderProductByBuyerIdAndProductId(buyerId, productId);
	}

	@Override
	public List<UUID> findExpiredCreatedOrderIds(LocalDateTime cutoff, int batchSize) {
		if (batchSize <= 0) {
			throw new IllegalArgumentException("batchSize must be positive");
		}
		return orderPersistence.findExpiredCreatedOrderIds(cutoff, PageRequest.of(0, batchSize));
	}

	@Override
	public List<UUID> findAccessiblePaidProductIdsByBuyerId(UUID buyerId) {
		return orderPersistence.findAccessiblePaidProductIdsByBuyerId(buyerId);
	}

	@Override
	public Page<OrderListProjection> searchOrders(
		UUID buyerId,
		OrderStatus status,
		LocalDateTime from,
		LocalDateTime to,
		Pageable pageable
	) {
		return orderPersistence.searchOrders(buyerId, status, from, to, pageable);
	}

	@Override
	public List<OrderListProductProjection> findOrderProductsByOrderIds(List<UUID> orderIds) {
		return orderPersistence.findOrderProductsByOrderIds(orderIds);
	}

	private boolean hasConstraint(Throwable failure, String expectedName) {
		Throwable cause = failure;
		while (cause != null) {
			if (cause instanceof ConstraintViolationException violation
				&& expectedName.equalsIgnoreCase(violation.getConstraintName())) {
				return true;
			}
			cause = cause.getCause();
		}
		return false;
	}
}
