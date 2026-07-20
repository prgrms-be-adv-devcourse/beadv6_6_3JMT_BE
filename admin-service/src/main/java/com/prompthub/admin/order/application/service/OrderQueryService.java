package com.prompthub.admin.order.application.service;

import com.prompthub.admin.order.application.dto.DailyTransactionProjection;
import com.prompthub.admin.order.application.dto.OrderListProjection;
import com.prompthub.admin.order.domain.repository.OrderQueryRepository;
import com.prompthub.admin.order.presentation.dto.request.OrderSearchCondition;
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
public class OrderQueryService {

	private final OrderQueryRepository orderQueryRepository;

	public Page<OrderListProjection> searchOrders(OrderSearchCondition condition, PageRequest pageable) {
		return orderQueryRepository.searchOrders(condition, pageable);
	}

	public long sumMonthlyTransactionAmount(LocalDateTime startInclusive, LocalDateTime endExclusive) {
		return orderQueryRepository.sumMonthlyTransactionAmount(startInclusive, endExclusive);
	}

	public List<DailyTransactionProjection> findDailyTransactions(
		LocalDateTime startInclusive,
		LocalDateTime endExclusive
	) {
		return orderQueryRepository.findDailyTransactions(startInclusive, endExclusive);
	}
}
