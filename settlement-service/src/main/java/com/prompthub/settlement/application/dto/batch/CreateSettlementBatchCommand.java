package com.prompthub.settlement.application.dto.batch;

import com.prompthub.settlement.domain.model.batch.SettlementPeriod;
import com.prompthub.settlement.domain.model.batch.TriggerType;
import java.util.Objects;

public record CreateSettlementBatchCommand(
        String batchNo,
        long jobInstanceId,
        SettlementPeriod period,
        TriggerType triggerType) {

    public CreateSettlementBatchCommand {
        Objects.requireNonNull(batchNo, "batchNo는 필수입니다.");
        Objects.requireNonNull(period, "period는 필수입니다.");
        Objects.requireNonNull(triggerType, "triggerType은 필수입니다.");
    }
}
