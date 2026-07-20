package com.prompthub.order.infra.redis;

import com.prompthub.order.application.event.order.OrderExpirationCleanupRequestedEvent;
import com.prompthub.order.application.service.order.OrderExpirationStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;

import static com.prompthub.order.fixture.PaymentEventFixture.ORDER_A;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class OrderExpirationCleanupListenerTest {

	@Mock
	private OrderExpirationStore orderExpirationStore;

	@InjectMocks
	private OrderExpirationCleanupListener listener;

	@Test
	@DisplayName("보상 커밋 후 만료 대상과 재시도 횟수를 제거한다")
	void cleanup_removesExpirationAndRetryCount() {
		listener.cleanup(new OrderExpirationCleanupRequestedEvent(ORDER_A));

		then(orderExpirationStore).should().removeExpiration(ORDER_A);
		then(orderExpirationStore).should().clearRetryCount(ORDER_A);
	}

	@Test
	@DisplayName("Redis 정리 실패는 이미 커밋된 DB 보상으로 전파하지 않는다")
	void cleanup_redisFailureIsSwallowed() {
		willThrow(new RuntimeException("redis unavailable"))
			.given(orderExpirationStore).removeExpiration(ORDER_A);

		assertThatCode(() -> listener.cleanup(new OrderExpirationCleanupRequestedEvent(ORDER_A)))
			.doesNotThrowAnyException();

		then(orderExpirationStore).should(never()).clearRetryCount(ORDER_A);
	}

	@Test
	@DisplayName("cleanup은 DB 커밋 이후에만 실행되도록 선언한다")
	void cleanupRunsAfterCommit() throws Exception {
		Method cleanup = OrderExpirationCleanupListener.class.getDeclaredMethod(
			"cleanup",
			OrderExpirationCleanupRequestedEvent.class
		);
		TransactionalEventListener annotation = AnnotatedElementUtils.findMergedAnnotation(
			cleanup,
			TransactionalEventListener.class
		);

		assertThat(annotation).isNotNull();
		assertThat(annotation.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
	}
}
