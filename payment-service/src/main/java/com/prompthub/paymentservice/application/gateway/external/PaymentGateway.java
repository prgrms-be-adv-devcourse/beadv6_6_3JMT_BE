package com.prompthub.paymentservice.application.gateway.external;

import java.util.UUID;

public interface PaymentGateway {
    TossConfirmResult confirm(String paymentKey, UUID orderId, int amount);
    TossRefundResult refund(String pgTxId, UUID paymentId, int amount);
}
