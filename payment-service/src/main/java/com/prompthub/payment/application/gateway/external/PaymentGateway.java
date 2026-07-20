package com.prompthub.payment.application.gateway.external;

import java.util.UUID;

public interface PaymentGateway {
    ConfirmResult confirm(String paymentKey, UUID orderId, int amount);
    RefundResult refund(String pgTxId, UUID refundId, int amount);
}
