package com.prompthub.order.application.service.event.payment;

import com.prompthub.order.application.service.event.common.ConsumedEventContext;

@FunctionalInterface
public interface PaymentEventProcessor<P> {
	void process(ConsumedEventContext context, P payload);
}
