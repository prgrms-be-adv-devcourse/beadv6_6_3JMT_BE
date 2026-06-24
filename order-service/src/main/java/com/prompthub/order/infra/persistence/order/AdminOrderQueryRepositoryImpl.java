package com.prompthub.order.infra.persistence.order;

import com.prompthub.order.application.dto.AdminOrderListProjection;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.prompthub.order.domain.model.QOrder.order;
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

	private BooleanExpression orderStatusEq(OrderStatus orderStatus) {
		return orderStatus == null ? null : order.orderStatus.eq(orderStatus);
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
}
