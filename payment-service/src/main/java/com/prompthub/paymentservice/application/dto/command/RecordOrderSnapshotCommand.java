package com.prompthub.paymentservice.application.dto.command;

import com.prompthub.paymentservice.domain.model.OrderSnapshotSource;
import java.time.OffsetDateTime;
import java.util.UUID;

public record RecordOrderSnapshotCommand(
    UUID orderId,
    UUID buyerId,
    int totalAmount,
    OffsetDateTime orderCreatedAt,
    OrderSnapshotSource source
) {}
