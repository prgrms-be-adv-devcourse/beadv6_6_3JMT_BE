package com.prompthub.order.application.dto;

import java.util.UUID;

public interface PaymentRefundResult {
    UUID refundRequestId();
    UUID paymentId();
    UUID orderId();
    int totalRefundAmount();
}
