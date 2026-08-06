package com.prompthub.order.infra.persistence.order;

import com.prompthub.order.application.dto.SettleableLineResult;
import com.prompthub.order.domain.enums.SettlementLineType;
import com.prompthub.order.domain.repository.SettlementOrderQueryRepository;
import com.querydsl.core.Tuple;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static com.prompthub.order.domain.model.QOrder.order;
import static com.prompthub.order.domain.model.QOrderProduct.orderProduct;

@Repository
@RequiredArgsConstructor
public class SettlementOrderQueryRepositoryImpl implements SettlementOrderQueryRepository {

    private static final Comparator<SettleableLineResult> SETTLEMENT_LINE_ORDER = Comparator
        .comparing(SettleableLineResult::occurredAt)
        .thenComparing(SettleableLineResult::orderProductId)
        .thenComparing(SettleableLineResult::lineType);

    private final JPAQueryFactory queryFactory;

    @Override
    public List<SettleableLineResult> findSettleableLines(
        LocalDateTime startInclusive,
        LocalDateTime endExclusive
    ) {
        List<SettleableLineResult> lines = new ArrayList<>();
        lines.addAll(findPaidLines(startInclusive, endExclusive));
        lines.addAll(findRefundLines(startInclusive, endExclusive));
        lines.sort(SETTLEMENT_LINE_ORDER);
        return lines;
    }

    private List<SettleableLineResult> findPaidLines(
        LocalDateTime startInclusive,
        LocalDateTime endExclusive
    ) {
        return queryFactory
            .select(
                order.id,
                orderProduct.id,
                orderProduct.sellerId,
                orderProduct.productAmount,
                order.completedAt
            )
            .from(orderProduct)
            .join(orderProduct.order, order)
            .where(
                order.completedAt.goe(startInclusive),
                order.completedAt.lt(endExclusive)
            )
            .fetch()
            .stream()
            .map(row -> toResult(row, SettlementLineType.PAID, row.get(order.completedAt)))
            .toList();
    }

    private List<SettleableLineResult> findRefundLines(
        LocalDateTime startInclusive,
        LocalDateTime endExclusive
    ) {
        return queryFactory
            .select(
                order.id,
                orderProduct.id,
                orderProduct.sellerId,
                orderProduct.productAmount,
                orderProduct.refundedAt
            )
            .from(orderProduct)
            .join(orderProduct.order, order)
            .where(
                orderProduct.refundedAt.goe(startInclusive),
                orderProduct.refundedAt.lt(endExclusive)
            )
            .fetch()
            .stream()
            .map(row -> toResult(row, SettlementLineType.REFUND, row.get(orderProduct.refundedAt)))
            .toList();
    }

    private SettleableLineResult toResult(
        Tuple row,
        SettlementLineType lineType,
        LocalDateTime occurredAt
    ) {
        return new SettleableLineResult(
            lineType,
            row.get(order.id),
            row.get(orderProduct.id),
            row.get(orderProduct.sellerId),
            row.get(orderProduct.productAmount),
            occurredAt
        );
    }
}
