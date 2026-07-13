package com.prompthub.order.infra.persistence.refund;

import com.prompthub.order.domain.model.OrderRefund;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRefundPersistence extends JpaRepository<OrderRefund, UUID> {

	@Query("""
		select distinct r
		from OrderRefund r
		left join fetch r.refundProducts rp
		left join fetch rp.orderProduct
		where r.id = :refundId
		""")
	Optional<OrderRefund> findByIdWithProducts(@Param("refundId") UUID refundId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select r from OrderRefund r where r.id = :refundId")
	Optional<OrderRefund> findByIdForUpdate(@Param("refundId") UUID refundId);

	@Query(value = """
		select *
		from order_refund
		where status = 'REQUESTED'
		  and next_check_at <= :now
		  and manual_review_required = false
		order by next_check_at asc
		limit :batchSize
		for update skip locked
		""", nativeQuery = true)
	List<OrderRefund> findDueRefunds(
		@Param("now") LocalDateTime now,
		@Param("batchSize") int batchSize
	);
}
