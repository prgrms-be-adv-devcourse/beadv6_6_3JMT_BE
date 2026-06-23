package com.prompthub.order.infra.persistence;

import com.prompthub.order.domain.model.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

	@Query("""
    select case when count(op) > 0 then true else false end
    from Order o
    join o.orderProducts op
    where o.buyerId = :buyerId
      and op.productId = :productId
      and o.orderStatus = com.prompthub.order.domain.enums.OrderStatus.PAID
      and op.orderStatus = com.prompthub.order.domain.enums.OrderStatus.PAID
""")
	boolean existsPaidOrderProductByBuyerIdAndProductId(
		@Param("buyerId") UUID buyerId,
		@Param("productId") UUID productId
	);

}
