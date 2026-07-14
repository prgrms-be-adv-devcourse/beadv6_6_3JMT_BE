package com.prompthub.order.infra.scheduling.refund;

import com.prompthub.order.application.client.PaymentRefundStatusClient;
import com.prompthub.order.application.dto.PaymentRefundStatusResult;
import com.prompthub.order.application.exception.PaymentRefundStatusQueryException;
import com.prompthub.order.application.port.RefundMetricsPort;
import com.prompthub.order.application.service.refund.RefundReconciliationResultService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Component
@Profile({"dev", "prod"})
@RequiredArgsConstructor
public class RefundReconciliationWorker {

	private final PaymentRefundStatusClient client;
	private final RefundReconciliationResultService resultService;
	private final RefundMetricsPort refundMetrics;

	public void reconcile(UUID refundRequestId, LocalDateTime checkedAt) {
		try {
			PaymentRefundStatusResult result = client.getRefundStatus(refundRequestId);
			refundMetrics.recordGrpcResult(result.status().name());
			resultService.apply(refundRequestId, result, checkedAt);
		} catch (PaymentRefundStatusQueryException exception) {
			refundMetrics.recordGrpcResult("QUERY_FAILED");
			log.warn("환불 상태 조회를 확정하지 못했습니다. refundRequestId={}", refundRequestId, exception);
			resultService.applyUnresolved(refundRequestId, checkedAt);
		}
	}
}
