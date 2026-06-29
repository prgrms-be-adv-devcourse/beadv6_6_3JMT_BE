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
		List<com.querydsl.core.Tuple> tuples = queryFactory
			.select(orderPayment, order)
			.from(orderPayment)
			.join(order).on(order.id.eq(orderPayment.orderId))
			.where(orderPayment.buyerId.eq(buyerId))
			.orderBy(orderPayment.approvedAt.desc())
			.offset(pageable.getOffset())
			.limit(pageable.getPageSize())
			.fetch();

		if (tuples.isEmpty()) {
			return Page.empty(pageable);
		}

		List<UUID> orderIds = tuples.stream()
			.map(t -> t.get(order).getId())
			.toList();

		queryFactory.selectFrom(order).distinct()
			.leftJoin(order.orderProducts, orderProduct).fetchJoin()
			.where(order.id.in(orderIds))
			.fetch();

		List<OrderPaymentListProjection> content = tuples.stream().map(t -> {
			com.prompthub.order.domain.model.OrderPayment payment = t.get(orderPayment);
			com.prompthub.order.domain.model.Order o = t.get(order);

			List<com.prompthub.order.domain.model.OrderProduct> products = o.getOrderProducts();
			
			String title = products.isEmpty() ? "" : products.get(0).getProductTitle();
			if (products.size() > 1) {
				title += " 외 " + (products.size() - 1) + "건";
			}

			boolean isRefundable = o.isPaid() && products.stream().noneMatch(com.prompthub.order.domain.model.OrderProduct::isDownload);
			String productType = products.isEmpty() ? "" : products.get(0).getProductType();

			return new OrderPaymentListProjection(
				o.getId(),
				payment.getPaymentId(),
				o.getOrderStatus(),
				isRefundable,
				productType,
				title,
				o.getTotalOrderAmount(),
				o.getPaidAt(),
				payment.getApprovedAt()
			);
		}).toList();

		JPAQuery<Long> countQuery = queryFactory
			.select(orderPayment.count())
			.from(orderPayment)
			.where(orderPayment.buyerId.eq(buyerId));

		return PageableExecutionUtils.getPage(content, pageable, countQuery::fetchOne);
	}
}
