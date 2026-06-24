package com.prompthub.settlement.application.usecase;

import com.prompthub.settlement.application.dto.RecordSettlementSourceCommand;

public interface SettlementSourceUseCase {

    void record(RecordSettlementSourceCommand command);
}
