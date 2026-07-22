package com.prompthub.settlement.application.dto;

import com.prompthub.settlement.domain.model.SettlementPeriod;
import com.prompthub.settlement.domain.model.enums.TriggerType;
import java.util.UUID;

public record RunSettlementBatchCommand(
        SettlementPeriod period,
        UUID actorId,
        TriggerType triggerType
) {

    public static RunSettlementBatchCommand manual(SettlementPeriod period, UUID actorId) {
        return new RunSettlementBatchCommand(period, actorId, TriggerType.MANUAL);
    }

    public static RunSettlementBatchCommand scheduled(SettlementPeriod period) {
        return new RunSettlementBatchCommand(period, null, TriggerType.SCHEDULED);
    }
}
