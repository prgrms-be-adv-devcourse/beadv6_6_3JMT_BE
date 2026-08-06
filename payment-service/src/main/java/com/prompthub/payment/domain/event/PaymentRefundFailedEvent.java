package com.prompthub.payment.domain.event;

import com.prompthub.payment.domain.model.Payment;
import com.prompthub.payment.domain.model.Refund;

public record PaymentRefundFailedEvent(Payment payment, Refund refund) {}
