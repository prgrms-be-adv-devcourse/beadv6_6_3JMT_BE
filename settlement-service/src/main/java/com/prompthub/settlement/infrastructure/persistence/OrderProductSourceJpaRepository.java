package com.prompthub.settlement.infrastructure.persistence;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// TODO[EDA]: 이벤트 적재 로컬 테이블(settlement_source_line)로 전환 시 아래 쿼리도 수정한다.
//  - order_product 직접 조회 → 로컬 테이블 조회
//  - '이미 정산됨' 제외: SettlementDetail 서브쿼리 → 로컬 테이블의 settled 상태로 대체
//  - REFUND 이벤트가 line_type 으로 들어오므로 status='PAID' 조건도 재검토
public interface OrderProductSourceJpaRepository extends JpaRepository<OrderProductSource, UUID> {

    @Query("""
            select distinct o.sellerId
            from OrderProductSource o
            where o.orderProductStatus = 'PAID'
              and o.createdAt >= :start
              and o.createdAt < :end
              and o.orderProductId not in (
                  select d.orderProductId from SettlementDetail d where d.orderProductId is not null
              )
            """)
    List<UUID> findSettleableSellerIds(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("""
            select o
            from OrderProductSource o
            where o.sellerId = :sellerId
              and o.orderProductStatus = 'PAID'
              and o.createdAt >= :start
              and o.createdAt < :end
              and o.orderProductId not in (
                  select d.orderProductId from SettlementDetail d where d.orderProductId is not null
              )
            """)
    List<OrderProductSource> findSettleableLines(@Param("sellerId") UUID sellerId,
                                                 @Param("start") LocalDateTime start,
                                                 @Param("end") LocalDateTime end);
}
