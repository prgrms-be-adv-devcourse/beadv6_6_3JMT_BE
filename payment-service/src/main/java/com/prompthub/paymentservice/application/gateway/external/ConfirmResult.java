package com.prompthub.paymentservice.application.gateway.external;

import java.time.OffsetDateTime;

public record ConfirmResult(
    String paymentMethod,
    int approvedAmount,
    String responsePayload,
    OffsetDateTime approvedAt
) {}
