package com.prompthub.order.application.service.refund;

import com.prompthub.common.event.EventMessage;
import com.prompthub.order.application.service.event.order.OrderEventMessageFactory;
import com.prompthub.order.application.service.event.outbox.OutboxEventAppender;
import com.prompthub.order.application.service.order.OrderPolicyService;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderPayment;
import com.prompthub.order.domain.model.OrderRefund;
import com.prompthub.order.domain.repository.OrderPaymentRepository;
import com.prompthub.order.domain.repository.OrderRefundRepository;
import com.prompthub.order.domain.repository.OrderRepository;
import com.prompthub.order.global.exception.OrderException;
import com.prompthub.order.infra.messaging.kafka.event.RefundRequestedPayload;
import com.prompthub.order.presentation.dto.request.CreateOrderRefundRequest;
import com.prompthub.order.presentation.dto.response.OrderRefundResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static com.prompthub.order.fixture.OrderFixture.APPROVED_AT;
import static com.prompthub.order.fixture.OrderFixture.BUYER_ID;
import static com.prompthub.order.fixture.OrderFixture.PAYMENT_ID;
import static com.prompthub.order.fixture.OrderFixture.TOTAL_AMOUNT;
import static com.prompthub.order.fixture.OrderFixture.createPaidOrderWithProducts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class OrderRefundServiceTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 13, 12, 0);
	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-13T03:00:00Z"), ZoneId.of("Asia/Seoul"));

	@Mock private OrderRepository orderRepository;
	@Mock private OrderPaymentRepository orderPaymentRepository;
	@Mock private OrderRefundRepository orderRefundRepository;
	@Mock private OutboxEventAppender outboxEventAppender;

	private OrderRefundService service;

	@BeforeEach
	void setUp() {
		service = new OrderRefundService(
			orderRepository,
			orderPaymentRepository,
			orderRefundRepository,
			new OrderEventMessageFactory(),
			outboxEventAppender,
			new OrderPolicyService(),
			CLOCK
		);
	}

	@Test
	@DisplayName("여러 결제 상품 환불을 요청하면 하나의 요청과 다건 payload를 저장한다")
	void requestRefund_multiplePaidProducts_savesSingleRefundAndOutbox() {
		Order order = createPaidOrderWithProducts();
		OrderPayment payment = OrderPayment.create(order.getId(), PAYMENT_ID, BUYER_ID, TOTAL_AMOUNT, APPROVED_AT);
		List<java.util.UUID> productIds = order.getOrderProducts().stream().map(item -> item.getId()).toList();
		given(orderRepository.findByIdWithOrderProductsForUpdate(order.getId())).willReturn(Optional.of(order));
		given(orderPaymentRepository.findByOrderId(order.getId())).willReturn(Optional.of(payment));
		given(orderRefundRepository.save(any(OrderRefund.class))).willAnswer(invocation -> invocation.getArgument(0));

		OrderRefundResponse response = service.requestRefund(
			BUYER_ID,
			order.getId(),
			new CreateOrderRefundRequest(productIds, "  고객 변심  ")
		);

		assertThat(response.orderProductIds()).containsExactlyElementsOf(productIds);
		assertThat(response.totalRefundAmount()).isEqualTo(TOTAL_AMOUNT);
		assertThat(response.requestedAt()).isEqualTo(NOW);

		ArgumentCaptor<EventMessage<?>> captor = ArgumentCaptor.forClass(EventMessage.class);
		then(outboxEventAppender).should().append(captor.capture());
		RefundRequestedPayload payload = (RefundRequestedPayload) captor.getValue().payload();
		ArgumentCaptor<OrderRefund> refundCaptor = ArgumentCaptor.forClass(OrderRefund.class);
		then(orderRefundRepository).should().save(refundCaptor.capture());
		RefundRequestedPayload expected = RefundRequestedPayload.from(refundCaptor.getValue());
		assertThat(payload).isEqualTo(expected);
		assertThat(payload.totalRefundAmount()).isEqualTo(TOTAL_AMOUNT);
		assertThat(payload.products()).hasSize(2);
		assertThat(payload.reason()).isEqualTo("고객 변심");
	}

	@Test
	@DisplayName("대상 중 다운로드 상품이 있으면 전체 환불 요청을 거절한다")
	void requestRefund_oneDownloadedProduct_rejectsEntireRequest() {
		Order order = createPaidOrderWithProducts();
		order.getOrderProducts().getFirst().markDownloaded();
		List<java.util.UUID> productIds = order.getOrderProducts().stream().map(item -> item.getId()).toList();
		given(orderRepository.findByIdWithOrderProductsForUpdate(order.getId())).willReturn(Optional.of(order));

		assertThatThrownBy(() -> service.requestRefund(
			BUYER_ID,
			order.getId(),
			new CreateOrderRefundRequest(productIds, null)
		)).isInstanceOf(OrderException.class);

		assertThat(order.getOrderProducts()).allMatch(item -> item.getOrderProductStatus().name().equals("PAID"));
		then(orderRefundRepository).shouldHaveNoInteractions();
		then(outboxEventAppender).shouldHaveNoInteractions();
	}
}
