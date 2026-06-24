package com.prompthub.order.domain.repository;

import com.prompthub.order.application.dto.AdminOrderListProjection;
import com.prompthub.order.application.dto.AdminDailyTransactionProjection;
import com.prompthub.order.presentation.dto.request.AdminOrderSearchCondition;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;

public interface AdminOrderQueryRepository {

	Page<AdminOrderListProjection> searchAdminOrders(AdminOrderSearchCondition condition, Pageable pageable);

	long sumMonthlyTransactionAmount(LocalDateTime startInclusive, LocalDateTime endExclusive);

	List<AdminDailyTransactionProjection> findDailyTransactions(LocalDateTime startInclusive, LocalDateTime endExclusive);
}
