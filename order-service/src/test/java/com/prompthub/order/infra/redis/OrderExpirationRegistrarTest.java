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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class OrderExpirationRegistrarTest {

	@Mock
	private OrderExpirationStore orderExpirationStore;

	@Test
	@DisplayName("주문 생성 이벤트의 단일 주문을 Redis 만료 대상에 한 번 등록한다")
	void registerOrderExpirationRegistersOrder() {
		OrderExpirationRegistrar registrar = new OrderExpirationRegistrar(
			orderExpirationStore,
			new OrderExpirationProperties(true, 20, 5_000L, 100, 3, 30)
		);
		OrderCreatedEvent event = new OrderCreatedEvent(ORDER_ID, CREATED_AT);

		registrar.registerOrderExpiration(event);

		then(orderExpirationStore).should().registerExpiration(ORDER_ID, CREATED_AT, 20);
		then(orderExpirationStore).should(times(1))
			.registerExpiration(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.eq(20));
	}

	@Test
	@DisplayName("Redis 만료 등록 실패는 DB reconciliation을 위해 전파하지 않는다")
	void registerOrderExpiration_storeFailureIsSwallowed() {
		OrderExpirationRegistrar registrar = new OrderExpirationRegistrar(
			orderExpirationStore,
			new OrderExpirationProperties(true, 20, 5_000L, 100, 3, 30)
		);
		willThrow(new IllegalStateException("redis down"))
			.given(orderExpirationStore).registerExpiration(ORDER_ID, CREATED_AT, 20);

		assertThatCode(() -> registrar.registerOrderExpiration(new OrderCreatedEvent(ORDER_ID, CREATED_AT)))
			.doesNotThrowAnyException();
	}
}
