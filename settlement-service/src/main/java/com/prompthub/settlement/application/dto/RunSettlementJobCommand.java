package com.prompthub.settlement.application.dto;

import com.prompthub.settlement.domain.model.enums.TriggerType;
import java.time.YearMonth;
import java.util.UUID;

public record RunSettlementJobCommand(
        YearMonth period,
        UUID actorId,
        TriggerType triggerType
) {

    public static RunSettlementJobCommand manual(YearMonth period, UUID actorId) {
        return new RunSettlementJobCommand(period, actorId, TriggerType.MANUAL);
    }

    public static RunSettlementJobCommand scheduled(YearMonth period) {
        return new RunSettlementJobCommand(period, null, TriggerType.SCHEDULED);
    }
}
