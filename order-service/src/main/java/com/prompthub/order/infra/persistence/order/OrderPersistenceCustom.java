package com.prompthub.order.infra.persistence.order;

import com.prompthub.order.application.dto.OrderListProjection;
import com.prompthub.order.application.dto.OrderListProductProjection;
import com.prompthub.order.domain.enums.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface OrderPersistenceCustom {
    Page<OrderListProjection> searchOrders(
        UUID buyerId, 
        OrderStatus status, 
        LocalDateTime from, 
        LocalDateTime to, 
        Pageable pageable
    );

    List<OrderListProductProjection> findOrderProductsByOrderIds(List<UUID> orderIds);
}
