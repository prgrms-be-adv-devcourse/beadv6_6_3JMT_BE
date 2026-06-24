package com.prompthub.order.application.service;

import com.prompthub.order.application.client.SellerClient;
import com.prompthub.order.application.dto.AdminDailyTransactionProjection;
import com.prompthub.order.application.dto.AdminOrderListProjection;
import com.prompthub.order.application.usecase.AdminOrderUseCase;
import com.prompthub.order.domain.repository.AdminOrderQueryRepository;
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
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AdminOrderService implements AdminOrderUseCase {

	private static final String UNKNOWN_SELLER_NICKNAME = "알 수 없음";
	private static final int RECENT_DAYS = 7;

	private final AdminOrderQueryRepository adminOrderQueryRepository;
	private final SellerClient sellerClient;

	@Override
	public Page<AdminOrderListResponse> getAdminOrders(AdminOrderSearchCondition condition) {
		PageRequest pageable = PageRequest.of(
			condition.page() - 1,
			condition.size(),
			Sort.by(Sort.Direction.DESC, "createdAt")
		);
		Page<AdminOrderListProjection> orders = adminOrderQueryRepository.searchAdminOrders(condition, pageable);
		Map<UUID, String> sellerNicknames = getSellerNicknames(orders.getContent());

		return orders.map(order -> toAdminOrderListResponse(order, sellerNicknames));
	}

	@Override
	public AdminMonthlyTradeAmountResponse getMonthlyTransactionAmount() {
		LocalDate today = LocalDate.now();
		LocalDateTime start = today.withDayOfMonth(1).atStartOfDay();
		LocalDateTime endExclusive = today.plusMonths(1).withDayOfMonth(1).atStartOfDay();

		return new AdminMonthlyTradeAmountResponse(
			adminOrderQueryRepository.sumMonthlyTransactionAmount(start, endExclusive)
		);
	}

	@Override
	public AdminWeeklyTransactionResponse getWeeklyTransactions() {
		LocalDate endDate = LocalDate.now();
		LocalDate startDate = endDate.minusDays(RECENT_DAYS - 1L);
		LocalDateTime start = startDate.atStartOfDay();
		LocalDateTime endExclusive = endDate.plusDays(1).atStartOfDay();

		Map<LocalDate, AdminDailyTransactionProjection> dailyTransactions = new LinkedHashMap<>();
		adminOrderQueryRepository.findDailyTransactions(start, endExclusive)
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

	private Map<UUID, String> getSellerNicknames(List<AdminOrderListProjection> orders) {
		List<UUID> sellerIds = orders.stream()
			.map(AdminOrderListProjection::sellerId)
			.distinct()
			.toList();

		if (sellerIds.isEmpty()) {
			return Map.of();
		}

		try {
			return sellerClient.getSellerNicknames(sellerIds);
		} catch (RuntimeException exception) {
			log.warn("판매자 닉네임 bulk 조회에 실패했습니다. sellerIds={}", sellerIds, exception);
			return Map.of();
		}
	}

	private AdminOrderListResponse toAdminOrderListResponse(
		AdminOrderListProjection projection,
		Map<UUID, String> sellerNicknames
	) {
		return new AdminOrderListResponse(
			projection.orderId(),
			sellerNicknames.getOrDefault(projection.sellerId(), UNKNOWN_SELLER_NICKNAME),
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
