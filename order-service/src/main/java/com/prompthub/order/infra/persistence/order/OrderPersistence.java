package com.prompthub.order.infra.persistence.order;

import com.prompthub.order.domain.model.Order;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderPersistence extends JpaRepository<Order, UUID>, OrderPersistenceCustom {
	@Query("""
    select distinct o
    from Order o
    left join fetch o.orderProducts
    where o.id = :orderId
""")
	Optional<Order> findByIdWithOrderProducts(@Param("orderId") UUID orderId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select o from Order o where o.id = :orderId")
	Optional<Order> findByIdForUpdate(@Param("orderId") UUID orderId);

	@Query("""
    select distinct o
    from Order o
    left join fetch o.orderProducts
    where o.orderNumber = :orderNumber
""")
	Optional<Order> findByOrderNumber(@Param("orderNumber") String orderNumber);

	@Query("""
    select case when count(op) > 0 then true else false end
    from Order o
    join o.orderProducts op
    where o.buyerId = :buyerId
      and op.productId = :productId
      and o.orderStatus in (
        com.prompthub.order.domain.enums.OrderStatus.COMPLETED,
        com.prompthub.order.domain.enums.OrderStatus.PARTIAL_REFUNDED,
        com.prompthub.order.domain.enums.OrderStatus.REFUND_REQUESTED
      )
      and op.orderStatus = com.prompthub.order.domain.enums.OrderProductStatus.PAID
""")
	boolean existsAccessiblePaidOrderProductByBuyerIdAndProductId(
		@Param("buyerId") UUID buyerId,
		@Param("productId") UUID productId
	);

	@Query("""
    select case when count(op) > 0 then true else false end
    from Order o
    join o.orderProducts op
    where o.buyerId = :buyerId
      and op.productId = :productId
      and op.downloaded = true
      and o.orderStatus in (
        com.prompthub.order.domain.enums.OrderStatus.COMPLETED,
        com.prompthub.order.domain.enums.OrderStatus.PARTIAL_REFUNDED,
        com.prompthub.order.domain.enums.OrderStatus.REFUND_REQUESTED
      )
      and op.orderStatus = com.prompthub.order.domain.enums.OrderProductStatus.PAID
""")
	boolean isAccessiblePaidProductDownloaded(
		@Param("buyerId") UUID buyerId,
		@Param("productId") UUID productId
	);

	@Query("""
    select case when count(op) > 0 then true else false end
    from Order o
    join o.orderProducts op
    where o.buyerId = :buyerId
      and op.productId = :productId
      and op.orderStatus in (
        com.prompthub.order.domain.enums.OrderProductStatus.PENDING,
        com.prompthub.order.domain.enums.OrderProductStatus.PAID,
        com.prompthub.order.domain.enums.OrderProductStatus.REFUND_REQUESTED
      )
""")
	boolean existsBlockingOrderProductByBuyerIdAndProductId(
		@Param("buyerId") UUID buyerId,
		@Param("productId") UUID productId
	);

	@Query("""
    select o.id
    from Order o
    where o.orderStatus = com.prompthub.order.domain.enums.OrderStatus.CREATED
      and o.createdAt <= :cutoff
    order by o.createdAt asc
""")
	List<UUID> findExpiredCreatedOrderIds(
		@Param("cutoff") LocalDateTime cutoff,
		Pageable pageable
	);

	@Query("""
    select distinct op.productId
    from Order o
    join o.orderProducts op
    where o.buyerId = :buyerId
      and o.orderStatus in (
        com.prompthub.order.domain.enums.OrderStatus.COMPLETED,
        com.prompthub.order.domain.enums.OrderStatus.PARTIAL_REFUNDED,
        com.prompthub.order.domain.enums.OrderStatus.REFUND_REQUESTED
      )
      and op.orderStatus = com.prompthub.order.domain.enums.OrderProductStatus.PAID
""")
	List<UUID> findAccessiblePaidProductIdsByBuyerId(@Param("buyerId") UUID buyerId);

}
