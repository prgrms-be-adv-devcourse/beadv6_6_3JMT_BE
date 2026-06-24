package com.prompthub.order.application.service;

import com.prompthub.order.application.dto.AdminDailyTransactionProjection;
import com.prompthub.order.application.dto.AdminOrderListProjection;
import com.prompthub.order.domain.repository.AdminOrderQueryRepository;
import com.prompthub.order.presentation.dto.request.AdminOrderSearchCondition;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AdminOrderQueryService {

	private final AdminOrderQueryRepository adminOrderQueryRepository;

	public Page<AdminOrderListProjection> searchAdminOrders(AdminOrderSearchCondition condition, PageRequest pageable) {
		return adminOrderQueryRepository.searchAdminOrders(condition, pageable);
	}

	public long sumMonthlyTransactionAmount(LocalDateTime startInclusive, LocalDateTime endExclusive) {
		return adminOrderQueryRepository.sumMonthlyTransactionAmount(startInclusive, endExclusive);
	}

	public List<AdminDailyTransactionProjection> findDailyTransactions(
		LocalDateTime startInclusive,
		LocalDateTime endExclusive
	) {
		return adminOrderQueryRepository.findDailyTransactions(startInclusive, endExclusive);
	}
}
