package com.prompthub.paymentservice.application.gateway.external;

import java.time.OffsetDateTime;

public record TossRefundResult(OffsetDateTime refundedAt) {}
