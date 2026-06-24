package com.prompthub.order.application.usecase;

import com.prompthub.order.presentation.dto.request.AdminOrderSearchCondition;
import com.prompthub.order.presentation.dto.response.AdminMonthlyTradeAmountResponse;
import com.prompthub.order.presentation.dto.response.AdminOrderListResponse;
import com.prompthub.order.presentation.dto.response.AdminWeeklyTransactionResponse;
import org.springframework.data.domain.Page;

public interface AdminOrderUseCase {

	Page<AdminOrderListResponse> getAdminOrders(AdminOrderSearchCondition condition);

	AdminMonthlyTradeAmountResponse getMonthlyTransactionAmount();

	AdminWeeklyTransactionResponse getWeeklyTransactions();
}
