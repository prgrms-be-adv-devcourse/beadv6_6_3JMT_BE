package com.prompthub.admin.order.service;

import com.prompthub.admin.order.dto.DailyTransactionProjection;
import com.prompthub.admin.order.dto.OrderListProjection;
import com.prompthub.admin.order.repository.OrderQueryRepository;
import com.prompthub.admin.order.dto.request.OrderSearchCondition;
import com.prompthub.admin.order.dto.response.DailyTransactionResponse;
import com.prompthub.admin.order.dto.response.MonthlyTradeAmountResponse;
import com.prompthub.admin.order.dto.response.OrderListResponse;
import com.prompthub.admin.order.dto.response.TransactionPeriodResponse;
import com.prompthub.admin.order.dto.response.WeeklyTransactionResponse;
import com.prompthub.admin.user.application.service.UserApplicationService;
import com.prompthub.admin.user.domain.model.UserProfile;
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

@Service
@RequiredArgsConstructor
public class OrderService {

	private static final int RECENT_DAYS = 7;
	private final OrderQueryRepository orderQueryRepository;
	private final UserApplicationService userApplicationService;

	public Page<OrderListResponse> getOrders(OrderSearchCondition condition) {
		PageRequest pageable = PageRequest.of(
			condition.page() - 1,
			condition.size(),
			Sort.by(Sort.Direction.DESC, "createdAt")
		);
		Page<OrderListProjection> orders = orderQueryRepository.searchOrders(condition, pageable);
		List<UUID> userIds = collectUserIds(orders.getContent());
		Map<UUID, UserProfile> profiles = userIds.isEmpty()
			? Map.of()
			: userApplicationService.findProfilesByIds(userIds);

		return orders.map(projection -> toOrderListResponse(projection, profiles));
	}

	public MonthlyTradeAmountResponse getMonthlyTransactionAmount() {
		LocalDate today = LocalDate.now();
		LocalDateTime start = today.withDayOfMonth(1).atStartOfDay();
		LocalDateTime endExclusive = today.plusMonths(1).withDayOfMonth(1).atStartOfDay();

		return new MonthlyTradeAmountResponse(
			orderQueryRepository.sumMonthlyTransactionAmount(start, endExclusive)
		);
	}

	public WeeklyTransactionResponse getWeeklyTransactions() {
		LocalDate endDate = LocalDate.now();
		LocalDate startDate = endDate.minusDays(RECENT_DAYS - 1L);
		LocalDateTime start = startDate.atStartOfDay();
		LocalDateTime endExclusive = endDate.plusDays(1).atStartOfDay();

		Map<LocalDate, DailyTransactionProjection> dailyTransactions = new LinkedHashMap<>();
		orderQueryRepository.findDailyTransactions(start, endExclusive)
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
		Map<UUID, UserProfile> profiles
	) {
		List<OrderListResponse.OrderProductSummary> orderProducts = projection.orderProducts().stream()
			.map(orderProduct -> new OrderListResponse.OrderProductSummary(
				toUserSummary(orderProduct.sellerId(), profiles),
				orderProduct.productTitle(),
				orderProduct.productAmount(),
				orderProduct.orderProductStatus()
			))
			.toList();

		return new OrderListResponse(
			projection.orderNumber(),
			toUserSummary(projection.buyerId(), profiles),
			projection.totalOrderAmount(),
			projection.orderStatus(),
			projection.createdAt(),
			orderProducts
		);
	}

	private OrderListResponse.UserSummary toUserSummary(UUID userId, Map<UUID, UserProfile> profiles) {
		UserProfile profile = profiles.get(userId);
		return profile == null
			? new OrderListResponse.UserSummary(userId, "알 수 없음", null)
			: new OrderListResponse.UserSummary(profile.userId(), profile.name(), profile.profileImageUrl());
	}

	private List<UUID> collectUserIds(List<OrderListProjection> orders) {
		LinkedHashSet<UUID> userIds = new LinkedHashSet<>();
		orders.forEach(order -> {
			userIds.add(order.buyerId());
			order.orderProducts().forEach(orderProduct -> userIds.add(orderProduct.sellerId()));
		});
		return userIds.stream().toList();
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
