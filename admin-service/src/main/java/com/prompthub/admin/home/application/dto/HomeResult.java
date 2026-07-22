package com.prompthub.admin.home.application.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record HomeResult(
	OffsetDateTime generatedAt,
	Users users,
	Transactions transactions,
	Settlements settlements,
	PendingProducts pendingProducts
) {

	public record Users(long totalUsers, long todayNewUsers) {
	}

	public record Transactions(long monthlyTransactionAmount, Recent7Days recent7Days) {
	}

	public record Recent7Days(
		long totalTransactionCount,
		long totalTransactionAmount,
		Period period,
		List<DailyTransaction> dailyTransactions
	) {
		public Recent7Days {
			dailyTransactions = List.copyOf(dailyTransactions);
		}
	}

	public record Period(LocalDate startDate, LocalDate endDate) {
	}

	public record DailyTransaction(LocalDate date, long transactionCount, long transactionAmount) {
	}

	public record Settlements(BigDecimal pendingApprovalAmount, long pendingApprovalCount) {
	}

	public record PendingProducts(long totalCount, List<PendingProduct> items) {
		public PendingProducts {
			items = List.copyOf(items);
		}
	}

	public record PendingProduct(
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
