package com.prompthub.order.domain.repository;

import com.prompthub.order.application.dto.AdminOrderListProjection;
import com.prompthub.order.presentation.dto.request.AdminOrderSearchCondition;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface AdminOrderQueryRepository {

	Page<AdminOrderListProjection> searchAdminOrders(AdminOrderSearchCondition condition, Pageable pageable);
}
