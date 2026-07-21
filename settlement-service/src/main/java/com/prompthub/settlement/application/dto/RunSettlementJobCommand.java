package com.prompthub.settlement.application.dto;

import com.prompthub.settlement.domain.model.SettlementPeriod;
import com.prompthub.settlement.domain.model.enums.TriggerType;
import java.util.UUID;

public record RunSettlementJobCommand(
        SettlementPeriod period,
        UUID actorId,
        TriggerType triggerType
) {

    public static RunSettlementJobCommand manual(SettlementPeriod period, UUID actorId) {
        return new RunSettlementJobCommand(period, actorId, TriggerType.MANUAL);
    }

    public static RunSettlementJobCommand scheduled(SettlementPeriod period) {
        return new RunSettlementJobCommand(period, null, TriggerType.SCHEDULED);
    }
}
