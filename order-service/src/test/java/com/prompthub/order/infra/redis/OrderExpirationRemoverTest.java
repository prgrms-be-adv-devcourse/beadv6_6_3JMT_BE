package com.prompthub.order.infra.redis;

import com.prompthub.order.application.event.order.OrderPaidEvent;
import com.prompthub.order.application.service.order.OrderExpirationStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static com.prompthub.order.fixture.PaymentEventFixture.ORDER_A;
import static com.prompthub.order.fixture.PaymentEventFixture.ORDER_B;
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
	void removeOrderExpiration_continuesAfterOneOrderFails() {
		willThrow(new RuntimeException("redis unavailable"))
			.given(orderExpirationStore).removeExpiration(ORDER_A);

		remover.removeOrderExpiration(new OrderPaidEvent(List.of(ORDER_A, ORDER_B)));

		then(orderExpirationStore).should().removeExpiration(ORDER_A);
		then(orderExpirationStore).should(never()).clearRetryCount(ORDER_A);
		then(orderExpirationStore).should().removeExpiration(ORDER_B);
		then(orderExpirationStore).should().clearRetryCount(ORDER_B);
	}
}
