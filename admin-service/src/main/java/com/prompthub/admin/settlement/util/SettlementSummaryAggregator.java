package com.prompthub.admin.settlement.util;

import com.prompthub.admin.settlement.entity.enums.SettlementDisplayStatus;
import com.prompthub.admin.settlement.repository.SettlementStatusAggregate;
import com.prompthub.admin.settlement.dto.response.SettlementSummaryResponse.Card;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 상태별 집계(SettlementStatusAggregate)를 요약 카드 순서·버킷 기준으로 재배열하는 순수 계산.
 * 엔티티 상태를 참조하지 않고 입력값만으로 결과가 정해진다.
 */
public final class SettlementSummaryAggregator {

	private static final List<SettlementDisplayStatus> CARD_ORDER = List.of(
		SettlementDisplayStatus.WAITING,
		SettlementDisplayStatus.APPROVED,
		SettlementDisplayStatus.PAYOUT_ON_HOLD,
		SettlementDisplayStatus.PAID
	);

	private SettlementSummaryAggregator() {
	}

	public static List<Card> toCards(List<SettlementStatusAggregate> aggregates) {
		Map<SettlementDisplayStatus, BigDecimal> amountByCard = new EnumMap<>(SettlementDisplayStatus.class);
		Map<SettlementDisplayStatus, Long> countByCard = new EnumMap<>(SettlementDisplayStatus.class);
		for (SettlementDisplayStatus card : CARD_ORDER) {
			amountByCard.put(card, BigDecimal.ZERO);
			countByCard.put(card, 0L);
		}

		for (SettlementStatusAggregate aggregate : aggregates) {
			SettlementDisplayStatus card = aggregate.status().toCard();
			if (card == null) {
				continue;
			}
			amountByCard.merge(card, aggregate.sumSettlementTotal(), BigDecimal::add);
			countByCard.merge(card, aggregate.count(), Long::sum);
		}

		return CARD_ORDER.stream()
			.map(card -> new Card(card.name(), amountByCard.get(card), countByCard.get(card)))
			.toList();
	}
}
