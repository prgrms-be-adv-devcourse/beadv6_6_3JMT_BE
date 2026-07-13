package com.prompthub.order.application.service.refund;

import com.prompthub.order.application.client.PaymentRefundStatusClient;
import com.prompthub.order.application.dto.PaymentRefundStatusResult;
import com.prompthub.order.application.service.event.common.ConsumedEventContext;
import com.prompthub.order.application.service.event.payment.PaymentRefundCompletedProcessor;
import com.prompthub.order.application.service.event.payment.PaymentRefundFailedProcessor;
import com.prompthub.order.domain.enums.OrderRefundStatus;
import com.prompthub.order.domain.model.OrderRefund;
import com.prompthub.order.domain.repository.OrderRefundRepository;
import com.prompthub.order.infra.messaging.kafka.event.PaymentRefundCompletedPayload;
import com.prompthub.order.infra.messaging.kafka.event.PaymentRefundFailedPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@Profile({"dev", "prod"})
@RequiredArgsConstructor
public class RefundReconciliationService {

	private final PaymentRefundStatusClient client;
	private final OrderRefundRepository repository;
	private final RefundReconciliationResultService resultService;
	private final PaymentRefundCompletedProcessor completedProcessor;
	private final PaymentRefundFailedProcessor failedProcessor;

	public void reconcile(UUID refundId, LocalDateTime checkedAt) {
		OrderRefund refund = repository.findById(refundId).orElse(null);
		if (refund == null || refund.getStatus() != OrderRefundStatus.REQUESTED) {
			return;
		}

		PaymentRefundStatusResult result;
		try {
			result = client.getRefundStatus(refundId);
		} catch (RuntimeException exception) {
			log.warn("환불 상태 gRPC 조회에 실패했습니다. refundId={}", refundId, exception);
			resultService.applyUnresolved(refundId, checkedAt);
			return;
		}

		switch (result.status()) {
			case PROCESSING, NOT_FOUND -> resultService.applyUnresolved(refundId, checkedAt);
			case COMPLETED -> completedProcessor.process(
				new ConsumedEventContext(
					eventId(refundId, "COMPLETED"),
					"PAYMENT_REFUND_COMPLETED",
					checkedAt
				),
				new PaymentRefundCompletedPayload(
					refundId, refund.getPaymentId(), refund.getOrderId(), refund.getTotalRefundAmount(),
					result.refundedAt() == null ? checkedAt : result.refundedAt()
				)
			);
			case FAILED -> failedProcessor.process(
				new ConsumedEventContext(
					eventId(refundId, "FAILED"),
					"PAYMENT_REFUND_FAILED",
					checkedAt
				),
				new PaymentRefundFailedPayload(
					refundId, refund.getPaymentId(), refund.getOrderId(), refund.getTotalRefundAmount(),
					result.failureCode(), result.failureReason(), checkedAt
				)
			);
		}
	}

	private UUID eventId(UUID refundId, String result) {
		return UUID.nameUUIDFromBytes(
			("refund-reconciliation:" + refundId + ":" + result).getBytes(StandardCharsets.UTF_8)
		);
	}
}
