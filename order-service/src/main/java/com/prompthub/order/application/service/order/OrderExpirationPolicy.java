package com.prompthub.order.application.service.order;

public interface OrderExpirationPolicy {

	int paymentTimeoutMinutes();
}
