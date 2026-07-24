package com.prompthub.order.application.service.order;

import com.prompthub.order.domain.model.Order;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static com.prompthub.order.application.service.order.OrderProductReservationMetrics.ReservationOutcome.CONFLICT;
import static com.prompthub.order.application.service.order.OrderProductReservationMetrics.ReservationOutcome.ERROR;
import static com.prompthub.order.application.service.order.OrderProductReservationMetrics.ReservationOutcome.SUCCESS;
import static com.prompthub.order.fixture.PaymentEventFixture.BUYER_ID;
import static com.prompthub.order.fixture.PaymentEventFixture.ORDER_A;
import static com.prompthub.order.fixture.PaymentEventFixture.createdOrder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class OrderProductReservationServiceTest {

	private static final Duration TTL = Duration.ofMinutes(30);

	@Mock
	private OrderProductIdempotencyStore store;

	@Mock
	private OrderProductIdempotencyPolicy policy;

	@Mock
	private OrderProductReservationMetrics metrics;

	@InjectMocks
	private OrderProductReservationService service;

	@Test
	@DisplayName("예약 키를 선점하지 못하면 O018을 반환한다")
	void reserve_conflictThrowsAlreadyOwned() {
		Order order = createdOrder();
		given(policy.ttl()).willReturn(TTL);
		given(store.acquire(eq(BUYER_ID), anyCollection(), eq(order.getId()), eq(TTL)))
			.willReturn(false);

		assertThatThrownBy(() -> service.reserve(order))
			.isInstanceOf(OrderException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.ORDER_PRODUCT_ALREADY_OWNED);

		then(store).should(never()).release(eq(BUYER_ID), anyCollection(), eq(order.getId()));
		then(metrics).should().recordAttempt(CONFLICT);
	}

	@Test
	@DisplayName("Redis 장애는 SYS003으로 변환한다")
	void reserve_storeFailureThrowsUnavailable() {
		Order order = createdOrder();
		given(policy.ttl()).willReturn(TTL);
		given(store.acquire(eq(BUYER_ID), anyCollection(), eq(order.getId()), eq(TTL)))
			.willThrow(new IllegalStateException("redis down"));

		assertThatThrownBy(() -> service.reserve(order))
			.isInstanceOf(OrderException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.ORDER_IDEMPOTENCY_STORE_UNAVAILABLE);

		then(metrics).should().recordAttempt(ERROR);
	}

	@Test
	@DisplayName("트랜잭션 동기화 없이 예약에 성공한다")
	void reserve_successDoesNotRequireTransactionSynchronization() {
		Order order = createdOrder();
		given(policy.ttl()).willReturn(TTL);
		given(store.acquire(
			eq(BUYER_ID), anyCollection(), eq(order.getId()), eq(TTL)
		)).willReturn(true);

		assertThatCode(() -> service.reserve(order)).doesNotThrowAnyException();

		then(metrics).should().recordAttempt(SUCCESS);
	}

	@Test
	@DisplayName("성공 지표 기록 실패는 예약 성공을 변경하지 않는다")
	void reserve_successMetricFailure_doesNotAlterSuccess() {
		Order order = createdOrder();
		given(policy.ttl()).willReturn(TTL);
		given(store.acquire(
			eq(BUYER_ID), anyCollection(), eq(order.getId()), eq(TTL)
		)).willReturn(true);
		willThrow(new IllegalStateException("metrics down"))
			.given(metrics).recordAttempt(SUCCESS);

		assertThatCode(() -> service.reserve(order)).doesNotThrowAnyException();

		then(metrics).should(never()).recordAttempt(ERROR);
	}

	@Test
	@DisplayName("충돌 지표 기록 실패는 O018을 변경하지 않는다")
	void reserve_conflictMetricFailure_preservesAlreadyOwned() {
		Order order = createdOrder();
		given(policy.ttl()).willReturn(TTL);
		given(store.acquire(eq(BUYER_ID), anyCollection(), eq(order.getId()), eq(TTL)))
			.willReturn(false);
		willThrow(new IllegalStateException("metrics down"))
			.given(metrics).recordAttempt(CONFLICT);

		assertThatThrownBy(() -> service.reserve(order))
			.isInstanceOf(OrderException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.ORDER_PRODUCT_ALREADY_OWNED);

		then(metrics).should(never()).recordAttempt(ERROR);
	}

	@Test
	@DisplayName("오류 지표 기록 실패는 Redis 장애의 SYS003 변환을 변경하지 않는다")
	void reserve_errorMetricFailure_preservesUnavailable() {
		Order order = createdOrder();
		given(policy.ttl()).willReturn(TTL);
		given(store.acquire(eq(BUYER_ID), anyCollection(), eq(order.getId()), eq(TTL)))
			.willThrow(new IllegalStateException("redis down"));
		willThrow(new IllegalStateException("metrics down"))
			.given(metrics).recordAttempt(ERROR);

		assertThatThrownBy(() -> service.reserve(order))
			.isInstanceOf(OrderException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.ORDER_IDEMPOTENCY_STORE_UNAVAILABLE);
	}

	@Test
	@DisplayName("주문 생성 실패 시 주문 토큰으로 예약을 해제한다")
	void releaseAfterFailure_releasesWithOrderToken() {
		Order order = createdOrder();

		service.releaseAfterFailure(order);

		then(store).should().release(
			eq(BUYER_ID), anyCollection(), eq(order.getId())
		);
	}

	@Test
	@DisplayName("실패 보상 중 Redis 장애는 삼킨다")
	void releaseAfterFailure_storeFailureIsSwallowed() {
		Order order = createdOrder();
		willThrow(new IllegalStateException("redis down"))
			.given(store).release(
				eq(BUYER_ID), anyCollection(), eq(order.getId())
			);

		assertThatCode(() -> service.releaseAfterFailure(order))
			.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("예약 존재 조회 중 Redis 장애는 SYS003으로 변환한다")
	void isReserved_storeFailureThrowsUnavailable() {
		given(store.exists(BUYER_ID, ORDER_A)).willThrow(new IllegalStateException("redis down"));

		assertThatThrownBy(() -> service.isReserved(BUYER_ID, ORDER_A))
			.isInstanceOf(OrderException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.ORDER_IDEMPOTENCY_STORE_UNAVAILABLE);
	}

	@Test
	@DisplayName("예약 존재 여부를 저장소에 위임한다")
	void isReserved_delegatesToStore() {
		given(store.exists(BUYER_ID, ORDER_A)).willReturn(true);

		assertThat(service.isReserved(BUYER_ID, ORDER_A)).isTrue();
	}
}
