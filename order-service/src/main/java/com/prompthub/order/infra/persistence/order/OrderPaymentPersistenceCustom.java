package com.prompthub.order.infra.persistence.order;

import com.prompthub.order.application.dto.OrderPaymentListProjection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface OrderPaymentPersistenceCustom {

	Page<OrderPaymentListProjection> searchOrderPayments(UUID buyerId, Pageable pageable);
}
