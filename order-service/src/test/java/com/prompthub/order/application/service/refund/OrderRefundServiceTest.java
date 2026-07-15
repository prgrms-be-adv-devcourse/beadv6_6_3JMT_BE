package com.prompthub.order.application.service.refund;

import com.prompthub.common.event.EventMessage;
import com.prompthub.order.application.event.refund.OrderRefundRequestedPayload;
import com.prompthub.order.application.service.event.OrderEventMessageFactory;
import com.prompthub.order.application.service.event.outbox.OutboxEventAppender;
import com.prompthub.order.domain.enums.OrderRefundStatus;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderPayment;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.domain.model.OrderRefund;
import com.prompthub.order.domain.repository.OrderPaymentRepository;
import com.prompthub.order.domain.repository.OrderRefundRepository;
import com.prompthub.order.domain.repository.OrderRepository;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import com.prompthub.order.infra.messaging.kafka.event.OrderEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static com.prompthub.order.fixture.OrderFixture.APPROVED_AT;
import static com.prompthub.order.fixture.OrderFixture.BUYER_ID;
import static com.prompthub.order.fixture.OrderFixture.PAYMENT_ID;
import static com.prompthub.order.fixture.OrderFixture.TOTAL_AMOUNT;
import static com.prompthub.order.fixture.OrderFixture.createPaidOrderWithProducts;
import static com.prompthub.order.fixture.OrderFixture.createPendingOrderWithProducts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class OrderRefundServiceTest {

	private static final UUID OTHER_BUYER_ID =
		UUID.fromString("00000000-0000-0000-0000-000000000777");
	private static final LocalDateTime REQUESTED_AT =
		LocalDateTime.of(2026, 7, 15, 12, 0);
	private static final int INITIAL_DELAY_MINUTES = 10;

	@Mock
	private OrderRepository orderRepository;

	@Mock
	private OrderPaymentRepository orderPaymentRepository;

	@Mock
	private OrderRefundRepository orderRefundRepository;

	@Mock
	private OrderEventMessageFactory orderEventMessageFactory;

	@Mock
	private OutboxEventAppender outboxEventAppender;

	private OrderRefundService orderRefundService;

	@BeforeEach
	void setUp() {
		Clock clock = Clock.fixed(
			Instant.parse("2026-07-15T12:00:00Z"),
			ZoneOffset.UTC
		);
		OrderRefundReconciliationPolicy policy = () -> INITIAL_DELAY_MINUTES;

		orderRefundService = new OrderRefundService(
			orderRepository,
			orderPaymentRepository,
			orderRefundRepository,
			orderEventMessageFactory,
			outboxEventAppender,
			policy,
			clock
		);
	}

	@Nested
	@DisplayName("단건 부분 환불 요청 성공")
	class Success {

		@Test
		@DisplayName("본인 PAID 주문의 미다운로드 상품을 요청 상태로 바꾸고 환불 이력과 Outbox를 저장한다")
		void requestRefund_paidOrderProduct_success() {
			Order order = createPaidOrderWithProducts();
			OrderProduct target = order.getOrderProducts().getFirst();
			OrderProduct untouched = order.getOrderProducts().getLast();
			OrderPayment payment = matchingPayment(order);
			givenValidRequest(order, payment, target);

			orderRefundService.requestRefund(BUYER_ID, order.getId(), PAYMENT_ID, target.getId());

			ArgumentCaptor<OrderRefund> refundCaptor = ArgumentCaptor.forClass(OrderRefund.class);
			then(orderRefundRepository).should().save(refundCaptor.capture());
			OrderRefund savedRefund = refundCaptor.getValue();

			assertThat(savedRefund.getOrderId()).isEqualTo(order.getId());
			assertThat(savedRefund.getPaymentId()).isEqualTo(PAYMENT_ID);
			assertThat(savedRefund.getBuyerId()).isEqualTo(BUYER_ID);
			assertThat(savedRefund.getStatus()).isEqualTo(OrderRefundStatus.REQUESTED);
			assertThat(savedRefund.getTotalRefundAmount()).isEqualTo(target.getProductAmount());
			assertThat(savedRefund.getRequestedAt()).isEqualTo(REQUESTED_AT);
			assertThat(savedRefund.getNextCheckAt()).isEqualTo(REQUESTED_AT.plusMinutes(INITIAL_DELAY_MINUTES));
			assertThat(savedRefund.getProduct().getOrderProductId()).isEqualTo(target.getId());
			assertThat(savedRefund.getProduct().getRefundAmount()).isEqualTo(target.getProductAmount());

			assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.REFUND_REQUESTED);
			assertThat(target.getOrderStatus()).isEqualTo(OrderStatus.REFUND_REQUESTED);
			assertThat(untouched.getOrderStatus()).isEqualTo(OrderStatus.PAID);

			ArgumentCaptor<OrderRefundRequestedPayload> payloadCaptor =
				ArgumentCaptor.forClass(OrderRefundRequestedPayload.class);
			then(orderEventMessageFactory).should()
				.createOrderRefundRequestedMessage(eq(order.getId()), payloadCaptor.capture());
			OrderRefundRequestedPayload payload = payloadCaptor.getValue();
			assertThat(payload.orderId()).isEqualTo(order.getId());
			assertThat(payload.orderProductId()).isEqualTo(target.getId());
			assertThat(payload.buyerId()).isEqualTo(BUYER_ID);
			assertThat(payload.refundAmount()).isEqualTo(target.getProductAmount());
			assertThat(payload.requestedAt()).isEqualTo(REQUESTED_AT);
			then(outboxEventAppender).should().append(any(EventMessage.class));
		}

		@Test
		@DisplayName("PARTIAL_REFUNDED 주문의 남은 PAID 상품도 순차 환불 요청할 수 있다")
		void requestRefund_partialRefundedOrderRemainingProduct_success() {
			Order order = createPaidOrderWithProducts();
			OrderProduct alreadyRefunded = order.getOrderProducts().getFirst();
			OrderProduct target = order.getOrderProducts().getLast();
			order.requestRefund(alreadyRefunded.getId());
			order.completeRefund(alreadyRefunded.getId(), REQUESTED_AT.minusMinutes(1));
			givenValidRequest(order, matchingPayment(order), target);

			orderRefundService.requestRefund(BUYER_ID, order.getId(), PAYMENT_ID, target.getId());

			assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.REFUND_REQUESTED);
			assertThat(alreadyRefunded.getOrderStatus()).isEqualTo(OrderStatus.REFUNDED);
			assertThat(target.getOrderStatus()).isEqualTo(OrderStatus.REFUND_REQUESTED);
			then(orderRefundRepository).should().save(any(OrderRefund.class));
			then(outboxEventAppender).should().append(any(EventMessage.class));
		}
	}

	@Nested
	@DisplayName("단건 부분 환불 요청 검증 실패")
	class Failure {

		@Test
		@DisplayName("주문이 없으면 O001을 반환하고 아무것도 저장하지 않는다")
		void requestRefund_orderNotFound_throwsException() {
			UUID orderId = UUID.randomUUID();
			given(orderRepository.findByIdWithOrderProducts(orderId)).willReturn(Optional.empty());

			assertError(
				() -> orderRefundService.requestRefund(BUYER_ID, orderId, PAYMENT_ID, UUID.randomUUID()),
				ErrorCode.ORDER_NOT_FOUND
			);
			assertNoWrites();
		}

		@Test
		@DisplayName("다른 구매자의 주문이면 O008을 반환한다")
		void requestRefund_notOwner_throwsException() {
			Order order = createPaidOrderWithProducts();
			given(orderRepository.findByIdWithOrderProducts(order.getId())).willReturn(Optional.of(order));

			assertError(
				() -> orderRefundService.requestRefund(
					OTHER_BUYER_ID,
					order.getId(),
					PAYMENT_ID,
					order.getOrderProducts().getFirst().getId()
				),
				ErrorCode.ORDER_ACCESS_DENIED
			);
			assertNoWrites();
		}

		@Test
		@DisplayName("paymentId로 로컬 결제를 찾을 수 없으면 O016을 반환한다")
		void requestRefund_paymentNotFound_throwsException() {
			Order order = createPaidOrderWithProducts();
			given(orderRepository.findByIdWithOrderProducts(order.getId())).willReturn(Optional.of(order));
			given(orderPaymentRepository.findByPaymentId(PAYMENT_ID)).willReturn(Optional.empty());

			assertError(
				() -> request(order, order.getOrderProducts().getFirst()),
				ErrorCode.ORDER_PAYMENT_NOT_FOUND
			);
			assertNoWrites();
		}

		@Test
		@DisplayName("결제의 주문 또는 구매자가 요청과 다르면 O016을 반환한다")
		void requestRefund_paymentOwnershipMismatch_throwsException() {
			Order order = createPaidOrderWithProducts();
			OrderPayment mismatchedPayment = OrderPayment.create(
				UUID.randomUUID(),
				PAYMENT_ID,
				OTHER_BUYER_ID,
				TOTAL_AMOUNT,
				APPROVED_AT
			);
			given(orderRepository.findByIdWithOrderProducts(order.getId())).willReturn(Optional.of(order));
			given(orderPaymentRepository.findByPaymentId(PAYMENT_ID)).willReturn(Optional.of(mismatchedPayment));

			assertError(
				() -> request(order, order.getOrderProducts().getFirst()),
				ErrorCode.ORDER_PAYMENT_NOT_FOUND
			);
			assertNoWrites();
		}

		@Test
		@DisplayName("결제 승인 금액과 주문 총액이 다르면 O014를 반환한다")
		void requestRefund_approvedAmountMismatch_throwsException() {
			Order order = createPaidOrderWithProducts();
			OrderPayment payment = OrderPayment.create(
				order.getId(),
				PAYMENT_ID,
				BUYER_ID,
				TOTAL_AMOUNT - 1,
				APPROVED_AT
			);
			given(orderRepository.findByIdWithOrderProducts(order.getId())).willReturn(Optional.of(order));
			given(orderPaymentRepository.findByPaymentId(PAYMENT_ID)).willReturn(Optional.of(payment));

			assertError(
				() -> request(order, order.getOrderProducts().getFirst()),
				ErrorCode.ORDER_PAYMENT_AMOUNT_MISMATCH
			);
			assertNoWrites();
		}

		@Test
		@DisplayName("주문에 속하지 않은 주문 상품이면 O012를 반환한다")
		void requestRefund_orderProductNotFound_throwsException() {
			Order order = createPaidOrderWithProducts();
			givenOrderAndPayment(order, matchingPayment(order));

			assertError(
				() -> orderRefundService.requestRefund(
					BUYER_ID,
					order.getId(),
					PAYMENT_ID,
					UUID.randomUUID()
				),
				ErrorCode.ORDER_PRODUCT_NOT_FOUND
			);
			assertNoWrites();
		}

		@Test
		@DisplayName("PAID 또는 PARTIAL_REFUNDED가 아닌 주문은 O017을 반환한다")
		void requestRefund_invalidOrderStatus_throwsException() {
			Order order = createPendingOrderWithProducts();
			givenOrderAndPayment(order, matchingPayment(order));

			assertError(
				() -> request(order, order.getOrderProducts().getFirst()),
				ErrorCode.ORDER_REFUND_NOT_ALLOWED
			);
			assertNoWrites();
		}

		@Test
		@DisplayName("이미 다운로드한 상품은 O017을 반환한다")
		void requestRefund_downloadedOrderProduct_throwsException() {
			Order order = createPaidOrderWithProducts();
			OrderProduct target = order.getOrderProducts().getFirst();
			target.markDownloaded();
			givenOrderAndPayment(order, matchingPayment(order));

			assertError(
				() -> request(order, target),
				ErrorCode.ORDER_REFUND_NOT_ALLOWED
			);
			assertNoWrites();
		}

		@Test
		@DisplayName("PAID가 아닌 상품은 O017을 반환한다")
		void requestRefund_invalidOrderProductStatus_throwsException() {
			Order order = createPaidOrderWithProducts();
			OrderProduct target = order.getOrderProducts().getFirst();
			order.requestRefund(target.getId());
			order.completeRefund(target.getId(), REQUESTED_AT.minusMinutes(1));
			givenOrderAndPayment(order, matchingPayment(order));

			assertError(
				() -> request(order, target),
				ErrorCode.ORDER_REFUND_NOT_ALLOWED
			);
			assertNoWrites();
		}

		@Test
		@DisplayName("같은 주문에 REQUESTED 요청이 있으면 O018을 반환한다")
		void requestRefund_requestInProgress_throwsException() {
			Order order = createPaidOrderWithProducts();
			OrderProduct requestedProduct = order.getOrderProducts().getFirst();
			OrderProduct target = order.getOrderProducts().getLast();
			order.requestRefund(requestedProduct.getId());
			givenOrderAndPayment(order, matchingPayment(order));
			given(orderRefundRepository.existsByOrderIdAndStatus(
				order.getId(),
				OrderRefundStatus.REQUESTED
			)).willReturn(true);

			assertError(
				() -> request(order, target),
				ErrorCode.ORDER_REFUND_IN_PROGRESS
			);
			then(orderRefundRepository).should(never()).save(any());
			then(orderEventMessageFactory).shouldHaveNoInteractions();
			then(outboxEventAppender).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("같은 주문 상품의 과거 환불 이력이 있으면 O017을 반환한다")
		void requestRefund_existingProductHistory_throwsException() {
			Order order = createPaidOrderWithProducts();
			OrderProduct target = order.getOrderProducts().getFirst();
			OrderRefund existingRefund = OrderRefund.request(
				order.getId(),
				PAYMENT_ID,
				BUYER_ID,
				target.getId(),
				target.getProductAmount(),
				REQUESTED_AT.minusMinutes(20),
				REQUESTED_AT.minusMinutes(10)
			);
			givenOrderAndPayment(order, matchingPayment(order));
			given(orderRefundRepository.existsByOrderIdAndStatus(
				order.getId(),
				OrderRefundStatus.REQUESTED
			)).willReturn(false);
			given(orderRefundRepository.findByPaymentIdAndOrderProductId(PAYMENT_ID, target.getId()))
				.willReturn(Optional.of(existingRefund));

			assertError(
				() -> request(order, target),
				ErrorCode.ORDER_REFUND_NOT_ALLOWED
			);
			assertNoWrites();
		}
	}

	private void givenValidRequest(Order order, OrderPayment payment, OrderProduct target) {
		givenOrderAndPayment(order, payment);
		given(orderRefundRepository.existsByOrderIdAndStatus(
			order.getId(),
			OrderRefundStatus.REQUESTED
		)).willReturn(false);
		given(orderRefundRepository.findByPaymentIdAndOrderProductId(PAYMENT_ID, target.getId()))
			.willReturn(Optional.empty());
		given(orderEventMessageFactory.createOrderRefundRequestedMessage(eq(order.getId()), any()))
			.willAnswer(invocation -> {
				OrderRefundRequestedPayload payload = invocation.getArgument(1);
				return new EventMessage<>(
					UUID.randomUUID(),
					OrderEventType.ORDER_REFUND_REQUESTED.code(),
					REQUESTED_AT,
					"ORDER",
					order.getId(),
					payload
				);
			});
	}

	private void givenOrderAndPayment(Order order, OrderPayment payment) {
		given(orderRepository.findByIdWithOrderProducts(order.getId())).willReturn(Optional.of(order));
		given(orderPaymentRepository.findByPaymentId(PAYMENT_ID)).willReturn(Optional.of(payment));
	}

	private OrderPayment matchingPayment(Order order) {
		return OrderPayment.create(
			order.getId(),
			PAYMENT_ID,
			BUYER_ID,
			order.getTotalOrderAmount(),
			APPROVED_AT
		);
	}

	private void request(Order order, OrderProduct target) {
		orderRefundService.requestRefund(BUYER_ID, order.getId(), PAYMENT_ID, target.getId());
	}

	private void assertError(Runnable invocation, ErrorCode expectedErrorCode) {
		assertThatThrownBy(invocation::run)
			.isInstanceOf(OrderException.class)
			.satisfies(exception -> assertThat(((OrderException) exception).getErrorCode())
				.isEqualTo(expectedErrorCode));
	}

	private void assertNoWrites() {
		then(orderRefundRepository).should(never()).save(any());
		then(orderEventMessageFactory).shouldHaveNoInteractions();
		then(outboxEventAppender).shouldHaveNoInteractions();
	}
}
