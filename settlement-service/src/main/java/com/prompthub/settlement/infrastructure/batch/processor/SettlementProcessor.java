package com.prompthub.settlement.infrastructure.batch.processor;

import com.prompthub.settlement.application.dto.CalculateSettlementCommand;
import com.prompthub.settlement.application.usecase.CalculateSettlementUseCase;
import com.prompthub.settlement.domain.model.Settlement;
import com.prompthub.settlement.infrastructure.batch.SettlementTarget;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SettlementProcessor implements ItemProcessor<SettlementTarget, Settlement> {

	private final CalculateSettlementUseCase calculateSettlementUseCase;

	@Override
	public Settlement process(SettlementTarget target) {
		Settlement settlement = calculateSettlementUseCase.calculate(new CalculateSettlementCommand(
			target.settlementBatchId(),
			target.sellerId(),
			target.period()
		));
		return settlement.getProductCount() == 0 ? null : settlement;
	}
}
