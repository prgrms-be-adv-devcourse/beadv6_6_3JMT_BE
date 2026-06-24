package com.prompthub.settlement.application.service;

import com.prompthub.settlement.application.dto.SettlementSummaryResult;
import com.prompthub.settlement.application.dto.SettlementSummaryResult.Card;
import com.prompthub.settlement.application.usecase.GetSettlementSummaryUseCase;
import com.prompthub.settlement.domain.model.enums.SettlementDisplayStatus;
import com.prompthub.settlement.domain.repository.SettlementStatusAggregate;
import com.prompthub.settlement.domain.repository.SettlementSummaryQueryRepository;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 정산 요약 카드 조회 구현체.
 *
 * <p>상태쌍 집계를 표시 상태(SettlementDisplayStatus.from)로 파생한 뒤, 화면 고정 4카드 버킷으로
 * 접어 합산한다. 카드는 항상 WAITING/APPROVED/PAYOUT_ON_HOLD/PAID 4종을 순서대로 반환한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SettlementSummaryApplicationService implements GetSettlementSummaryUseCase {

    private static final List<SettlementDisplayStatus> CARD_ORDER = List.of(
            SettlementDisplayStatus.WAITING,
            SettlementDisplayStatus.APPROVED,
            SettlementDisplayStatus.PAYOUT_ON_HOLD,
            SettlementDisplayStatus.PAID);

    private final SettlementSummaryQueryRepository settlementSummaryQueryRepository;

    @Override
    public SettlementSummaryResult getSummary() {
        Map<SettlementDisplayStatus, BigDecimal> amountByCard = new EnumMap<>(SettlementDisplayStatus.class);
        Map<SettlementDisplayStatus, Long> countByCard = new EnumMap<>(SettlementDisplayStatus.class);
        for (SettlementDisplayStatus card : CARD_ORDER) {
            amountByCard.put(card, BigDecimal.ZERO);
            countByCard.put(card, 0L);
        }

        for (SettlementStatusAggregate aggregate : settlementSummaryQueryRepository.aggregateByStatus()) {
            SettlementDisplayStatus card = toCard(
                    SettlementDisplayStatus.from(aggregate.settlementStatus(), aggregate.payoutStatus()));
            if (card == null) {
                continue;
            }
            amountByCard.merge(card, aggregate.sumSettlementTotal(), BigDecimal::add);
            countByCard.merge(card, aggregate.count(), Long::sum);
        }

        List<Card> cards = CARD_ORDER.stream()
                .map(card -> new Card(card, amountByCard.get(card), countByCard.get(card)))
                .toList();
        return new SettlementSummaryResult(cards);
    }

    private static SettlementDisplayStatus toCard(SettlementDisplayStatus displayStatus) {
        return switch (displayStatus) {
            case WAITING, APPROVAL_ON_HOLD -> SettlementDisplayStatus.WAITING;
            case APPROVED, PAYOUT_REQUESTED -> SettlementDisplayStatus.APPROVED;
            case PAYOUT_ON_HOLD -> SettlementDisplayStatus.PAYOUT_ON_HOLD;
            case PAID -> SettlementDisplayStatus.PAID;
            case CANCELLED -> null;
        };
    }
}
