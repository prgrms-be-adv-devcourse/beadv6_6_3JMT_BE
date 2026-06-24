package com.prompthub.order.infra.persistence.order;

import com.prompthub.order.application.dto.AdminOrderListProjection;
import com.prompthub.order.application.dto.AdminDailyTransactionProjection;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.repository.AdminOrderQueryRepository;
import com.prompthub.order.presentation.dto.request.AdminOrderSearchCondition;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.prompthub.order.domain.model.QOrder.order;
import static com.prompthub.order.domain.model.QOrderPayment.orderPayment;
import static com.prompthub.order.domain.model.QOrderProduct.orderProduct;

@Repository
@RequiredArgsConstructor
public class AdminOrderQueryRepositoryImpl implements AdminOrderQueryRepository {

	private final JPAQueryFactory queryFactory;

	@Override
	public Page<AdminOrderListProjection> searchAdminOrders(AdminOrderSearchCondition condition, Pageable pageable) {
		List<UUID> orderIds = queryFactory
			.select(order.id)
			.from(order)
			.where(orderStatusEq(condition.resolvedOrderStatus()))
			.orderBy(order.createdAt.desc())
			.offset(pageable.getOffset())
			.limit(pageable.getPageSize())
			.fetch();

		JPAQuery<Long> countQuery = queryFactory
			.select(order.count())
			.from(order)
			.where(orderStatusEq(condition.resolvedOrderStatus()));

		if (orderIds.isEmpty()) {
			Long total = countQuery.fetchOne();
			return new PageImpl<>(List.of(), pageable, total == null ? 0L : total);
		}

		List<Tuple> rows = queryFactory
			.select(
				order.id,
				orderProduct.sellerId,
				orderProduct.productTitle,
				order.totalProductCount,
				order.totalOrderAmount,
				order.orderStatus,
				order.createdAt,
				orderProduct.createdAt,
				orderProduct.id
			)
			.from(order)
			.join(order.orderProducts, orderProduct)
			.where(order.id.in(orderIds))
			.orderBy(order.createdAt.desc(), orderProduct.createdAt.asc(), orderProduct.id.asc())
			.fetch();

		Map<UUID, AdminOrderListProjection> projections = new LinkedHashMap<>();
		for (Tuple row : rows) {
			UUID orderId = row.get(order.id);
			if (projections.containsKey(orderId)) {
				continue;
			}

			Integer totalProductCount = row.get(order.totalProductCount);
			String firstProductTitle = row.get(orderProduct.productTitle);
			projections.put(orderId, new AdminOrderListProjection(
				orderId,
				row.get(orderProduct.sellerId),
				formatProductTitle(firstProductTitle, totalProductCount == null ? 0 : totalProductCount),
				totalProductCount == null ? 0 : totalProductCount,
				valueOrZero(row.get(order.totalOrderAmount)),
				row.get(order.orderStatus),
				row.get(order.createdAt)
			));
		}

		List<AdminOrderListProjection> content = new ArrayList<>(projections.values());
		content.sort(Comparator.comparing(AdminOrderListProjection::createdAt).reversed());
		Long total = countQuery.fetchOne();
		return new PageImpl<>(content, pageable, total == null ? 0L : total);
	}

	@Override
	public long sumMonthlyTransactionAmount(LocalDateTime startInclusive, LocalDateTime endExclusive) {
		long approvedAmount = sumApprovedAmount(startInclusive, endExclusive);
		long canceledAmount = sumCanceledAmount(startInclusive, endExclusive);
		long refundedAmount = sumRefundedAmount(startInclusive, endExclusive);
		return approvedAmount - canceledAmount - refundedAmount;
	}

	@Override
	public List<AdminDailyTransactionProjection> findDailyTransactions(
		LocalDateTime startInclusive,
		LocalDateTime endExclusive
	) {
		Map<LocalDate, DailyTransactionAccumulator> dailyTransactions = new LinkedHashMap<>();

		for (Tuple row : fetchApprovedDailyRows(startInclusive, endExclusive)) {
			LocalDateTime approvedAt = row.get(orderPayment.approvedAt);
			LocalDate date = approvedAt.toLocalDate();
			Integer amount = row.get(orderPayment.approvedAmount);
			dailyTransactions.computeIfAbsent(date, ignored -> new DailyTransactionAccumulator())
				.addApproved(1L, amount == null ? 0L : amount);
		}

		subtractDailyAmounts(dailyTransactions, fetchCanceledDailyRows(startInclusive, endExclusive), order.canceledAt);
		subtractDailyAmounts(dailyTransactions, fetchRefundedDailyRows(startInclusive, endExclusive), order.refundedAt);

		return dailyTransactions.entrySet().stream()
			.sorted(Map.Entry.comparingByKey())
			.map(entry -> new AdminDailyTransactionProjection(
				entry.getKey(),
				entry.getValue().transactionCount,
				entry.getValue().transactionAmount
			))
			.toList();
	}

