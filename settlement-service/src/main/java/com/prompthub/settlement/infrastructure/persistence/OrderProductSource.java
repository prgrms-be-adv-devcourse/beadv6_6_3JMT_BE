package com.prompthub.settlement.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.Subselect;
import org.hibernate.annotations.Synchronize;

// TODO[EDA]: DB per service 전환 시 이 @Subselect(order_product 직접 읽기)는 제거한다.
//  PAID·REFUND 이벤트를 Kafka 로 받아 정산 소유 테이블(settlement_source_line)에 적재하고,
//  SettlementSourceRepository 어댑터를 그 로컬 테이블 조회로 교체한다. (포트 시그니처는 불변)
@Entity
@Immutable
@Subselect("""
        select op.order_product_id as order_product_id,
               op.seller_id as seller_id,
               op.product_amount_snapshot as product_amount_snapshot,
               op.order_product_status as order_product_status,
               op.created_at as created_at
        from order_product op
        """)
@Synchronize("order_product")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderProductSource {

    @Id
    @Column(name = "order_product_id")
    private UUID orderProductId;

    @Column(name = "seller_id")
    private UUID sellerId;

    @Column(name = "product_amount_snapshot")
    private int productAmountSnapshot;

    @Column(name = "order_product_status")
    private String orderProductStatus;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
