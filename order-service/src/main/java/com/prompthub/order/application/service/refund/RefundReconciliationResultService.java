package com.prompthub.order.application.service.refund;

import com.prompthub.order.application.dto.PaymentRefundStatusResult;
import com.prompthub.order.application.dto.RefundCompletionCommand;
import com.prompthub.order.application.dto.RefundFailureCommand;
import com.prompthub.order.application.port.RefundMetricsPort;
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
	private final OrderRefundCompletionService completionService;
	private final OrderRefundFailureService failureService;
	private final RefundMetricsPort refundMetrics;

	@Transactional
	public void apply(UUID refundRequestId, PaymentRefundStatusResult result, LocalDateTime checkedAt) {
		OrderRefund refund = loadForUpdate(refundRequestId);
		if (isTerminal(refund)) {
			return;
		}

		switch (result.status()) {
			case COMPLETED -> completionService.complete(new RefundCompletionCommand(
				refund.getId(), refund.getPaymentId(), refund.getOrderId(),
				refund.getTotalRefundAmount(), result.refundedAt()
			));
			case FAILED -> failureService.fail(new RefundFailureCommand(
				refund.getId(), refund.getPaymentId(), refund.getOrderId(),
				refund.getTotalRefundAmount(), result.failureCode(), result.failureReason(),
				false, checkedAt
			));
			case PROCESSING, NOT_FOUND -> applyUnresolved(refund, result.status(), checkedAt);
		}
	}

	@Transactional
	public void applyUnresolved(UUID refundRequestId, LocalDateTime checkedAt) {
		OrderRefund refund = loadForUpdate(refundRequestId);
		if (!isTerminal(refund)) {
			applyUnresolved(refund, null, checkedAt);
		}
	}

	private void applyUnresolved(
		OrderRefund refund,
		PaymentRefundStatusResult.Status remoteStatus,
		LocalDateTime checkedAt
	) {
		RefundReconciliationPolicy.Decision decision = policy.decide(refund, checkedAt);
		if (remoteStatus == PaymentRefundStatusResult.Status.PROCESSING
			&& decision.action() != RefundReconciliationPolicy.Action.MANUAL_REVIEW) {
			refund.markProcessing(decision.nextCheckAt());
			refund.scheduleNext(decision.attempt(), decision.nextCheckAt());
			return;
		}

		switch (decision.action()) {
			case RESCHEDULE -> refund.scheduleNext(decision.attempt(), decision.nextCheckAt());
			case MARK_UNKNOWN -> {
				refund.markUnknown(decision.nextCheckAt());
				refund.scheduleNext(decision.attempt(), decision.nextCheckAt());
				refundMetrics.recordUnknown();
			}
			case MANUAL_REVIEW -> {
				refund.requireManualReview();
				refundMetrics.recordManualReview();
			}
		}
	}

	private OrderRefund loadForUpdate(UUID refundRequestId) {
		return repository.findByIdForUpdate(refundRequestId)
			.orElseThrow(() -> new OrderException(ErrorCode.ORDER_NOT_FOUND));
	}

	private boolean isTerminal(OrderRefund refund) {
		return refund.getStatus() == OrderRefundStatus.COMPLETED
			|| refund.getStatus() == OrderRefundStatus.FAILED;
	}
}
