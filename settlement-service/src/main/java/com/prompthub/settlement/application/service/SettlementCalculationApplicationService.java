package com.prompthub.settlement.application.service;

import com.prompthub.settlement.application.dto.CalculateSettlementCommand;
import com.prompthub.settlement.application.usecase.CalculateSettlementUseCase;
import com.prompthub.settlement.domain.model.Settlement;
import com.prompthub.settlement.domain.model.SettlementDetail;
import com.prompthub.settlement.domain.model.SettlementSourceLine;
import com.prompthub.settlement.domain.repository.SettlementSourceRepository;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SettlementCalculationApplicationService implements CalculateSettlementUseCase {

    private static final BigDecimal DEFAULT_FEE_RATE = new BigDecimal("0.15");

    private final SettlementSourceRepository settlementSourceRepository;

    @Override
    public Settlement calculate(CalculateSettlementCommand command) {
        List<SettlementSourceLine> lines = settlementSourceRepository.findSettleableLines(
                command.sellerId(), command.period());

        List<SettlementDetail> details = lines.stream()
                .map(line -> SettlementDetail.sale(
                    line.orderProductId(),
                    line.amount(),
                    DEFAULT_FEE_RATE,
                    line.occurredAt()
                ))
                .toList();

        return Settlement.create(command.settlementBatchId(), command.sellerId(), command.period(), details);
    }
}
