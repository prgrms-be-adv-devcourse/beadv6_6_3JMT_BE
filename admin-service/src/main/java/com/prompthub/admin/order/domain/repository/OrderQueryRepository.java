package com.prompthub.admin.order.domain.repository;

import com.prompthub.admin.order.application.dto.DailyTransactionProjection;
import com.prompthub.admin.order.application.dto.OrderListProjection;
import com.prompthub.admin.order.presentation.dto.request.OrderSearchCondition;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;

public interface OrderQueryRepository {

	Page<OrderListProjection> searchOrders(OrderSearchCondition condition, Pageable pageable);

	long sumMonthlyTransactionAmount(LocalDateTime startInclusive, LocalDateTime endExclusive);

	List<DailyTransactionProjection> findDailyTransactions(LocalDateTime startInclusive, LocalDateTime endExclusive);
}
