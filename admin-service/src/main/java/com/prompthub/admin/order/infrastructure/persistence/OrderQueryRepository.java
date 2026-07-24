package com.prompthub.admin.order.infrastructure.persistence;

import com.prompthub.admin.order.application.dto.DailyTransactionProjection;
import com.prompthub.admin.order.application.dto.OrderListProjection;
import com.prompthub.admin.order.domain.enums.OrderStatus;
import com.prompthub.admin.order.presentation.dto.request.OrderSearchCondition;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.DateExpression;
import com.querydsl.core.types.dsl.DateTimePath;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.prompthub.admin.order.domain.model.QOrder.order;
import static com.prompthub.admin.order.domain.model.QOrderProduct.orderProduct;

@Repository
@RequiredArgsConstructor
public class OrderQueryRepository {

	private final JPAQueryFactory queryFactory;

	public Page<OrderListProjection> searchOrders(OrderSearchCondition condition, Pageable pageable) {
		List<UUID> orderIds = queryFactory
			.select(order.id)
			.from(order)
			.where(orderStatusEq(condition.resolvedOrderStatus()))
			.orderBy(order.createdAt.desc(), order.id.desc())
			.offset(pageable.getOffset())
			.limit(pageable.getPageSize())
			.fetch();

		JPAQuery<Long> countQuery = queryFactory
			.select(order.count())
			.from(order)
			.where(orderStatusEq(condition.resolvedOrderStatus()));

		if (orderIds.isEmpty()) {
			return new PageImpl<>(List.of(), pageable, valueOrZero(countQuery.fetchOne()));
		}

		List<Tuple> rows = queryFactory
			.select(
				order.id,
				order.orderNumber,
				order.buyerId,
				orderProduct.sellerId,
				orderProduct.productTitle,
				orderProduct.productAmount,
				orderProduct.orderProductStatus,
				order.totalOrderAmount,
				order.orderStatus,
				order.createdAt,
				orderProduct.createdAt,
				orderProduct.id
			)
			.from(order)
			.join(order.orderProducts, orderProduct)
			.where(order.id.in(orderIds))
			.orderBy(order.createdAt.desc(), order.id.desc(), orderProduct.createdAt.asc(), orderProduct.id.asc())
			.fetch();

		Map<UUID, List<Tuple>> rowsByOrderId = new LinkedHashMap<>();
		rows.forEach(row -> rowsByOrderId
			.computeIfAbsent(row.get(order.id), ignored -> new ArrayList<>())
			.add(row));

		List<OrderListProjection> content = orderIds.stream()
			.map(rowsByOrderId::get)
			.filter(orderRows -> orderRows != null && !orderRows.isEmpty())
			.map(this::toOrderListProjection)
			.toList();

		return new PageImpl<>(content, pageable, valueOrZero(countQuery.fetchOne()));
	}

	public long sumMonthlyTransactionAmount(LocalDateTime startInclusive, LocalDateTime endExclusive) {
		return sumCompletedOrderAmount(startInclusive, endExclusive)
			- sumRefundedProductAmount(startInclusive, endExclusive);
	}

