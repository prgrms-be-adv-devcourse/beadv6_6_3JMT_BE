package com.prompthub.order.infra.persistence.order;

import com.prompthub.order.domain.model.OrderProduct;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface OrderProductPersistence extends JpaRepository<OrderProduct, UUID> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
        select op
        from OrderProduct op
        where op.order.id = :orderId
        order by op.id
    """)
	List<OrderProduct> findAllByOrderIdForUpdate(@Param("orderId") UUID orderId);
}
