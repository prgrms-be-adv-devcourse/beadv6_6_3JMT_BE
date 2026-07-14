package com.prompthub.order.infra.persistence.refund;

import com.prompthub.order.domain.model.OrderRefund;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.LocalDateTime;

public interface OrderRefundPersistence extends JpaRepository<OrderRefund, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select refund from OrderRefund refund where refund.id = :refundRequestId")
    Optional<OrderRefund> findByIdForUpdate(
        @Param("refundRequestId") UUID refundRequestId
    );

    @Query(value = """
        select *
        from order_refund
        where status in ('REQUESTED', 'PROCESSING', 'UNKNOWN')
          and next_check_at <= :now
          and manual_review_required = false
        order by next_check_at, id
        limit :batchSize
        for update skip locked
        """, nativeQuery = true)
    List<OrderRefund> findDueRefunds(
        @Param("now") LocalDateTime now,
        @Param("batchSize") int batchSize
    );

    @Query("""
        select distinct refund
        from OrderRefund refund
        left join fetch refund.products
        where refund.orderId = :orderId
        order by refund.requestedAt desc, refund.id desc
        """)
    List<OrderRefund> findAllByOrderIdWithProducts(@Param("orderId") UUID orderId);
}
