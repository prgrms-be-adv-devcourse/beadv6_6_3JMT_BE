package com.prompthub.paymentservice.domain.event;

import com.prompthub.paymentservice.domain.model.Payment;

public record PaymentFailedEvent(Payment payment) {}
