package com.prompthub.admin.settlement.infrastructure.kubernetes;

import java.util.Set;
import java.util.UUID;

public class DisabledSettlementDeliveryRetryJobClient
        implements SettlementDeliveryRetryJobClient {

    @Override
    public Set<UUID> findActiveDeliveryIds() {
        return Set.of();
    }

    @Override
    public boolean isActive(UUID settlementDeliveryId) {
        return false;
    }

    @Override
    public void launch(
            UUID settlementDeliveryId,
            int previousAttemptCount) {
        throw new SettlementDeliveryRetryJobException(
                "정산 재전송 Job 기능이 비활성화되어 있습니다.");
    }
}
