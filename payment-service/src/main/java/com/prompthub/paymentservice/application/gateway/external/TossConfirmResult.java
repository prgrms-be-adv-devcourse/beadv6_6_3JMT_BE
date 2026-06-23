package com.prompthub.paymentservice.application.gateway.external;

import java.time.OffsetDateTime;

public record TossConfirmResult(
    String paymentMethod,
    int approvedAmount,
    String responsePayload,
    OffsetDateTime approvedAt
) {}