	public List<DailyTransactionProjection> findDailyTransactions(
		LocalDateTime startInclusive,
		LocalDateTime endExclusive
	) {
		Map<LocalDate, DailyTransactionAccumulator> dailyTransactions = new LinkedHashMap<>();

		DateExpression<java.sql.Date> completedDate = toDate(order.completedAt);
		NumberExpression<Long> completedCount = order.count();
		NumberExpression<Integer> completedAmount = order.totalOrderAmount.sum();
		queryFactory
			.select(completedDate, completedCount, completedAmount)
			.from(order)
			.where(
				dateTimeGoe(order.completedAt, startInclusive),
				dateTimeLt(order.completedAt, endExclusive)
			)
			.groupBy(completedDate)
			.fetch()
			.forEach(row -> dailyTransactions
				.computeIfAbsent(toLocalDate(row.get(completedDate)), ignored -> new DailyTransactionAccumulator())
				.addCompleted(valueOrZero(row.get(completedCount)), valueOrZero(row.get(completedAmount))));

		DateExpression<java.sql.Date> refundedDate = toDate(orderProduct.refundedAt);
		NumberExpression<Integer> refundedAmount = orderProduct.productAmount.sum();
		queryFactory
			.select(refundedDate, refundedAmount)
			.from(orderProduct)
			.where(
				dateTimeGoe(orderProduct.refundedAt, startInclusive),
				dateTimeLt(orderProduct.refundedAt, endExclusive)
			)
			.groupBy(refundedDate)
			.fetch()
			.forEach(row -> dailyTransactions
				.computeIfAbsent(toLocalDate(row.get(refundedDate)), ignored -> new DailyTransactionAccumulator())
				.subtractRefund(valueOrZero(row.get(refundedAmount))));

		return dailyTransactions.entrySet().stream()
			.sorted(Map.Entry.comparingByKey())
			.map(entry -> new DailyTransactionProjection(
				entry.getKey(),
				entry.getValue().transactionCount,
				entry.getValue().transactionAmount
			))
			.toList();
	}

	private OrderListProjection toOrderListProjection(List<Tuple> rows) {
		Tuple first = rows.getFirst();

		return new OrderListProjection(
			first.get(order.id),
			first.get(order.orderNumber),
			first.get(order.buyerId),
			valueOrZero(first.get(order.totalOrderAmount)),
			first.get(order.orderStatus),
			first.get(order.createdAt),
			rows.stream()
				.map(row -> new OrderListProjection.OrderProductSummary(
					row.get(orderProduct.sellerId),
					row.get(orderProduct.productTitle),
					valueOrZero(row.get(orderProduct.productAmount)),
					row.get(orderProduct.orderProductStatus)
				))
				.toList()
		);
	}

	private long sumCompletedOrderAmount(LocalDateTime startInclusive, LocalDateTime endExclusive) {
		Integer amount = queryFactory
			.select(order.totalOrderAmount.sum())
			.from(order)
			.where(
				dateTimeGoe(order.completedAt, startInclusive),
				dateTimeLt(order.completedAt, endExclusive)
			)
			.fetchOne();
		return valueOrZero(amount);
	}

	private long sumRefundedProductAmount(LocalDateTime startInclusive, LocalDateTime endExclusive) {
		Integer amount = queryFactory
			.select(orderProduct.productAmount.sum())
			.from(orderProduct)
			.where(
				dateTimeGoe(orderProduct.refundedAt, startInclusive),
				dateTimeLt(orderProduct.refundedAt, endExclusive)
			)
			.fetchOne();
		return valueOrZero(amount);
	}

	private BooleanExpression orderStatusEq(OrderStatus orderStatus) {
		return orderStatus == null ? null : order.orderStatus.eq(orderStatus);
	}

	private BooleanExpression dateTimeGoe(DateTimePath<LocalDateTime> path, LocalDateTime value) {
		return value == null ? null : path.goe(value);
	}

	private BooleanExpression dateTimeLt(DateTimePath<LocalDateTime> path, LocalDateTime value) {
		return value == null ? null : path.lt(value);
	}

	private DateExpression<java.sql.Date> toDate(DateTimePath<LocalDateTime> path) {
		return Expressions.dateTemplate(java.sql.Date.class, "cast({0} as date)", path);
	}

	private LocalDate toLocalDate(java.sql.Date date) {
		return date == null ? null : date.toLocalDate();
	}

	private long valueOrZero(Long value) {
		return value == null ? 0L : value;
	}

	private int valueOrZero(Integer value) {
		return value == null ? 0 : value;
	}

	private static class DailyTransactionAccumulator {
		private long transactionCount;
		private long transactionAmount;

		private void addCompleted(long count, long amount) {
			this.transactionCount += count;
			this.transactionAmount += amount;
		}

		private void subtractRefund(long amount) {
			this.transactionAmount -= amount;
		}
	}

}
