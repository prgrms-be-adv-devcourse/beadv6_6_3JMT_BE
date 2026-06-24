package com.prompthub.paymentservice.domain.event;

import java.util.UUID;

public record PaymentRefundRequestedEvent(UUID paymentId, UUID refundId) {}
