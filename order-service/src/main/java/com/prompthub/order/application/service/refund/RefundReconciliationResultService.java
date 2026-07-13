package com.prompthub.order.application.service.refund;

import com.prompthub.order.domain.enums.OrderRefundStatus;
import com.prompthub.order.domain.model.OrderRefund;
import com.prompthub.order.domain.repository.OrderRefundRepository;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RefundReconciliationResultService {

	private final OrderRefundRepository repository;
	private final RefundReconciliationPolicy policy;

	@Transactional
	public void applyUnresolved(UUID refundId, LocalDateTime checkedAt) {
		OrderRefund refund = repository.findByIdForUpdate(refundId)
			.orElseThrow(() -> new OrderException(ErrorCode.ORDER_REFUND_REQUEST_CONFLICT));
		if (refund.getStatus() != OrderRefundStatus.REQUESTED) {
			return;
		}
		RefundReconciliationPolicy.Decision decision = policy.decide(refund, checkedAt);
		if (decision.action() == RefundReconciliationPolicy.Action.TIMEOUT) {
			refund.timeout(checkedAt);
			return;
		}
		refund.recordReconciliationAttempt(decision.nextCheckAt());
	}
}
