package com.prompthub.settlement.application.service.calculation;

import com.prompthub.settlement.application.dto.calculation.CalculateSettlementCommand;
import com.prompthub.settlement.application.usecase.calculation.CalculateSettlementUseCase;
import com.prompthub.settlement.domain.model.calculation.Settlement;
import com.prompthub.settlement.domain.model.calculation.SettlementDetail;
import com.prompthub.settlement.domain.model.delivery.SettlementDelivery;
import com.prompthub.settlement.domain.model.source.SettlementSourceLine;
import com.prompthub.settlement.domain.repository.SettlementRepository;
import com.prompthub.settlement.domain.repository.SettlementDeliveryRepository;
import com.prompthub.settlement.domain.repository.SettlementSourceRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SettlementCalculationApplicationService implements CalculateSettlementUseCase {

    private static final BigDecimal DEFAULT_FEE_RATE = new BigDecimal("0.15");

    private final SettlementSourceRepository settlementSourceRepository;
    private final SettlementRepository settlementRepository;
    private final SettlementDeliveryRepository settlementDeliveryRepository;

    @Override
    @Transactional
    public Settlement calculate(CalculateSettlementCommand command) {
        List<SettlementSourceLine> lines = settlementSourceRepository.findSettleableLines(
                command.sellerId(), command.period());
        if (lines.isEmpty()) {
            return null;
        }

        List<SettlementDetail> details = lines.stream()
                .map(this::toDetail)
                .toList();

        Settlement settlement = Settlement.create(
                command.settlementBatchId(), command.sellerId(), command.period(), details);
        settlementRepository.save(settlement);

        UUID settlementId = settlement.getId();
        lines.forEach(line -> line.markSettled(settlementId));

        settlementDeliveryRepository.save(SettlementDelivery.calculated(
                settlementId,
                command.settlementBatchId(),
                UUID.randomUUID()));

        return settlement;
    }

    private SettlementDetail toDetail(SettlementSourceLine line) {
        return switch (line.getLineType()) {
            case PAID -> SettlementDetail.sale(
                    line.getId(), line.getOrderProductId(), line.getLineAmount(),
                    DEFAULT_FEE_RATE, line.getOccurredAt());
            case REFUND -> SettlementDetail.refund(
                    line.getId(), line.getOrderProductId(), line.getLineAmount(),
                    DEFAULT_FEE_RATE, line.getOccurredAt());
        };
    }
}
