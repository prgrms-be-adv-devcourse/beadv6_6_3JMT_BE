package com.prompthub.order.application.service.order;

import com.prompthub.order.domain.model.Order;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;

import static com.prompthub.order.fixture.PaymentEventFixture.BUYER_ID;
import static com.prompthub.order.fixture.PaymentEventFixture.ORDER_A;
import static com.prompthub.order.fixture.PaymentEventFixture.createdOrder;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class OrderProductReservationServiceTest {

	private static final Duration TTL = Duration.ofMinutes(30);

	@Mock
	private OrderProductIdempotencyStore store;

	@Mock
	private OrderProductIdempotencyPolicy policy;

	@AfterEach
	void tearDownSynchronization() {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.clearSynchronization();
		}
	}

	@Test
	@DisplayName("예약 키를 선점하지 못하면 O018을 반환한다")
	void reserve_conflictThrowsAlreadyOwned() {
		Order order = createdOrder();
		given(policy.ttl()).willReturn(TTL);
		given(store.acquire(eq(BUYER_ID), anyCollection(), eq(order.getId()), eq(TTL))).willReturn(false);
		OrderProductReservationService service = new OrderProductReservationService(store, policy);

		assertThatThrownBy(() -> service.reserve(order))
			.isInstanceOf(OrderException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.ORDER_PRODUCT_ALREADY_OWNED);

		then(store).should(never()).release(any(), anyCollection(), any());
	}

	@Test
	@DisplayName("Redis 장애는 SYS003으로 변환한다")
	void reserve_storeFailureThrowsUnavailable() {
		Order order = createdOrder();
		given(policy.ttl()).willReturn(TTL);
		given(store.acquire(eq(BUYER_ID), anyCollection(), eq(order.getId()), eq(TTL)))
			.willThrow(new IllegalStateException("redis down"));
		OrderProductReservationService service = new OrderProductReservationService(store, policy);

		assertThatThrownBy(() -> service.reserve(order))
			.isInstanceOf(OrderException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.ORDER_IDEMPOTENCY_STORE_UNAVAILABLE);
	}

	@Test
	@DisplayName("트랜잭션 롤백 시 예약 토큰을 해제한다")
	void reserve_rollbackReleasesReservation() {
		Order order = createdOrder();
		given(policy.ttl()).willReturn(TTL);
		given(store.acquire(eq(BUYER_ID), anyCollection(), eq(order.getId()), eq(TTL))).willReturn(true);
		OrderProductReservationService service = new OrderProductReservationService(store, policy);
		TransactionSynchronizationManager.initSynchronization();

		service.reserve(order);
		TransactionSynchronization synchronization = TransactionSynchronizationManager.getSynchronizations().getFirst();
		synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

		then(store).should().release(eq(BUYER_ID), anyCollection(), eq(order.getId()));
	}

	@Test
	@DisplayName("커밋된 주문은 예약 롤백 정리를 실행하지 않는다")
	void reserve_commitKeepsReservation() {
		Order order = createdOrder();
		given(policy.ttl()).willReturn(TTL);
		given(store.acquire(eq(BUYER_ID), anyCollection(), eq(order.getId()), eq(TTL))).willReturn(true);
		OrderProductReservationService service = new OrderProductReservationService(store, policy);
		TransactionSynchronizationManager.initSynchronization();

		service.reserve(order);
		TransactionSynchronizationManager.getSynchronizations().getFirst()
			.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);

		then(store).should(never()).release(any(), anyCollection(), any());
	}

	@Test
	@DisplayName("예약 존재 조회 중 Redis 장애는 SYS003으로 변환한다")
	void isReserved_storeFailureThrowsUnavailable() {
		given(store.exists(BUYER_ID, ORDER_A)).willThrow(new IllegalStateException("redis down"));
		OrderProductReservationService service = new OrderProductReservationService(store, policy);

		assertThatThrownBy(() -> service.isReserved(BUYER_ID, ORDER_A))
			.isInstanceOf(OrderException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.ORDER_IDEMPOTENCY_STORE_UNAVAILABLE);
	}

	@Test
	@DisplayName("예약 존재 여부를 저장소에 위임한다")
	void isReserved_delegatesToStore() {
		given(store.exists(BUYER_ID, ORDER_A)).willReturn(true);
		OrderProductReservationService service = new OrderProductReservationService(store, policy);

		assertThat(service.isReserved(BUYER_ID, ORDER_A)).isTrue();
	}
}
