package com.prompthub.admin.settlement.service;

import com.prompthub.admin.global.exception.AdminErrorCode;
import com.prompthub.admin.global.exception.AdminException;
import com.prompthub.admin.settlement.dto.SettlementListQuery;
import com.prompthub.admin.settlement.dto.SettlementWeeklyListQuery;
import com.prompthub.admin.settlement.entity.Settlement;
import com.prompthub.admin.settlement.entity.SettlementSourceLine;
import com.prompthub.admin.settlement.entity.enums.SettlementDisplayStatus;
import com.prompthub.admin.settlement.repository.SettlementMonthlyQueryRepository;
import com.prompthub.admin.settlement.repository.SettlementMonthlyQueryRepository.MonthlyAggregate;
import com.prompthub.admin.settlement.repository.SettlementMonthlyQueryRepository.MonthlyKey;
import com.prompthub.admin.settlement.repository.SettlementMonthlyQueryRepository.MonthlyPage;
import com.prompthub.admin.settlement.repository.SettlementMonthlyQueryRepository.MonthlyStatusCount;
import com.prompthub.admin.settlement.repository.SettlementQueryRepository;
import com.prompthub.admin.settlement.repository.SettlementRepository;
import com.prompthub.admin.settlement.repository.SettlementSourceRepository;
import com.prompthub.admin.settlement.repository.SettlementStatusAggregate;
import com.prompthub.admin.settlement.repository.SettlementWeeklyQueryRepository;
import com.prompthub.admin.settlement.repository.SettlementWeeklyQueryRepository.WeeklyPage;
import com.prompthub.admin.settlement.dto.response.SettlementDetailResponse;
import com.prompthub.admin.settlement.dto.response.SettlementListResponse;
import com.prompthub.admin.settlement.dto.response.SettlementResponse;
import com.prompthub.admin.settlement.dto.response.SettlementStatusResponse;
import com.prompthub.admin.settlement.dto.response.SettlementSummaryResponse;
import com.prompthub.admin.settlement.dto.response.SettlementSummaryResponse.Card;
import com.prompthub.admin.settlement.dto.response.SettlementWeeklyListResponse;
import com.prompthub.admin.user.application.service.UserApplicationService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class SettlementService {

	private static final List<SettlementDisplayStatus> CARD_ORDER = List.of(
		SettlementDisplayStatus.WAITING,
		SettlementDisplayStatus.APPROVED,
		SettlementDisplayStatus.PAYOUT_ON_HOLD,
		SettlementDisplayStatus.PAID
	);

	private final SettlementQueryRepository settlementQueryRepository;
	private final SettlementMonthlyQueryRepository monthlyQueryRepository;
	private final SettlementWeeklyQueryRepository weeklyQueryRepository;
	private final UserApplicationService userApplicationService;
	private final SettlementRepository settlementRepository;
	private final SettlementSourceRepository settlementSourceRepository;

	public SettlementListResponse getList(SettlementListQuery query) {
		MonthlyPage page = monthlyQueryRepository.findMonthlyPage(
			query.status(), query.settlementMonth(), query.page(), query.size());
		List<MonthlyKey> keys = page.content().stream()
			.map(MonthlyAggregate::key)
			.toList();
		List<MonthlyStatusCount> counts = monthlyQueryRepository.findStatusCounts(keys);
		List<UUID> sellerIds = keys.stream()
			.map(MonthlyKey::sellerId)
			.distinct()
			.toList();
		Map<UUID, String> sellerNames =
			userApplicationService.findNamesByIds(sellerIds);
		sellerIds.stream()
			.filter(sellerId -> !sellerNames.containsKey(sellerId))
			.forEach(sellerId ->
				log.warn("정산 판매자명 조회 누락 - sellerId={}", sellerId));
		return SettlementListResponse.from(
			page, counts, sellerNames, query.page(), query.size());
	}

	public SettlementWeeklyListResponse getWeeklyList(SettlementWeeklyListQuery query) {
		WeeklyPage page = weeklyQueryRepository.findWeeklyPage(
			query.status(), query.settlementMonth(), query.page(), query.size());
		List<UUID> sellerIds = page.content().stream()
			.map(Settlement::getSellerId)
			.distinct()
			.toList();
		Map<UUID, String> sellerNames = sellerIds.isEmpty()
			? Map.of()
			: userApplicationService.findNamesByIds(sellerIds);
		sellerIds.stream()
			.filter(sellerId -> !sellerNames.containsKey(sellerId))
			.forEach(sellerId ->
				log.warn("정산 판매자명 조회 누락 - sellerId={}", sellerId));
		return SettlementWeeklyListResponse.from(
			page, weeklyQueryRepository.findStatusCounts(query.settlementMonth()), sellerNames,
			query.page(), query.size());
	}

	public SettlementDetailResponse getDetail(
		UUID sellerId, YearMonth settlementMonth) {
		MonthlyAggregate aggregate = monthlyQueryRepository
			.findMonthlyAggregate(sellerId, settlementMonth)
			.orElseThrow(() ->
				new AdminException(AdminErrorCode.SETTLEMENT_NOT_FOUND));
		List<MonthlyStatusCount> counts = monthlyQueryRepository
			.findStatusCounts(List.of(aggregate.key()));
		List<Settlement> weeklySettlements = monthlyQueryRepository
			.findWeeklySettlements(sellerId, settlementMonth);
		Map<UUID, String> sellerNames =
			userApplicationService.findNamesByIds(List.of(sellerId));
		String sellerName = sellerNames.get(sellerId);
		if (sellerName == null) {
			log.warn("정산 판매자명 조회 누락 - sellerId={}", sellerId);
		}
		return SettlementDetailResponse.from(
			aggregate, counts, weeklySettlements, sellerName);
	}

	public SettlementSummaryResponse getSummary(YearMonth settlementMonth) {
		Map<SettlementDisplayStatus, BigDecimal> amountByCard = new EnumMap<>(SettlementDisplayStatus.class);
		Map<SettlementDisplayStatus, Long> countByCard = new EnumMap<>(SettlementDisplayStatus.class);
		for (SettlementDisplayStatus card : CARD_ORDER) {
			amountByCard.put(card, BigDecimal.ZERO);
			countByCard.put(card, 0L);
		}

		for (SettlementStatusAggregate aggregate
			: settlementQueryRepository.aggregateByStatus(settlementMonth)) {
			SettlementDisplayStatus card = aggregate.status().toCard();
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

	@Transactional
	public SettlementStatusResponse approve(UUID settlementId) {
		Settlement settlement = findSettlement(settlementId);
		settlement.approve(LocalDateTime.now());
		settlementRepository.save(settlement);
		return SettlementStatusResponse.from(settlement);
	}

	@Transactional
	public SettlementStatusResponse hold(UUID settlementId) {
		Settlement settlement = findSettlement(settlementId);
		settlement.hold();
		settlementRepository.save(settlement);
		return SettlementStatusResponse.from(settlement);
	}

	@Transactional
	public SettlementStatusResponse releaseHold(UUID settlementId) {
		Settlement settlement = findSettlement(settlementId);
		settlement.releaseHold();
		settlementRepository.save(settlement);
		return SettlementStatusResponse.from(settlement);
	}

	@Transactional
	public SettlementStatusResponse payout(UUID settlementId) {
		Settlement settlement = findSettlement(settlementId);
		settlement.payout(LocalDateTime.now());
		settlementRepository.save(settlement);
		return SettlementStatusResponse.from(settlement);
	}

	@Transactional
	public SettlementStatusResponse payoutHold(UUID settlementId) {
		Settlement settlement = findSettlement(settlementId);
		settlement.payoutHold();
		settlementRepository.save(settlement);
		return SettlementStatusResponse.from(settlement);
	}

	@Transactional
	public SettlementStatusResponse releasePayoutHold(UUID settlementId) {
		Settlement settlement = findSettlement(settlementId);
		settlement.releasePayoutHold();
		settlementRepository.save(settlement);
		return SettlementStatusResponse.from(settlement);
	}

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
		return settlementRepository.findBySettlementId(settlementId)
			.orElseThrow(() -> new AdminException(AdminErrorCode.SETTLEMENT_NOT_FOUND));
	}
}
