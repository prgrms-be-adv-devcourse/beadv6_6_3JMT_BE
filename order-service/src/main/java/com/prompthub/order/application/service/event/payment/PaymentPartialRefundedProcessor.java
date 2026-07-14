package com.prompthub.order.application.service.event.payment;

import com.prompthub.order.application.service.event.common.ConsumedEventContext;
import com.prompthub.order.application.service.event.common.ProcessedEventService;
import com.prompthub.order.application.service.refund.OrderRefundCompletionService;
import com.prompthub.order.infra.messaging.kafka.event.PaymentPartialRefundedPayload;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PaymentPartialRefundedProcessor implements PaymentEventProcessor<PaymentPartialRefundedPayload> {

	private final ProcessedEventService processedEventService;
	private final OrderRefundCompletionService completionService;

	@Override
	public void process(ConsumedEventContext context, PaymentPartialRefundedPayload payload) {
		processedEventService.executeOnce(context, () -> completionService.complete(payload.toCommand()));
	}
}
