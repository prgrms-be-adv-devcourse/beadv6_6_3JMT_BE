package com.prompthub.order.application.service.event.payment;

import com.prompthub.order.application.service.event.common.ConsumedEventContext;
import com.prompthub.order.application.service.event.common.ProcessedEventService;
import com.prompthub.order.application.service.refund.OrderRefundCompletionService;
import com.prompthub.order.infra.messaging.kafka.event.PaymentPartialRefundedPayload;
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
class PaymentPartialRefundedProcessorTest {

	@Mock private ProcessedEventService processedEventService;
	@Mock private OrderRefundCompletionService completionService;
	@InjectMocks private PaymentPartialRefundedProcessor processor;

	@Test
	void process_delegatesCompletionInsideIdempotencyBoundary() {
		LocalDateTime refundedAt = LocalDateTime.of(2026, 7, 14, 12, 0);
		ConsumedEventContext context = new ConsumedEventContext(
			UUID.randomUUID(), "PAYMENT_PARTIAL_REFUNDED", refundedAt
		);
		PaymentPartialRefundedPayload payload = new PaymentPartialRefundedPayload(
			UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 10_000, refundedAt
		);
		ArgumentCaptor<Runnable> actionCaptor = ArgumentCaptor.forClass(Runnable.class);

		processor.process(context, payload);

		then(processedEventService).should().executeOnce(
			org.mockito.ArgumentMatchers.eq(context), actionCaptor.capture()
		);
		then(completionService).shouldHaveNoInteractions();

		actionCaptor.getValue().run();

		then(completionService).should().complete(payload.toCommand());
	}
}
