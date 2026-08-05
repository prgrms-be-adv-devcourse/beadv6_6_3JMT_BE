package com.prompthub.settlement.application.usecase.calculation;

import com.prompthub.settlement.application.dto.calculation.CalculateSettlementCommand;
import com.prompthub.settlement.domain.model.calculation.Settlement;

public interface CalculateSettlementUseCase {

    Settlement calculate(CalculateSettlementCommand command);
}
