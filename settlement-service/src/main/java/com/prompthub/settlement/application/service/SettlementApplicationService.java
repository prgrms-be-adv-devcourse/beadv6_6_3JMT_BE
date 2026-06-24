package com.prompthub.settlement.application.service;

import com.prompthub.settlement.application.dto.SettlementListQuery;
import com.prompthub.settlement.application.usecase.SettlementUseCase;
import com.prompthub.settlement.domain.model.Settlement;
import com.prompthub.settlement.domain.model.SettlementSourceLine;
import com.prompthub.settlement.domain.model.enums.SettlementDisplayStatus;
import com.prompthub.settlement.domain.repository.SettlementQueryRepository;
import com.prompthub.settlement.domain.repository.SettlementRepository;
import com.prompthub.settlement.domain.repository.SettlementSourceRepository;
import com.prompthub.settlement.domain.repository.SettlementStatusAggregate;
import com.prompthub.settlement.global.exception.SettlementErrorCode;
import com.prompthub.settlement.global.exception.SettlementException;
import com.prompthub.settlement.presentation.dto.response.SettlementListResponse;
import com.prompthub.settlement.presentation.dto.response.SettlementResponse;
import com.prompthub.settlement.presentation.dto.response.SettlementStatusResponse;
import com.prompthub.settlement.presentation.dto.response.SettlementSummaryResponse;
import com.prompthub.settlement.presentation.dto.response.SettlementSummaryResponse.Card;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SettlementApplicationService implements SettlementUseCase {

    private static final List<SettlementDisplayStatus> CARD_ORDER = List.of(
            SettlementDisplayStatus.WAITING,
            SettlementDisplayStatus.APPROVED,
            SettlementDisplayStatus.PAYOUT_ON_HOLD,
            SettlementDisplayStatus.PAID);

    private final SettlementQueryRepository settlementQueryRepository;
    private final SettlementRepository settlementRepository;
    private final SettlementSourceRepository settlementSourceRepository;

    @Override
    public SettlementSummaryResponse getSummary() {
        Map<SettlementDisplayStatus, BigDecimal> amountByCard = new EnumMap<>(SettlementDisplayStatus.class);
        Map<SettlementDisplayStatus, Long> countByCard = new EnumMap<>(SettlementDisplayStatus.class);
        for (SettlementDisplayStatus card : CARD_ORDER) {
            amountByCard.put(card, BigDecimal.ZERO);
            countByCard.put(card, 0L);
        }

        for (SettlementStatusAggregate aggregate : settlementQueryRepository.aggregateByStatus()) {
            SettlementDisplayStatus card =
                    SettlementDisplayStatus.from(aggregate.settlementStatus(), aggregate.payoutStatus()).toCard();
            if (card == null) {
                continue;
            }
            amountByCard.merge(card, aggregate.sumSettlementTotal(), BigDecimal::add);
            countByCard.merge(card, aggregate.count(), Long::sum);
        }

        List<Card> cards = CARD_ORDER.stream()
                .map(card -> new Card(card.name(), amountByCard.get(card), countByCard.get(card)))
                .toList();
        return new SettlementSummaryResponse(cards);
    }

    @Override
    public SettlementListResponse getList(SettlementListQuery query) {
        SettlementQueryRepository.SettlementPage page =
                settlementQueryRepository.findPage(query.status(), query.page(), query.size());
        return SettlementListResponse.from(page.content(), page.totalElements(), query.page(), query.size());
    }

    @Override
    @Transactional
    public SettlementStatusResponse approve(UUID settlementId) {
        Settlement settlement = findSettlement(settlementId);
        settlement.approve(LocalDateTime.now());
        settlementRepository.save(settlement);
        return SettlementStatusResponse.from(settlement);
    }

    @Override
    @Transactional
    public SettlementStatusResponse hold(UUID settlementId) {
        Settlement settlement = findSettlement(settlementId);
        settlement.hold();
        settlementRepository.save(settlement);
        return SettlementStatusResponse.from(settlement);
    }

    @Override
    @Transactional
    public SettlementStatusResponse releaseHold(UUID settlementId) {
        Settlement settlement = findSettlement(settlementId);
        settlement.releaseHold();
        settlementRepository.save(settlement);
        return SettlementStatusResponse.from(settlement);
    }

    @Override
    @Transactional
    public SettlementStatusResponse payout(UUID settlementId) {
        Settlement settlement = findSettlement(settlementId);
        settlement.payout(LocalDateTime.now());
        settlementRepository.save(settlement);
        return SettlementStatusResponse.from(settlement);
    }

    @Override
    @Transactional
    public SettlementStatusResponse payoutHold(UUID settlementId) {
        Settlement settlement = findSettlement(settlementId);
        settlement.payoutHold();
        settlementRepository.save(settlement);
        return SettlementStatusResponse.from(settlement);
    }

    @Override
    @Transactional
    public SettlementStatusResponse releasePayoutHold(UUID settlementId) {
        Settlement settlement = findSettlement(settlementId);
        settlement.releasePayoutHold();
        settlementRepository.save(settlement);
        return SettlementStatusResponse.from(settlement);
    }

    @Override
    @Transactional
    public SettlementResponse cancel(UUID settlementId) {
        Settlement settlement = findSettlement(settlementId);
        settlement.cancel(LocalDateTime.now());
        List<SettlementSourceLine> lines = settlementSourceRepository.findBySettlementId(settlementId);
        lines.forEach(line -> line.release(settlementId));
        settlementRepository.save(settlement);
        return SettlementResponse.from(settlement);
    }

    private Settlement findSettlement(UUID settlementId) {
        return settlementRepository.findById(settlementId)
                .orElseThrow(() -> new SettlementException(SettlementErrorCode.SETTLEMENT_NOT_FOUND));
    }
}
