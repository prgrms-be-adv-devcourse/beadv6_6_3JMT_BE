package com.prompthub.admin.home.domain.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface HomeQueryRepository {

	UserSummary findUserSummary(LocalDateTime todayStartInclusive, LocalDateTime tomorrowStartExclusive);

	long findMonthlyTransactionAmount(LocalDateTime startInclusive, LocalDateTime endExclusive);

	List<DailyTransaction> findDailyTransactions(LocalDateTime startInclusive, LocalDateTime endExclusive);

	SettlementSummary findPendingApprovalSettlementSummary();

	PendingProductPreview findPendingProductPreview(int limit);

	record UserSummary(long totalUsers, long todayNewUsers) {
	}

	record DailyTransaction(LocalDate date, long transactionCount, long transactionAmount) {
	}

	record SettlementSummary(BigDecimal pendingApprovalAmount, long pendingApprovalCount) {
	}

	record PendingProductPreview(long totalCount, List<PendingProduct> items) {
		public PendingProductPreview {
			items = List.copyOf(items);
		}
	}

	record PendingProduct(
		UUID productId,
		String title,
		String sellerNickname,
		String productType,
		String model,
		int amount,
		String status,
		LocalDateTime createdAt
	) {
	}
}