	private BooleanExpression orderStatusEq(OrderStatus orderStatus) {
		return orderStatus == null ? null : order.orderStatus.eq(orderStatus);
	}

	private List<Tuple> fetchApprovedDailyRows(LocalDateTime startInclusive, LocalDateTime endExclusive) {
		return queryFactory
			.select(orderPayment.approvedAt, orderPayment.approvedAmount)
			.from(orderPayment)
			.where(dateTimeGoe(orderPayment.approvedAt, startInclusive), dateTimeLt(orderPayment.approvedAt, endExclusive))
			.fetch();
	}

	private List<Tuple> fetchCanceledDailyRows(LocalDateTime startInclusive, LocalDateTime endExclusive) {
		return queryFactory
			.select(order.canceledAt, orderPayment.approvedAmount)
			.from(orderPayment)
			.join(order).on(order.id.eq(orderPayment.orderId))
			.where(
				order.orderStatus.eq(OrderStatus.CANCELED),
				dateTimeGoe(order.canceledAt, startInclusive),
				dateTimeLt(order.canceledAt, endExclusive)
			)
			.fetch();
	}

	private List<Tuple> fetchRefundedDailyRows(LocalDateTime startInclusive, LocalDateTime endExclusive) {
		return queryFactory
			.select(order.refundedAt, orderPayment.approvedAmount)
			.from(orderPayment)
			.join(order).on(order.id.eq(orderPayment.orderId))
			.where(
				order.orderStatus.eq(OrderStatus.REFUNDED),
				dateTimeGoe(order.refundedAt, startInclusive),
				dateTimeLt(order.refundedAt, endExclusive)
			)
			.fetch();
	}

	private void subtractDailyAmounts(
		Map<LocalDate, DailyTransactionAccumulator> dailyTransactions,
		List<Tuple> rows,
		com.querydsl.core.types.dsl.DateTimePath<LocalDateTime> dateTimePath
	) {
		for (Tuple row : rows) {
			LocalDateTime occurredAt = row.get(dateTimePath);
			LocalDate date = occurredAt.toLocalDate();
			Integer amount = row.get(orderPayment.approvedAmount);
			dailyTransactions.computeIfAbsent(date, ignored -> new DailyTransactionAccumulator())
				.subtract(amount == null ? 0L : amount);
		}
	}

	private long sumApprovedAmount(LocalDateTime startInclusive, LocalDateTime endExclusive) {
		Integer amount = queryFactory
			.select(orderPayment.approvedAmount.sum())
			.from(orderPayment)
			.where(dateTimeGoe(orderPayment.approvedAt, startInclusive), dateTimeLt(orderPayment.approvedAt, endExclusive))
			.fetchOne();
		return amount == null ? 0L : amount;
	}

	private long sumCanceledAmount(LocalDateTime startInclusive, LocalDateTime endExclusive) {
		Integer amount = queryFactory
			.select(orderPayment.approvedAmount.sum())
			.from(orderPayment)
			.join(order).on(order.id.eq(orderPayment.orderId))
			.where(
				order.orderStatus.eq(OrderStatus.CANCELED),
				dateTimeGoe(order.canceledAt, startInclusive),
				dateTimeLt(order.canceledAt, endExclusive)
			)
			.fetchOne();
		return amount == null ? 0L : amount;
	}

	private long sumRefundedAmount(LocalDateTime startInclusive, LocalDateTime endExclusive) {
		Integer amount = queryFactory
			.select(orderPayment.approvedAmount.sum())
			.from(orderPayment)
			.join(order).on(order.id.eq(orderPayment.orderId))
			.where(
				order.orderStatus.eq(OrderStatus.REFUNDED),
				dateTimeGoe(order.refundedAt, startInclusive),
				dateTimeLt(order.refundedAt, endExclusive)
			)
			.fetchOne();
		return amount == null ? 0L : amount;
	}

	private BooleanExpression dateTimeGoe(
		com.querydsl.core.types.dsl.DateTimePath<LocalDateTime> dateTimePath,
		LocalDateTime value
	) {
		return value == null ? null : dateTimePath.goe(value);
	}

	private BooleanExpression dateTimeLt(
		com.querydsl.core.types.dsl.DateTimePath<LocalDateTime> dateTimePath,
		LocalDateTime value
	) {
		return value == null ? null : dateTimePath.lt(value);
	}

	private int valueOrZero(Integer value) {
		return value == null ? 0 : value;
	}

	private String formatProductTitle(String firstProductTitle, int totalProductCount) {
		if (totalProductCount <= 1) {
			return firstProductTitle;
		}
		return firstProductTitle + " 외 " + (totalProductCount - 1) + "건";
	}

	private static class DailyTransactionAccumulator {
		private long transactionCount;
		private long transactionAmount;

		private void addApproved(long transactionCount, long amount) {
			this.transactionCount += transactionCount;
			this.transactionAmount += amount;
		}

		private void subtract(long amount) {
			this.transactionAmount -= amount;
		}
	}
}
