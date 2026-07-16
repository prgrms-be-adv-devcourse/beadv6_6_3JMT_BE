package com.prompthub.order.infra.redis;

import com.prompthub.order.application.event.order.OrderCreatedEvent;
import com.prompthub.order.application.service.order.OrderExpirationStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static com.prompthub.order.fixture.OrderFixture.CREATED_AT;
import static com.prompthub.order.fixture.OrderFixture.ORDER_ID;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class OrderExpirationRegistrarTest {

	@Mock
	private OrderExpirationStore orderExpirationStore;

	@Test
	@DisplayName("주문 생성 이벤트의 모든 주문을 Redis 만료 대상에 한 번씩 등록한다")
	void registerOrderExpirationRegistersEveryOrder() {
		OrderExpirationRegistrar registrar = new OrderExpirationRegistrar(
			orderExpirationStore,
			new OrderExpirationProperties(true, 20, 5_000L, 100, 3)
		);
		UUID secondOrderId = UUID.fromString("00000000-0000-0000-0000-000000000502");
		UUID thirdOrderId = UUID.fromString("00000000-0000-0000-0000-000000000503");
		OrderCreatedEvent event = new OrderCreatedEvent(List.of(
			new OrderCreatedEvent.Item(ORDER_ID, CREATED_AT),
			new OrderCreatedEvent.Item(secondOrderId, CREATED_AT.plusNanos(1)),
			new OrderCreatedEvent.Item(thirdOrderId, CREATED_AT.plusNanos(2))
		));

		registrar.registerOrderExpiration(event);

		then(orderExpirationStore).should().registerExpiration(ORDER_ID, CREATED_AT, 20);
		then(orderExpirationStore).should()
			.registerExpiration(secondOrderId, CREATED_AT.plusNanos(1), 20);
		then(orderExpirationStore).should()
			.registerExpiration(thirdOrderId, CREATED_AT.plusNanos(2), 20);
		then(orderExpirationStore).should(times(3))
			.registerExpiration(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.eq(20));
	}
}
