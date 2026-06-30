package com.prompthub.settlement.infrastructure.batch.processor;

import com.prompthub.settlement.application.dto.CalculateSettlementCommand;
import com.prompthub.settlement.application.usecase.CalculateSettlementUseCase;
import com.prompthub.settlement.domain.model.Settlement;
import com.prompthub.settlement.infrastructure.batch.model.SettlementItem;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SettlementProcessor implements ItemProcessor<SettlementItem, Settlement> {

    private final CalculateSettlementUseCase calculateSettlementUseCase;

    @Override
    public Settlement process(SettlementItem item) {
        // 정산 대상 라인이 없으면 calculate 가 null 을 반환하고, chunk 는 해당 아이템을 건너뛴다.
        return calculateSettlementUseCase.calculate(new CalculateSettlementCommand(
                item.settlementBatchId(),
                item.sellerId(),
                item.period()));
    }
}
