package com.prompthub.order.application.service.refund;

import com.prompthub.order.application.port.RefundMetricsPort;
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
	private final RefundMetricsPort refundMetrics;

	@Transactional
	public List<UUID> claimDueRequests(LocalDateTime now, int batchSize, Duration leaseDuration) {
		LocalDateTime leaseUntil = now.plus(leaseDuration);
		List<OrderRefund> refunds = repository.findDueRefunds(now, batchSize);
		refunds.forEach(refund -> {
			refundMetrics.recordReconciliationDelay(Duration.between(refund.getNextCheckAt(), now));
			refund.leaseUntil(leaseUntil);
		});
		return refunds.stream().map(OrderRefund::getId).toList();
	}
}
