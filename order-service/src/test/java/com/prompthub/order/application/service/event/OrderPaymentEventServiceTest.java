package com.prompthub.order.application.service.event;

import com.prompthub.order.application.event.payment.PaymentApprovedEvent;
import com.prompthub.order.application.event.payment.PaymentRefundedEvent;
import com.prompthub.order.application.service.event.outbox.OutboxEventAppender;
import com.prompthub.order.application.service.order.OrderExpirationStore;
import com.prompthub.order.application.service.order.OrderPolicyService;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderPayment;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.domain.repository.OrderPaymentRepository;
import com.prompthub.order.domain.repository.OrderRepository;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static com.prompthub.order.fixture.OrderFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import org.mockito.BDDMockito;

@ExtendWith(MockitoExtension.class)
class OrderPaymentEventServiceTest {

	@Mock
	private OrderRepository orderRepository;

	@Mock
	private OrderPaymentRepository orderPaymentRepository;

	@Mock
	private OutboxEventAppender outboxEventAppender;

	@Mock
	private OrderExpirationStore orderExpirationStore;

	@Spy
	private OrderPolicyService orderPolicyService;

	@InjectMocks
	private OrderPaymentEventService orderPaymentEventService;

	@Nested
	@DisplayName("결제 승인 이벤트 처리")
	class HandlePaymentApproved {

		@Test
		@DisplayName("승인 이벤트를 받으면 주문/주문상품을 PAID로 변경하고 결제내역과 ORDER_PAID Outbox를 저장한다")
		void handlePaymentApproved_success() {
			Order order = createPendingOrderWithProducts();
			
			PaymentApprovedEvent event = createPaymentApprovedEvent(order.getId(), TOTAL_AMOUNT);

			given(orderRepository.findByIdWithOrderProducts(event.orderId()))
				.willReturn(Optional.of(order));
			given(orderPaymentRepository.existsByPaymentId(event.paymentId()))
				.willReturn(false);

			ArgumentCaptor<OrderPayment> paymentCaptor = ArgumentCaptor.forClass(OrderPayment.class);

			orderPaymentEventService.handlePaymentApproved(event);

			assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PAID);
			assertThat(order.getPaidAt()).isEqualTo(APPROVED_AT);
			assertThat(order.getOrderProducts())
				.extracting(OrderProduct::getOrderStatus)
				.containsOnly(OrderStatus.PAID);

			then(orderPaymentRepository).should().save(paymentCaptor.capture());
			OrderPayment savedPayment = paymentCaptor.getValue();
			assertThat(savedPayment.getOrderId()).isEqualTo(order.getId());
			assertThat(savedPayment.getPaymentId()).isEqualTo(PAYMENT_ID);
			assertThat(savedPayment.getBuyerId()).isEqualTo(BUYER_ID);
			assertThat(savedPayment.getApprovedAmount()).isEqualTo(TOTAL_AMOUNT);
			assertThat(savedPayment.getApprovedAt()).isEqualTo(APPROVED_AT);

			then(outboxEventAppender).should().appendOrderPaid(order, event);
			then(orderExpirationStore).should().removeExpiration(order.getId());
		}

		@Test
		@DisplayName("승인 금액이 주문 금액과 다르면 상태 변경과 저장을 하지 않는다")
		void handlePaymentApproved_amountMismatch_throwsException() {
			Order order = createPendingOrderWithProducts();
			PaymentApprovedEvent event = createPaymentApprovedEvent(order.getId(), PRODUCT_AMOUNT_2);

			given(orderRepository.findByIdWithOrderProducts(event.orderId()))
				.willReturn(Optional.of(order));
			given(orderPaymentRepository.existsByPaymentId(event.paymentId()))
				.willReturn(false);

			assertThatThrownBy(() -> orderPaymentEventService.handlePaymentApproved(event))
				.isInstanceOf(OrderException.class)
				.satisfies(exception -> assertThat(((OrderException) exception).getErrorCode())
					.isEqualTo(ErrorCode.ORDER_PAYMENT_AMOUNT_MISMATCH));

			assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
			assertThat(order.getOrderProducts())
				.extracting(OrderProduct::getOrderStatus)
				.containsOnly(OrderStatus.PENDING);
			then(orderPaymentRepository).should(never()).save(any(OrderPayment.class));
			then(outboxEventAppender).should(never()).appendOrderPaid(any(Order.class), any(PaymentApprovedEvent.class));
			then(orderExpirationStore).should(never()).removeExpiration(any());
		}

