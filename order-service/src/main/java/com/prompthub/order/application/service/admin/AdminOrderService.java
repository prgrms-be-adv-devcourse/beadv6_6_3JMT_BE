package com.prompthub.order.application.service.admin;


import com.prompthub.order.application.dto.AdminDailyTransactionProjection;
import com.prompthub.order.application.dto.AdminOrderListProjection;
import com.prompthub.order.application.usecase.AdminOrderUseCase;
import com.prompthub.order.presentation.dto.request.AdminOrderSearchCondition;
import com.prompthub.order.presentation.dto.response.AdminDailyTransactionResponse;
import com.prompthub.order.presentation.dto.response.AdminMonthlyTradeAmountResponse;
import com.prompthub.order.presentation.dto.response.AdminOrderListResponse;
import com.prompthub.order.presentation.dto.response.AdminTransactionPeriodResponse;
import com.prompthub.order.presentation.dto.response.AdminWeeklyTransactionResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminOrderService implements AdminOrderUseCase {


	private static final int RECENT_DAYS = 7;

	private final AdminOrderQueryService adminOrderQueryService;

	@Override
	public Page<AdminOrderListResponse> getAdminOrders(AdminOrderSearchCondition condition) {
		PageRequest pageable = PageRequest.of(
			condition.page() - 1,
			condition.size(),
			Sort.by(Sort.Direction.DESC, "createdAt")
		);
		Page<AdminOrderListProjection> orders = adminOrderQueryService.searchAdminOrders(condition, pageable);

		return orders.map(this::toAdminOrderListResponse);
	}

	@Override
	public AdminMonthlyTradeAmountResponse getMonthlyTransactionAmount() {
		LocalDate today = LocalDate.now();
		LocalDateTime start = today.withDayOfMonth(1).atStartOfDay();
		LocalDateTime endExclusive = today.plusMonths(1).withDayOfMonth(1).atStartOfDay();

		return new AdminMonthlyTradeAmountResponse(
			adminOrderQueryService.sumMonthlyTransactionAmount(start, endExclusive)
		);
	}

	@Override
	public AdminWeeklyTransactionResponse getWeeklyTransactions() {
		LocalDate endDate = LocalDate.now();
		LocalDate startDate = endDate.minusDays(RECENT_DAYS - 1L);
		LocalDateTime start = startDate.atStartOfDay();
		LocalDateTime endExclusive = endDate.plusDays(1).atStartOfDay();

		Map<LocalDate, AdminDailyTransactionProjection> dailyTransactions = new LinkedHashMap<>();
		adminOrderQueryService.findDailyTransactions(start, endExclusive)
			.forEach(dailyTransaction -> dailyTransactions.put(dailyTransaction.date(), dailyTransaction));

		List<AdminDailyTransactionResponse> responses = startDate.datesUntil(endDate.plusDays(1))
			.map(date -> toDailyTransactionResponse(date, dailyTransactions.get(date)))
			.toList();

		long totalTransactionCount = responses.stream()
			.mapToLong(AdminDailyTransactionResponse::transactionCount)
			.sum();
		long totalTransactionAmount = responses.stream()
			.mapToLong(AdminDailyTransactionResponse::transactionAmount)
			.sum();

		return new AdminWeeklyTransactionResponse(
			totalTransactionCount,
			totalTransactionAmount,
			new AdminTransactionPeriodResponse(startDate, endDate),
			responses
		);
	}

	private AdminOrderListResponse toAdminOrderListResponse(
		AdminOrderListProjection projection
	) {
		return new AdminOrderListResponse(
			projection.orderId(),
			projection.sellerNickname(),
			projection.productTitle(),
			projection.totalOrderCount(),
			projection.totalOrderAmount(),
			projection.orderStatus(),
			projection.createdAt()
		);
	}

	private AdminDailyTransactionResponse toDailyTransactionResponse(
		LocalDate date,
		AdminDailyTransactionProjection projection
	) {
		if (projection == null) {
			return new AdminDailyTransactionResponse(date, 0L, 0L);
		}
		return new AdminDailyTransactionResponse(
			date,
			projection.transactionCount(),
			projection.transactionAmount()
		);
	}
}
