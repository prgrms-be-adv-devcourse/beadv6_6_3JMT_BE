package com.prompthub.order.application.service.event.payment;

import com.prompthub.order.application.service.event.common.ConsumedEventContext;
import com.prompthub.order.application.service.event.common.ProcessedEventService;
import com.prompthub.order.application.service.refund.OrderRefundFailureService;
import com.prompthub.order.infra.messaging.kafka.event.PaymentPartialRefundFailedPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class PaymentPartialRefundFailedProcessorTest {

	@Mock private ProcessedEventService processedEventService;
	@Mock private OrderRefundFailureService failureService;
	@InjectMocks private PaymentPartialRefundFailedProcessor processor;

	@Test
	void process_delegatesFailureInsideIdempotencyBoundary() {
		LocalDateTime failedAt = LocalDateTime.of(2026, 7, 14, 12, 0);
		ConsumedEventContext context = new ConsumedEventContext(
			UUID.randomUUID(), "PAYMENT_PARTIAL_REFUND_FAILED", failedAt
		);
		PaymentPartialRefundFailedPayload payload = new PaymentPartialRefundFailedPayload(
			UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 10_000,
			"PG_REJECTED", "환불 거절", false, failedAt
		);
		ArgumentCaptor<Runnable> actionCaptor = ArgumentCaptor.forClass(Runnable.class);

		processor.process(context, payload);

		then(processedEventService).should().executeOnce(
			org.mockito.ArgumentMatchers.eq(context), actionCaptor.capture()
		);
		then(failureService).shouldHaveNoInteractions();

		actionCaptor.getValue().run();

		then(failureService).should().fail(payload.toCommand());
	}
}
