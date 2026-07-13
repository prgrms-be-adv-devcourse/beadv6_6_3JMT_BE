package com.prompthub.order.infra.persistence.order;

import com.prompthub.order.domain.model.OrderProduct;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface OrderProductPersistence extends JpaRepository<OrderProduct, UUID> {

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
		update OrderProduct p
		set p.downloaded = true,
		    p.updatedAt = current_timestamp
		where p.id = :orderProductId
		  and p.orderStatus = com.prompthub.order.domain.enums.OrderProductStatus.PAID
		  and p.downloaded = false
		""")
	int tryMarkDownloaded(@Param("orderProductId") UUID orderProductId);
}
