package com.prompthub.payment.infrastructure.external.toss.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record TossRefundResponse(
    String status,
    List<TossCancel> cancels
) {
    public record TossCancel(OffsetDateTime canceledAt) {}
}
