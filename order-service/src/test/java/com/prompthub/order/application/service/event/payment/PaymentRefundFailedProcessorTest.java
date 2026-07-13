package com.prompthub.order.application.service.event.payment;

import com.prompthub.order.application.service.event.common.ConsumedEventContext;
import com.prompthub.order.application.service.event.common.ProcessedEventService;
import com.prompthub.order.application.service.refund.RefundResultContextLoader;

import com.prompthub.order.domain.enums.OrderProductStatus;
import com.prompthub.order.domain.enums.OrderRefundStatus;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderRefund;
import com.prompthub.order.infra.messaging.kafka.event.PaymentRefundFailedPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.UUID;

import static com.prompthub.order.fixture.OrderFixture.PAYMENT_ID;
import static com.prompthub.order.fixture.OrderFixture.TOTAL_AMOUNT;
import static com.prompthub.order.fixture.OrderFixture.createPaidOrderWithProducts;
import static com.prompthub.order.fixture.OrderRefundFixture.createRequestedRefundWithAllProducts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class PaymentRefundFailedProcessorTest {

	@Mock private ProcessedEventService processedEventService;
	@Mock private RefundResultContextLoader contextLoader;
	@InjectMocks private PaymentRefundFailedProcessor processor;

	@BeforeEach
	void executeEventAction() {
		given(processedEventService.executeOnce(any(), any())).willAnswer(invocation -> {
			invocation.<Runnable>getArgument(1).run();
			return true;
		});
	}

	@Test
	void process_requestLevelFailure_failsEveryProduct() {
		UUID refundId = UUID.randomUUID();
		LocalDateTime failedAt = LocalDateTime.of(2026, 7, 13, 12, 5);
		Order order = createPaidOrderWithProducts();
		OrderRefund refund = createRequestedRefundWithAllProducts(order, refundId, failedAt.minusMinutes(5));
		PaymentRefundFailedPayload payload = new PaymentRefundFailedPayload(
			refundId, PAYMENT_ID, order.getId(), TOTAL_AMOUNT, "PG_REJECTED", "환불 거절", failedAt
		);
		given(contextLoader.loadValidatedRefund(payload)).willReturn(refund);
		given(contextLoader.loadOrderForUpdate(order.getId())).willReturn(order);

		processor.process(
			new ConsumedEventContext(UUID.randomUUID(), "PAYMENT_REFUND_FAILED", failedAt),
			payload
		);

		assertThat(refund.getStatus()).isEqualTo(OrderRefundStatus.FAILED);
		assertThat(refund.getFailureCode()).isEqualTo("PG_REJECTED");
		assertThat(order.getOrderProducts()).extracting(item -> item.getOrderProductStatus())
			.containsOnly(OrderProductStatus.REFUND_FAILED);
	}
}
