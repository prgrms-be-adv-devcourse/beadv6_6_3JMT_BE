package com.prompthub.order.application.service.event.payment;

import com.prompthub.order.application.service.event.common.ConsumedEventContext;
import com.prompthub.order.application.service.event.common.ProcessedEventService;
import com.prompthub.order.application.service.refund.OrderRefundFailureService;
import com.prompthub.order.infra.messaging.kafka.event.PaymentPartialRefundFailedPayload;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PaymentPartialRefundFailedProcessor implements PaymentEventProcessor<PaymentPartialRefundFailedPayload> {

	private final ProcessedEventService processedEventService;
	private final OrderRefundFailureService failureService;

	@Override
	public void process(ConsumedEventContext context, PaymentPartialRefundFailedPayload payload) {
		processedEventService.executeOnce(context, () -> failureService.fail(payload.toCommand()));
	}
}