		@Test
		@DisplayName("이미 결제 완료된 주문과 같은 paymentId 결제내역이 있으면 승인 이벤트를 중복으로 보고 무시한다")
		void handlePaymentApproved_duplicatePaymentIdForPaidOrder_doNothing() {
			Order order = createPaidOrderWithProducts();
			PaymentApprovedEvent event = createPaymentApprovedEvent(order.getId(), TOTAL_AMOUNT);

			given(orderRepository.findByIdWithOrderProducts(event.orderId()))
				.willReturn(Optional.of(order));
			given(orderPaymentRepository.existsByPaymentId(event.paymentId()))
				.willReturn(true);

			orderPaymentEventService.handlePaymentApproved(event);

			assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PAID);
			then(orderPaymentRepository).should().existsByPaymentId(event.paymentId());
			then(orderPaymentRepository).should(never()).save(any(OrderPayment.class));
			then(outboxEventAppender).should(never()).appendOrderPaid(any(Order.class), any(PaymentApprovedEvent.class));
			then(orderExpirationStore).should(never()).removeExpiration(any());
		}

		@Test
		@DisplayName("PAID 주문이어도 paymentId 결제내역이 없으면 승인 이벤트를 중복으로 보지 않고 예외를 발생시킨다")
		void handlePaymentApproved_paidOrderWithoutSamePaymentId_throwsAlreadyProcessed() {
			Order order = createPaidOrderWithProducts();
			PaymentApprovedEvent event = createPaymentApprovedEvent(order.getId(), TOTAL_AMOUNT);

			given(orderRepository.findByIdWithOrderProducts(event.orderId()))
				.willReturn(Optional.of(order));
			given(orderPaymentRepository.existsByPaymentId(event.paymentId()))
				.willReturn(false);

			assertThatThrownBy(() -> orderPaymentEventService.handlePaymentApproved(event))
				.isInstanceOf(OrderException.class)
				.satisfies(exception -> assertThat(((OrderException) exception).getErrorCode())
					.isEqualTo(ErrorCode.ORDER_ALREADY_PROCESSED));

			then(orderPaymentRepository).should().existsByPaymentId(event.paymentId());
			then(orderPaymentRepository).should(never()).save(any(OrderPayment.class));
			then(outboxEventAppender).should(never()).appendOrderPaid(any(Order.class), any(PaymentApprovedEvent.class));
		}

		@Test
		@DisplayName("승인 이벤트의 주문 ID가 존재하지 않으면 예외가 발생한다")
		void handlePaymentApproved_orderNotFound_throwsException() {
			PaymentApprovedEvent event = createPaymentApprovedEvent(ORDER_ID, TOTAL_AMOUNT);

			given(orderRepository.findByIdWithOrderProducts(event.orderId()))
				.willReturn(Optional.empty());

			assertThatThrownBy(() -> orderPaymentEventService.handlePaymentApproved(event))
				.isInstanceOf(OrderException.class)
				.satisfies(exception -> assertThat(((OrderException) exception).getErrorCode())
					.isEqualTo(ErrorCode.ORDER_NOT_FOUND));

			then(orderPaymentRepository).should(never()).save(any(OrderPayment.class));
			then(outboxEventAppender).should(never()).appendOrderPaid(any(Order.class), any(PaymentApprovedEvent.class));
		}

		@Test
		@DisplayName("Outbox 저장 시 예외가 발생하면 예외가 상위로 전파되어 전체 롤백을 유도한다")
		void handlePaymentApproved_outboxSaveFails_throwsException() {
			Order order = createPendingOrderWithProducts();
			PaymentApprovedEvent event = createPaymentApprovedEvent(order.getId(), TOTAL_AMOUNT);

			given(orderRepository.findByIdWithOrderProducts(event.orderId()))
				.willReturn(Optional.of(order));
			given(orderPaymentRepository.existsByPaymentId(event.paymentId()))
				.willReturn(false);
			given(outboxEventAppender.appendOrderPaid(any(Order.class), any(PaymentApprovedEvent.class)))
				.willThrow(new RuntimeException("DB Connection Error"));

			assertThatThrownBy(() -> orderPaymentEventService.handlePaymentApproved(event))
				.isInstanceOf(RuntimeException.class)
				.hasMessage("DB Connection Error");

			then(orderExpirationStore).should(never()).removeExpiration(any());
		}

		@Test
		@DisplayName("Redis 만료 대상 제거 중 예외가 발생해도 결제 승인 처리는 성공한다")
		void handlePaymentApproved_expirationRemoveFails_doesNotThrow() {
			Order order = createPendingOrderWithProducts();
			PaymentApprovedEvent event = createPaymentApprovedEvent(order.getId(), TOTAL_AMOUNT);

			given(orderRepository.findByIdWithOrderProducts(event.orderId()))
				.willReturn(Optional.of(order));
			given(orderPaymentRepository.existsByPaymentId(event.paymentId()))
				.willReturn(false);
			BDDMockito.willThrow(new RuntimeException("Redis Timeout"))
				.given(orderExpirationStore).removeExpiration(order.getId());

			orderPaymentEventService.handlePaymentApproved(event);

			assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PAID);
			then(orderPaymentRepository).should().save(any(OrderPayment.class));
			then(outboxEventAppender).should().appendOrderPaid(any(Order.class), any(PaymentApprovedEvent.class));
		}

		@Test
		@DisplayName("다른 최종 상태 주문에 승인 이벤트가 들어오면 이미 처리된 주문 예외가 발생한다")
		void handlePaymentApproved_terminalOrder_throwsAlreadyProcessed() {
			Order order = createCanceledOrderWithProducts();
			PaymentApprovedEvent event = createPaymentApprovedEvent(order.getId(), TOTAL_AMOUNT);

			given(orderRepository.findByIdWithOrderProducts(event.orderId()))
				.willReturn(Optional.of(order));
			given(orderPaymentRepository.existsByPaymentId(event.paymentId()))
				.willReturn(false);

			assertThatThrownBy(() -> orderPaymentEventService.handlePaymentApproved(event))
				.isInstanceOf(OrderException.class)
				.satisfies(exception -> assertThat(((OrderException) exception).getErrorCode())
					.isEqualTo(ErrorCode.ORDER_ALREADY_PROCESSED));
		}
	}



	@Nested
	@DisplayName("결제 환불 이벤트 처리")
	class HandlePaymentRefunded {

		@Test
		@DisplayName("환불 이벤트를 받으면 PAID 주문/주문상품을 REFUNDED로 변경하고 ORDER_REFUND Outbox를 저장한다")
		void handlePaymentRefunded_success() {
			Order order = createPaidOrderWithProducts();
			PaymentRefundedEvent event = createPaymentRefundedEvent(order.getId());

			given(orderRepository.findByIdWithOrderProducts(event.orderId()))
				.willReturn(Optional.of(order));

			orderPaymentEventService.handlePaymentRefunded(event);

			assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.REFUNDED);
			assertThat(order.getRefundedAt()).isEqualTo(REFUNDED_AT);
			assertThat(order.getOrderProducts())
				.extracting(OrderProduct::getOrderStatus)
				.containsOnly(OrderStatus.REFUNDED);
			then(outboxEventAppender).should().appendOrderRefund(order, event);
		}

		@Test
		@DisplayName("이미 REFUNDED인 주문에 환불 이벤트가 다시 들어오면 무시한다")
		void handlePaymentRefunded_duplicateRefundedOrder_doNothing() {
			Order order = createPaidOrderWithProducts();
			order.refund(REFUNDED_AT);
			PaymentRefundedEvent event = createPaymentRefundedEvent(order.getId());

			given(orderRepository.findByIdWithOrderProducts(event.orderId()))
				.willReturn(Optional.of(order));

			orderPaymentEventService.handlePaymentRefunded(event);

			assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.REFUNDED);
			then(outboxEventAppender).should(never()).appendOrderRefund(any(Order.class), any(PaymentRefundedEvent.class));
		}

		@Test
		@DisplayName("PENDING 주문에 환불 이벤트가 들어오면 상태 전이를 거부한다")
		void handlePaymentRefunded_pendingOrder_throwsException() {
			Order order = createPendingOrderWithProducts();
			PaymentRefundedEvent event = createPaymentRefundedEvent(order.getId());

			given(orderRepository.findByIdWithOrderProducts(event.orderId()))
				.willReturn(Optional.of(order));

			assertThatThrownBy(() -> orderPaymentEventService.handlePaymentRefunded(event))
				.isInstanceOf(OrderException.class)
				.satisfies(exception -> assertThat(((OrderException) exception).getErrorCode())
					.isEqualTo(ErrorCode.INVALID_ORDER_STATUS_TRANSITION));
			then(outboxEventAppender).should(never()).appendOrderRefund(any(Order.class), any(PaymentRefundedEvent.class));
		}
	}
}
