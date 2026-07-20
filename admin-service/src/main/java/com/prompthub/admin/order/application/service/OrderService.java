package com.prompthub.admin.order.application.service;

import com.prompthub.admin.order.application.dto.DailyTransactionProjection;
import com.prompthub.admin.order.application.dto.OrderListProjection;
import com.prompthub.admin.order.application.usecase.OrderUseCase;
import com.prompthub.admin.order.domain.model.SellerNickname;
import com.prompthub.admin.order.infrastructure.persistence.SellerNicknameRepository;
import com.prompthub.admin.order.presentation.dto.request.OrderSearchCondition;
import com.prompthub.admin.order.presentation.dto.response.DailyTransactionResponse;
import com.prompthub.admin.order.presentation.dto.response.MonthlyTradeAmountResponse;
import com.prompthub.admin.order.presentation.dto.response.OrderListResponse;
import com.prompthub.admin.order.presentation.dto.response.TransactionPeriodResponse;
import com.prompthub.admin.order.presentation.dto.response.WeeklyTransactionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OrderService implements OrderUseCase {

	private static final int RECENT_DAYS = 7;
	private static final String UNKNOWN_SELLER_NICKNAME = "알 수 없음";

	private final OrderQueryService orderQueryService;
	private final SellerNicknameRepository sellerNicknameRepository;

	@Override
	public Page<OrderListResponse> getOrders(OrderSearchCondition condition) {
		PageRequest pageable = PageRequest.of(
			condition.page() - 1,
			condition.size(),
			Sort.by(Sort.Direction.DESC, "createdAt")
		);
		Page<OrderListProjection> orders = orderQueryService.searchOrders(condition, pageable);
		List<UUID> sellerIds = collectSellerIds(orders.getContent());
		Map<UUID, String> sellerNicknames = sellerIds.isEmpty()
			? Map.of()
			: sellerNicknameRepository.findAllById(sellerIds).stream()
				.collect(Collectors.toMap(
					SellerNickname::getSellerId,
					SellerNickname::getNickname,
					(existing, ignored) -> existing
				));

		return orders.map(projection -> toOrderListResponse(projection, sellerNicknames));
	}

	@Override
	public MonthlyTradeAmountResponse getMonthlyTransactionAmount() {
		LocalDate today = LocalDate.now();
		LocalDateTime start = today.withDayOfMonth(1).atStartOfDay();
		LocalDateTime endExclusive = today.plusMonths(1).withDayOfMonth(1).atStartOfDay();

		return new MonthlyTradeAmountResponse(
			orderQueryService.sumMonthlyTransactionAmount(start, endExclusive)
		);
	}

	@Override
	public WeeklyTransactionResponse getWeeklyTransactions() {
		LocalDate endDate = LocalDate.now();
		LocalDate startDate = endDate.minusDays(RECENT_DAYS - 1L);
		LocalDateTime start = startDate.atStartOfDay();
		LocalDateTime endExclusive = endDate.plusDays(1).atStartOfDay();

		Map<LocalDate, DailyTransactionProjection> dailyTransactions = new LinkedHashMap<>();
		orderQueryService.findDailyTransactions(start, endExclusive)
			.forEach(dailyTransaction -> dailyTransactions.put(dailyTransaction.date(), dailyTransaction));

		List<DailyTransactionResponse> responses = startDate.datesUntil(endDate.plusDays(1))
			.map(date -> toDailyTransactionResponse(date, dailyTransactions.get(date)))
			.toList();

		long totalTransactionCount = responses.stream()
			.mapToLong(DailyTransactionResponse::transactionCount)
			.sum();
		long totalTransactionAmount = responses.stream()
			.mapToLong(DailyTransactionResponse::transactionAmount)
			.sum();

		return new WeeklyTransactionResponse(
			totalTransactionCount,
			totalTransactionAmount,
			new TransactionPeriodResponse(startDate, endDate),
			responses
		);
	}

	private OrderListResponse toOrderListResponse(
		OrderListProjection projection,
		Map<UUID, String> sellerNicknames
	) {
		List<OrderListResponse.SellerSummary> sellers = projection.sellers().stream()
			.map(seller -> new OrderListResponse.SellerSummary(
				seller.sellerId(),
				sellerNicknames.getOrDefault(seller.sellerId(), UNKNOWN_SELLER_NICKNAME),
				seller.productCount(),
				seller.orderAmount()
			))
			.toList();

		return new OrderListResponse(
			projection.orderId(),
			sellers.size(),
			sellers,
			projection.productTitle(),
			projection.totalOrderCount(),
			projection.totalOrderAmount(),
			projection.orderStatus(),
			projection.createdAt()
		);
	}

	private List<UUID> collectSellerIds(List<OrderListProjection> orders) {
		if (orders.isEmpty()) {
			return List.of();
		}
		return orders.stream()
			.flatMap(order -> order.sellers().stream())
			.map(OrderListProjection.SellerSummary::sellerId)
			.collect(Collectors.toCollection(LinkedHashSet::new))
			.stream()
			.toList();
	}

	private DailyTransactionResponse toDailyTransactionResponse(
		LocalDate date,
		DailyTransactionProjection projection
	) {
		if (projection == null) {
			return new DailyTransactionResponse(date, 0L, 0L);
		}
		return new DailyTransactionResponse(
			date,
			projection.transactionCount(),
			projection.transactionAmount()
		);
	}
}
