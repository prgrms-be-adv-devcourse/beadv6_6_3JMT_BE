package com.prompthub.order.application.usecase;

import com.prompthub.order.presentation.dto.request.AdminOrderSearchCondition;
import com.prompthub.order.presentation.dto.response.AdminOrderListResponse;
import org.springframework.data.domain.Page;

public interface AdminOrderUseCase {

	Page<AdminOrderListResponse> getAdminOrders(AdminOrderSearchCondition condition);
}
