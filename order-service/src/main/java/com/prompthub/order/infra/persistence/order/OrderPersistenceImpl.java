package com.prompthub.order.infra.persistence.order;

import com.prompthub.order.application.dto.OrderListProjection;
import com.prompthub.order.application.dto.OrderListProductProjection;
import com.prompthub.order.domain.enums.OrderStatus;

import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.support.PageableExecutionUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static com.prompthub.order.domain.model.QOrder.order;
import static com.prompthub.order.domain.model.QOrderProduct.orderProduct;

@RequiredArgsConstructor
public class OrderPersistenceImpl implements OrderPersistenceCustom {

    private final JPAQueryFactory queryFactory;

    @Override
    public Page<OrderListProjection> searchOrders(
        UUID buyerId, 
        OrderStatus status, 
        LocalDateTime from, 
        LocalDateTime to, 
        Pageable pageable
    ) {
        List<OrderListProjection> content = queryFactory
            .select(Projections.constructor(OrderListProjection.class,
                order.id,
                order.orderNumber,
                order.orderStatus,
                order.totalOrderAmount,
                order.completedAt,
                order.createdAt
            ))
            .from(order)
            .where(
                buyerIdEq(buyerId),
                statusEq(status),
                createdAtGoe(from),
                createdAtLoe(to)
            )
            .orderBy(order.createdAt.desc(), order.id.desc())
            .offset(pageable.getOffset())
            .limit(pageable.getPageSize())
            .fetch();

        JPAQuery<Long> countQuery = queryFactory
            .select(order.count())
            .from(order)
            .where(
                buyerIdEq(buyerId),
                statusEq(status),
                createdAtGoe(from),
                createdAtLoe(to)
            );

        return PageableExecutionUtils.getPage(content, pageable, countQuery::fetchOne);
    }

    @Override
    public List<OrderListProductProjection> findOrderProductsByOrderIds(List<UUID> orderIds) {
        if (orderIds.isEmpty()) {
            return List.of();
        }

        return queryFactory
            .select(Projections.constructor(OrderListProductProjection.class,
                order.id,
                orderProduct.id,
                orderProduct.productId,
                orderProduct.orderStatus,
                orderProduct.productAmount,
                orderProduct.downloaded,
                Expressions.nullExpression(String.class),
                orderProduct.productTitle,
                Expressions.nullExpression(String.class),
                Expressions.nullExpression(Double.class)
            ))
            .from(order)
            .join(order.orderProducts, orderProduct)
            .where(order.id.in(orderIds))
            .orderBy(orderProduct.id.asc())
            .fetch();
    }

    private BooleanExpression buyerIdEq(UUID buyerId) {
        return buyerId != null ? order.buyerId.eq(buyerId) : null;
    }

    private BooleanExpression statusEq(OrderStatus status) {
        return status != null ? order.orderStatus.eq(status) : null;
    }

    private BooleanExpression createdAtGoe(LocalDateTime from) {
        return from != null ? order.createdAt.goe(from) : null;
    }

    private BooleanExpression createdAtLoe(LocalDateTime to) {
        return to != null ? order.createdAt.loe(to) : null;
    }
}
