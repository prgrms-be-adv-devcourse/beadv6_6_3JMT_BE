package com.prompthub.order.infra.product;

import java.util.List;
import java.util.UUID;

public record ProductOrderSnapshotRequest(
        List<UUID> productIds
) {
}
