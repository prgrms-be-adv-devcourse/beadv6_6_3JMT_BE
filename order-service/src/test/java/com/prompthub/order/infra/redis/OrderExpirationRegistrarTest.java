package com.prompthub.order.infra.redis;

import com.prompthub.order.application.event.order.OrderCreatedEvent;
import com.prompthub.order.application.service.order.OrderExpirationStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.prompthub.order.fixture.OrderFixture.CREATED_AT;
import static com.prompthub.order.fixture.OrderFixture.ORDER_ID;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class OrderExpirationRegistrarTest {

	@Mock
	private OrderExpirationStore orderExpirationStore;

	@Test
	@DisplayName("주문 생성 이벤트를 받으면 Redis 만료 대상에 등록한다")
	void registerOrderExpiration_registersExpiration() {
		OrderExpirationRegistrar registrar = new OrderExpirationRegistrar(
			orderExpirationStore,
			new OrderExpirationProperties(true, 20, 5_000L, 100, 3)
		);

		registrar.registerOrderExpiration(new OrderCreatedEvent(ORDER_ID, CREATED_AT));

		then(orderExpirationStore).should().registerExpiration(ORDER_ID, CREATED_AT, 20);
	}
}
