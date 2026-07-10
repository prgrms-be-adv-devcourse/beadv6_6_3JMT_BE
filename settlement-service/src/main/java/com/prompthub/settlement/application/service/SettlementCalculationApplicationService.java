package com.prompthub.settlement.application.service;

import com.prompthub.settlement.application.dto.CalculateSettlementCommand;
import com.prompthub.settlement.application.event.SettlementCreatedPayload;
import com.prompthub.settlement.application.usecase.CalculateSettlementUseCase;
import com.prompthub.settlement.domain.model.Settlement;
import com.prompthub.settlement.domain.model.SettlementDetail;
import com.prompthub.settlement.domain.model.SettlementSourceLine;
import com.prompthub.settlement.domain.repository.SettlementRepository;
import com.prompthub.settlement.domain.repository.SettlementSourceRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SettlementCalculationApplicationService implements CalculateSettlementUseCase {

    private static final BigDecimal DEFAULT_FEE_RATE = new BigDecimal("0.15");

    private final SettlementSourceRepository settlementSourceRepository;
    private final SettlementRepository settlementRepository;
    private final ApplicationEventPublisher eventPublisher;

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

        // 커밋 후 settlement.created 발행(AFTER_COMMIT 리스너 위임 — SettlementCreatedEventListener)
        eventPublisher.publishEvent(SettlementCreatedPayload.from(settlement));

        return settlement;
    }

    private SettlementDetail toDetail(SettlementSourceLine line) {
        return switch (line.getEventType()) {
            case PAID -> SettlementDetail.sale(
                    line.getOrderProductId(), line.getLineAmount(), DEFAULT_FEE_RATE, line.getOccurredAt());
            case REFUND -> SettlementDetail.refund(
                    line.getOrderProductId(), line.getLineAmount(), DEFAULT_FEE_RATE, line.getOccurredAt());
        };
    }
}
