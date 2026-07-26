package com.prompthub.admin.home.dto.response;

import com.prompthub.admin.home.dto.HomeResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Schema(description = "어드민 홈 통합 조회 응답")
public record HomeResponse(
	OffsetDateTime generatedAt,
	Users users,
	Transactions transactions,
	Settlements settlements,
	PendingProducts pendingProducts
) {

	public static HomeResponse from(HomeResult result) {
		HomeResult.Recent7Days recent = result.transactions().recent7Days();
		return new HomeResponse(
			result.generatedAt(),
			new Users(result.users().totalUsers(), result.users().todayNewUsers()),
			new Transactions(
				result.transactions().monthlyTransactionAmount(),
				new Recent7Days(
					recent.totalTransactionCount(),
					recent.totalTransactionAmount(),
					new Period(recent.period().startDate(), recent.period().endDate()),
					recent.dailyTransactions().stream()
						.map(item -> new DailyTransaction(
							item.date(),
							item.transactionCount(),
							item.transactionAmount()
						))
						.toList()
				)
			),
			new Settlements(
				result.settlements().pendingApprovalAmount(),
				result.settlements().pendingApprovalCount()
			),
			new PendingProducts(
				result.pendingProducts().totalCount(),
				result.pendingProducts().items().stream()
					.map(item -> new PendingProduct(
						item.productId(),
						item.title(),
						item.sellerNickname(),
						item.productType(),
						item.model(),
						item.amount(),
						item.status(),
						item.createdAt()
					))
					.toList()
			)
		);
	}

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
	}

	public record Period(LocalDate startDate, LocalDate endDate) {
	}

	public record DailyTransaction(LocalDate date, long transactionCount, long transactionAmount) {
	}

	public record Settlements(BigDecimal pendingApprovalAmount, long pendingApprovalCount) {
	}

	public record PendingProducts(long totalCount, List<PendingProduct> items) {
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
