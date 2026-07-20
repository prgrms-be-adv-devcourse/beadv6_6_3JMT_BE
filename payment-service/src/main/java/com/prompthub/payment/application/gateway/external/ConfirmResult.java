package com.prompthub.payment.application.gateway.external;

import java.time.OffsetDateTime;

public record ConfirmResult(
    String paymentMethod,
    int approvedAmount,
    String responsePayload,
    OffsetDateTime approvedAt
) {}
