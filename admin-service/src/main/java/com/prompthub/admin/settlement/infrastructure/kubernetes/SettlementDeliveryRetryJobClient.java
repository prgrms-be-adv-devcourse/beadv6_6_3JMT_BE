package com.prompthub.admin.settlement.infrastructure.kubernetes;

import java.util.Set;
import java.util.UUID;

public interface SettlementDeliveryRetryJobClient {

    Set<UUID> findActiveDeliveryIds();

    boolean isActive(UUID settlementDeliveryId);

    void launch(
            UUID settlementDeliveryId,
            int previousAttemptCount);
}
