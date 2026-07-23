package com.prompthub.order.application.service.order;

import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderProductReservationService {

	private final OrderProductIdempotencyStore store;
	private final OrderProductIdempotencyPolicy policy;

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
				throw new OrderException(ErrorCode.ORDER_PRODUCT_ALREADY_OWNED);
			}
		} catch (OrderException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			log.warn("주문 상품 예약 저장소를 사용할 수 없습니다. orderId={}", order.getId(), exception);
			throw new OrderException(ErrorCode.ORDER_IDEMPOTENCY_STORE_UNAVAILABLE);
		}

		registerRollbackCleanup(order, productIds);
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

	private void registerRollbackCleanup(Order order, List<UUID> productIds) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			releaseQuietly(order, productIds);
			throw new IllegalStateException("상품 예약은 활성 트랜잭션 안에서만 생성할 수 있습니다.");
		}

		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCompletion(int status) {
				if (status != TransactionSynchronization.STATUS_COMMITTED) {
					releaseQuietly(order, productIds);
				}
			}
		});
	}

	private void releaseQuietly(Order order, List<UUID> productIds) {
		try {
			store.release(order.getBuyerId(), productIds, order.getId());
		} catch (RuntimeException exception) {
			log.warn("주문 상품 예약 롤백 정리에 실패했습니다. orderId={}", order.getId(), exception);
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
