package com.prompthub.paymentservice.domain.event;

import com.prompthub.paymentservice.domain.model.Payment;
import com.prompthub.paymentservice.domain.model.Refund;

public record PaymentRefundFailedEvent(Payment payment, Refund refund, String failureReason) {}
