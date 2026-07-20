package com.prompthub.admin.order.application.usecase;

import com.prompthub.admin.order.presentation.dto.request.OrderSearchCondition;
import com.prompthub.admin.order.presentation.dto.response.MonthlyTradeAmountResponse;
import com.prompthub.admin.order.presentation.dto.response.OrderListResponse;
import com.prompthub.admin.order.presentation.dto.response.WeeklyTransactionResponse;
import org.springframework.data.domain.Page;

public interface OrderUseCase {

	Page<OrderListResponse> getOrders(OrderSearchCondition condition);

	MonthlyTradeAmountResponse getMonthlyTransactionAmount();

	WeeklyTransactionResponse getWeeklyTransactions();
}
