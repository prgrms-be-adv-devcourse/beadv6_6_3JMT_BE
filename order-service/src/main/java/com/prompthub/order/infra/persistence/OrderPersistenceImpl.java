package com.prompthub.order.infra.persistence;

import com.prompthub.order.application.dto.OrderListProjection;
import com.prompthub.order.domain.enums.OrderStatus;

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
    public Page<OrderListProjection> searchOrderProducts(
        UUID buyerId, 
        OrderStatus status, 
        LocalDateTime from, 
        LocalDateTime to, 
        Pageable pageable
    ) {
        List<OrderListProjection> content = queryFactory
            .select(Projections.constructor(OrderListProjection.class,
                order.id,
                orderProduct.id,
                order.orderStatus,
                orderProduct.orderStatus,
                orderProduct.download,
                orderProduct.productType,
                orderProduct.productTitle,
                // orderProduct.productThumbnailSnapshot,
                order.paidAt,
                order.createdAt
            ))
            .from(order)
            .join(order.orderProducts, orderProduct)
            .where(
                buyerIdEq(buyerId),
                statusEq(status),
                createdAtGoe(from),
                createdAtLoe(to)
            )
            .offset(pageable.getOffset())
            .limit(pageable.getPageSize())
            // Add order by if needed, or get from pageable
            .fetch();

        JPAQuery<Long> countQuery = queryFactory
            .select(orderProduct.count())
            .from(order)
            .join(order.orderProducts, orderProduct)
            .where(
                buyerIdEq(buyerId),
                statusEq(status),
                createdAtGoe(from),
                createdAtLoe(to)
            );

        return PageableExecutionUtils.getPage(content, pageable, countQuery::fetchOne);
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
