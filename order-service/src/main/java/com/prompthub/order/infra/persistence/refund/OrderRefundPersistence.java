package com.prompthub.order.infra.persistence.refund;

import com.prompthub.order.domain.enums.OrderRefundStatus;
import com.prompthub.order.domain.model.OrderRefund;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface OrderRefundPersistence
	extends JpaRepository<OrderRefund, UUID>, OrderRefundPersistenceCustom {

	@Query("""
		select r
		from OrderRefund r
		join fetch r.product
		where r.id = :refundId
		""")
	Optional<OrderRefund> findByIdWithProduct(@Param("refundId") UUID refundId);

	@Query("""
		select r
		from OrderRefund r
		join fetch r.product p
		where r.paymentId = :paymentId
		  and p.orderProductId = :orderProductId
		""")
	Optional<OrderRefund> findByPaymentIdAndOrderProductId(
		@Param("paymentId") UUID paymentId,
		@Param("orderProductId") UUID orderProductId
	);

	boolean existsByOrderIdAndStatus(UUID orderId, OrderRefundStatus status);
}
