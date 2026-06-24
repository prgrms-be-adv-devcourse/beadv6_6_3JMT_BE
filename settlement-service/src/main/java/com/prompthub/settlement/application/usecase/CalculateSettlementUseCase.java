package com.prompthub.settlement.application.usecase;

import com.prompthub.settlement.application.dto.CalculateSettlementCommand;
import com.prompthub.settlement.domain.model.Settlement;

public interface CalculateSettlementUseCase {

    Settlement calculate(CalculateSettlementCommand command);
}
