package com.prompthub.payment.application.gateway.external;

import java.time.OffsetDateTime;

public record RefundResult(OffsetDateTime refundedAt) {}
