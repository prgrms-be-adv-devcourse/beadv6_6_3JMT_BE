package com.prompthub.order.infra.persistence.order;

import com.prompthub.order.application.dto.OrderPaymentListProjection;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.support.PageableExecutionUtils;

import java.util.List;
import java.util.UUID;

import static com.prompthub.order.domain.model.QOrder.order;
import static com.prompthub.order.domain.model.QOrderPayment.orderPayment;
import static com.prompthub.order.domain.model.QOrderProduct.orderProduct;

@RequiredArgsConstructor
public class OrderPaymentPersistenceImpl implements OrderPaymentPersistenceCustom {

	private final JPAQueryFactory queryFactory;

	@Override
	public Page<OrderPaymentListProjection> searchOrderPayments(UUID buyerId, Pageable pageable) {
		List<OrderPaymentListProjection> content = queryFactory
			.select(Projections.constructor(OrderPaymentListProjection.class,
				order.id,
				orderProduct.id,
				orderPayment.paymentId,
				order.orderStatus,
				orderProduct.orderStatus,
				orderProduct.productType,
				orderProduct.productTitle,
				orderProduct.productAmount,
				order.paidAt,
				orderPayment.approvedAt,
				orderProduct.download
			))
			.from(orderPayment)
			.join(order).on(order.id.eq(orderPayment.orderId))
			.join(order.orderProducts, orderProduct)
			.where(orderPayment.buyerId.eq(buyerId))
			.orderBy(orderPayment.approvedAt.desc(), orderProduct.id.asc())
			.offset(pageable.getOffset())
			.limit(pageable.getPageSize())
			.fetch();

		JPAQuery<Long> countQuery = queryFactory
			.select(orderProduct.count())
			.from(orderPayment)
			.join(order).on(order.id.eq(orderPayment.orderId))
			.join(order.orderProducts, orderProduct)
			.where(orderPayment.buyerId.eq(buyerId));

		return PageableExecutionUtils.getPage(content, pageable, countQuery::fetchOne);
	}
}
