package com.prompthub.order.infra.redis;

import com.prompthub.order.application.event.order.OrderPaidEvent;
import com.prompthub.order.application.service.order.OrderExpirationStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.prompthub.order.fixture.PaymentEventFixture.ORDER_A;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class OrderExpirationRemoverTest {

	@Mock
	private OrderExpirationStore orderExpirationStore;

	@InjectMocks
	private OrderExpirationRemover remover;

	@Test
	void removeOrderExpiration_removesSingleOrderExpirationAndRetryCount() {
		remover.removeOrderExpiration(new OrderPaidEvent(ORDER_A));

		then(orderExpirationStore).should().removeExpiration(ORDER_A);
		then(orderExpirationStore).should().clearRetryCount(ORDER_A);
	}

	@Test
	void removeOrderExpiration_whenExpirationRemovalFails_skipsRetryClear() {
		willThrow(new RuntimeException("redis unavailable"))
			.given(orderExpirationStore).removeExpiration(ORDER_A);

		remover.removeOrderExpiration(new OrderPaidEvent(ORDER_A));

		then(orderExpirationStore).should(never()).clearRetryCount(ORDER_A);
	}
}
