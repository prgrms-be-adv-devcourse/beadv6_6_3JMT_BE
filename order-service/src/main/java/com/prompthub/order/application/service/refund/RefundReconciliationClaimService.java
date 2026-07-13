package com.prompthub.order.application.service.refund;

import com.prompthub.order.domain.model.OrderRefund;
import com.prompthub.order.domain.repository.OrderRefundRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RefundReconciliationClaimService {

	private final OrderRefundRepository repository;

	@Transactional
	public List<UUID> claimDueRequests(LocalDateTime now, int batchSize, Duration leaseDuration) {
		LocalDateTime leaseUntil = now.plus(leaseDuration);
		List<OrderRefund> refunds = repository.findDueRefunds(now, batchSize);
		refunds.forEach(refund -> refund.claimUntil(leaseUntil));
		return refunds.stream().map(OrderRefund::getId).toList();
	}
}
