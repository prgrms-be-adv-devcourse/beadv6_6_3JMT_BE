package com.prompthub.paymentservice.domain.event;

import com.prompthub.paymentservice.domain.model.Payment;
import com.prompthub.paymentservice.domain.model.Refund;

public record PaymentRefundedEvent(Payment payment, Refund refund) {}
