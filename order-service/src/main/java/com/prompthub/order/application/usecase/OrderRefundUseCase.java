package com.prompthub.order.application.usecase;

import java.util.UUID;

public interface OrderRefundUseCase {

	void requestRefund(
		UUID buyerId,
		UUID orderId,
		UUID paymentId,
		UUID orderProductId
	);
}
