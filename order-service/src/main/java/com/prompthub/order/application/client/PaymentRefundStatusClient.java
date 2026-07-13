package com.prompthub.order.application.client;

import com.prompthub.order.application.dto.PaymentRefundStatusResult;

import java.util.UUID;

public interface PaymentRefundStatusClient {

	PaymentRefundStatusResult getRefundStatus(UUID refundRequestId);
}
