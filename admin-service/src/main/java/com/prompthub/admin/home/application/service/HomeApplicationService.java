package com.prompthub.admin.home.application.service;

import com.prompthub.admin.home.application.dto.HomeResult;
import com.prompthub.admin.home.infrastructure.persistence.HomeQueryRepository;
import com.prompthub.admin.home.infrastructure.persistence.HomeQueryRepository.PendingProductPreview;
import com.prompthub.admin.home.infrastructure.persistence.HomeQueryRepository.SettlementSummary;
import com.prompthub.admin.home.infrastructure.persistence.HomeQueryRepository.UserSummary;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class HomeApplicationService {

	private static final int RECENT_DAYS = 7;
	private static final int PRODUCT_PREVIEW_LIMIT = 4;

	private final HomeQueryRepository repository;
	private final Clock clock;
	private final ZoneId zoneId;

	public HomeApplicationService(
		HomeQueryRepository repository,
		@Qualifier("homeClock") Clock clock,
		@Qualifier("homeZoneId") ZoneId zoneId
	) {
		this.repository = repository;
		this.clock = clock;
		this.zoneId = zoneId;
	}

	public HomeResult getHome() {
		ZonedDateTime generatedAt = clock.instant().atZone(zoneId);
		LocalDate today = generatedAt.toLocalDate();
		LocalDate sevenDaysStart = today.minusDays(RECENT_DAYS - 1L);

		UserSummary users = repository.findUserSummary(
			today.atStartOfDay(), today.plusDays(1).atStartOfDay());
		long monthlyAmount = repository.findMonthlyTransactionAmount(
			today.withDayOfMonth(1).atStartOfDay(),
			today.plusMonths(1).withDayOfMonth(1).atStartOfDay());
		Map<LocalDate, HomeQueryRepository.DailyTransaction> indexedDaily = repository
			.findDailyTransactions(sevenDaysStart.atStartOfDay(), today.plusDays(1).atStartOfDay())
			.stream()
			.collect(Collectors.toMap(
				HomeQueryRepository.DailyTransaction::date,
				Function.identity()
			));
		List<HomeResult.DailyTransaction> daily = sevenDaysStart.datesUntil(today.plusDays(1))
			.map(date -> indexedDaily.containsKey(date)
				? toResult(indexedDaily.get(date))
				: new HomeResult.DailyTransaction(date, 0L, 0L))
			.toList();
		SettlementSummary settlements = repository.findPendingApprovalSettlementSummary();
		PendingProductPreview products = repository.findPendingProductPreview(PRODUCT_PREVIEW_LIMIT);

		return toHomeResult(
			generatedAt.toOffsetDateTime(),
			users,
			monthlyAmount,
			sevenDaysStart,
			today,
			daily,
			settlements,
			products
		);
	}

	private HomeResult toHomeResult(
		OffsetDateTime generatedAt,
		UserSummary users,
		long monthlyAmount,
		LocalDate periodStart,
		LocalDate periodEnd,
		List<HomeResult.DailyTransaction> daily,
		SettlementSummary settlements,
		PendingProductPreview products
	) {
		long totalCount = daily.stream()
			.mapToLong(HomeResult.DailyTransaction::transactionCount)
			.sum();
		long totalAmount = daily.stream()
			.mapToLong(HomeResult.DailyTransaction::transactionAmount)
			.sum();

		return new HomeResult(
			generatedAt,
			new HomeResult.Users(users.totalUsers(), users.todayNewUsers()),
			new HomeResult.Transactions(
				monthlyAmount,
				new HomeResult.Recent7Days(
					totalCount,
					totalAmount,
					new HomeResult.Period(periodStart, periodEnd),
					daily
				)
			),
			new HomeResult.Settlements(
				settlements.pendingApprovalAmount(),
				settlements.pendingApprovalCount()
			),
			new HomeResult.PendingProducts(
				products.totalCount(),
				products.items().stream().map(this::toResult).toList()
			)
		);
	}

	private HomeResult.DailyTransaction toResult(HomeQueryRepository.DailyTransaction source) {
		return new HomeResult.DailyTransaction(
			source.date(),
			source.transactionCount(),
			source.transactionAmount()
		);
	}

	private HomeResult.PendingProduct toResult(HomeQueryRepository.PendingProduct source) {
		return new HomeResult.PendingProduct(
			source.productId(),
			source.title(),
			source.sellerNickname(),
			source.productType(),
			source.model(),
			source.amount(),
			source.status(),
			source.createdAt()
		);
	}
}
