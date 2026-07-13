package com.prompthub.order.application.service.event.payment;

import com.prompthub.common.event.EventMessage;
import com.prompthub.order.application.service.event.common.ConsumedEventContext;
import com.prompthub.order.application.service.event.common.ProcessedEventService;
import com.prompthub.order.application.service.event.outbox.OutboxEventAppender;
import com.prompthub.order.application.service.event.order.OrderEventMessageFactory;
import com.prompthub.order.application.service.refund.RefundResultContextLoader;
import com.prompthub.order.domain.enums.OrderProductStatus;
import com.prompthub.order.domain.enums.OrderRefundStatus;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderRefund;
import com.prompthub.order.infra.messaging.kafka.event.OrderProductRefundedPayload;
import com.prompthub.order.infra.messaging.kafka.event.PaymentRefundCompletedPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.UUID;

import static com.prompthub.order.fixture.OrderFixture.PAYMENT_ID;
import static com.prompthub.order.fixture.OrderFixture.TOTAL_AMOUNT;
import static com.prompthub.order.fixture.OrderFixture.createPaidOrderWithProducts;
import static com.prompthub.order.fixture.OrderRefundFixture.createRequestedRefundWithAllProducts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class PaymentRefundCompletedProcessorTest {

	@Mock private ProcessedEventService processedEventService;
	@Mock private RefundResultContextLoader contextLoader;
	@Mock private OutboxEventAppender outboxEventAppender;
	private PaymentRefundCompletedProcessor processor;

	@BeforeEach
	void setUp() {
		processor = new PaymentRefundCompletedProcessor(
			processedEventService, contextLoader,
			new OrderEventMessageFactory(), outboxEventAppender
		);
		given(processedEventService.executeOnce(any(), any())).willAnswer(invocation -> {
			invocation.<Runnable>getArgument(1).run();
			return true;
		});
	}

	@Test
	void process_requestLevelSuccess_refundsEveryProduct() {
		UUID eventId = UUID.randomUUID();
		UUID refundId = UUID.randomUUID();
		LocalDateTime refundedAt = LocalDateTime.of(2026, 7, 13, 12, 5);
		Order order = createPaidOrderWithProducts();
		OrderRefund refund = createRequestedRefundWithAllProducts(order, refundId, refundedAt.minusMinutes(5));
		PaymentRefundCompletedPayload payload = new PaymentRefundCompletedPayload(
			refundId, PAYMENT_ID, order.getId(), TOTAL_AMOUNT, refundedAt
		);
		given(contextLoader.loadValidatedRefund(payload)).willReturn(refund);
		given(contextLoader.loadOrderForUpdate(order.getId())).willReturn(order);

		processor.process(new ConsumedEventContext(eventId, "PAYMENT_REFUND_COMPLETED", refundedAt), payload);

		assertThat(refund.getStatus()).isEqualTo(OrderRefundStatus.COMPLETED);
		assertThat(order.getOrderProducts()).extracting(item -> item.getOrderProductStatus())
			.containsOnly(OrderProductStatus.REFUNDED);
		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.REFUNDED);
		ArgumentCaptor<EventMessage<?>> captor = ArgumentCaptor.forClass(EventMessage.class);
		then(outboxEventAppender).should().append(captor.capture());
		OrderProductRefundedPayload outbox = (OrderProductRefundedPayload) captor.getValue().payload();
		assertThat(outbox).isEqualTo(OrderProductRefundedPayload.from(refund, refundedAt));
		assertThat(outbox.products()).hasSize(2);
		assertThat(outbox.totalRefundAmount()).isEqualTo(TOTAL_AMOUNT);
	}

}
