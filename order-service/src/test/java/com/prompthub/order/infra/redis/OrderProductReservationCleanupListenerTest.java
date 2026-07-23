package com.prompthub.order.infra.redis;

import com.prompthub.order.application.event.order.OrderProductReservationCleanupRequestedEvent;
import com.prompthub.order.application.service.order.OrderProductIdempotencyStore;
import com.prompthub.order.domain.model.Order;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.prompthub.order.fixture.PaymentEventFixture.createdOrder;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class OrderProductReservationCleanupListenerTest {

	@Mock
	private OrderProductIdempotencyStore store;

	@Test
	@DisplayName("커밋 이후 주문 토큰으로 상품 예약을 해제한다")
	void cleanup_releasesWithOrderToken() {
		Order order = createdOrder();
		OrderProductReservationCleanupListener listener = new OrderProductReservationCleanupListener(store);
		OrderProductReservationCleanupRequestedEvent event =
			OrderProductReservationCleanupRequestedEvent.from(order);

		listener.cleanup(event);

		then(store).should().release(
			eq(order.getBuyerId()),
			eq(event.productIds()),
			eq(order.getId())
		);
	}

	@Test
	@DisplayName("예약 해제 Redis 장애는 주문 상태 처리에 영향을 주지 않도록 기록하고 종료한다")
	void cleanup_storeFailureIsSwallowed() {
		Order order = createdOrder();
		willThrow(new IllegalStateException("redis down"))
			.given(store).release(eq(order.getBuyerId()), eq(
			OrderProductReservationCleanupRequestedEvent.from(order).productIds()
		), eq(order.getId()));
		OrderProductReservationCleanupListener listener = new OrderProductReservationCleanupListener(store);

		listener.cleanup(OrderProductReservationCleanupRequestedEvent.from(order));

		then(store).should(times(1)).release(
			eq(order.getBuyerId()),
			eq(OrderProductReservationCleanupRequestedEvent.from(order).productIds()),
			eq(order.getId())
		);
	}
}
