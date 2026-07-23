package com.prompthub.order.application.service.order;

import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

import static com.prompthub.order.application.service.order.OrderProductReservationMetrics.ReservationOutcome.CONFLICT;
import static com.prompthub.order.application.service.order.OrderProductReservationMetrics.ReservationOutcome.ERROR;
import static com.prompthub.order.application.service.order.OrderProductReservationMetrics.ReservationOutcome.SUCCESS;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderProductReservationService {

	private final OrderProductIdempotencyStore store;
	private final OrderProductIdempotencyPolicy policy;
	private final OrderProductReservationMetrics metrics;

	public void reserve(Order order) {
		List<UUID> productIds = productIds(order);
		try {
			boolean acquired = store.acquire(
				order.getBuyerId(),
				productIds,
				order.getId(),
				policy.ttl()
			);
			if (!acquired) {
				metrics.recordAttempt(CONFLICT);
				throw new OrderException(ErrorCode.ORDER_PRODUCT_ALREADY_OWNED);
			}
			metrics.recordAttempt(SUCCESS);
		} catch (OrderException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			metrics.recordAttempt(ERROR);
			log.warn("주문 상품 예약 저장소를 사용할 수 없습니다. orderId={}", order.getId(), exception);
			throw new OrderException(ErrorCode.ORDER_IDEMPOTENCY_STORE_UNAVAILABLE);
		}
	}

	public void releaseAfterFailure(Order order) {
		try {
			store.release(
				order.getBuyerId(),
				productIds(order),
				order.getId()
			);
		} catch (RuntimeException exception) {
			log.warn(
				"주문 생성 실패 후 상품 예약 정리에 실패했습니다. orderId={}",
				order.getId(),
				exception
			);
		}
	}

	public boolean isReserved(UUID buyerId, UUID productId) {
		try {
			return store.exists(buyerId, productId);
		} catch (RuntimeException exception) {
			log.warn(
				"주문 상품 예약 조회 저장소를 사용할 수 없습니다. buyerId={}, productId={}",
				buyerId,
				productId,
				exception
			);
			throw new OrderException(ErrorCode.ORDER_IDEMPOTENCY_STORE_UNAVAILABLE);
		}
	}

	private List<UUID> productIds(Order order) {
		return order.getOrderProducts().stream()
			.map(OrderProduct::getProductId)
			.distinct()
			.sorted()
			.toList();
	}
}
