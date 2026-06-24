package com.prompthub.settlement.application.service;

import com.prompthub.settlement.application.dto.SettlementListQuery;
import com.prompthub.settlement.application.usecase.SettlementUseCase;
import com.prompthub.settlement.domain.model.enums.SettlementDisplayStatus;
import com.prompthub.settlement.domain.repository.SettlementListQueryRepository;
import com.prompthub.settlement.domain.repository.SettlementStatusAggregate;
import com.prompthub.settlement.domain.repository.SettlementSummaryQueryRepository;
import com.prompthub.settlement.presentation.dto.response.SettlementListResponse;
import com.prompthub.settlement.presentation.dto.response.SettlementSummaryResponse;
import com.prompthub.settlement.presentation.dto.response.SettlementSummaryResponse.Card;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
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

    private final SettlementSummaryQueryRepository settlementSummaryQueryRepository;
    private final SettlementListQueryRepository settlementListQueryRepository;

    @Override
    public SettlementSummaryResponse getSummary() {
        Map<SettlementDisplayStatus, BigDecimal> amountByCard = new EnumMap<>(SettlementDisplayStatus.class);
        Map<SettlementDisplayStatus, Long> countByCard = new EnumMap<>(SettlementDisplayStatus.class);
        for (SettlementDisplayStatus card : CARD_ORDER) {
            amountByCard.put(card, BigDecimal.ZERO);
            countByCard.put(card, 0L);
        }

        for (SettlementStatusAggregate aggregate : settlementSummaryQueryRepository.aggregateByStatus()) {
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
        SettlementListQueryRepository.SettlementPage page =
                settlementListQueryRepository.findPage(query.status(), query.page(), query.size());
        return SettlementListResponse.from(page.content(), page.totalElements(), query.page(), query.size());
    }
}
