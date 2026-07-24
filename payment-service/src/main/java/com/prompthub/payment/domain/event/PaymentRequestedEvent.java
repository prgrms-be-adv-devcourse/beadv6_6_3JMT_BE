package com.prompthub.payment.domain.event;

import com.prompthub.payment.domain.model.Payment;

public record PaymentRequestedEvent(Payment payment) {}
