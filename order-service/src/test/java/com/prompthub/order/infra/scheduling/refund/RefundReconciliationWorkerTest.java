package com.prompthub.order.infra.scheduling.refund;

import com.prompthub.order.application.client.PaymentRefundStatusClient;
import com.prompthub.order.application.dto.PaymentRefundStatusResult;
import com.prompthub.order.application.exception.PaymentRefundStatusQueryException;
import com.prompthub.order.application.port.RefundMetricsPort;
import com.prompthub.order.application.service.refund.RefundReconciliationResultService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class RefundReconciliationWorkerTest {

	@Mock PaymentRefundStatusClient client;
	@Mock RefundReconciliationResultService resultService;
	@Mock RefundMetricsPort refundMetrics;
	@InjectMocks RefundReconciliationWorker worker;

	@Test
	void reconcile_routesRemoteResult() {
		UUID id = UUID.randomUUID();
		LocalDateTime checkedAt = LocalDateTime.of(2026, 7, 14, 10, 2);
		PaymentRefundStatusResult result = PaymentRefundStatusResult.processing();
		given(client.getRefundStatus(id)).willReturn(result);

		worker.reconcile(id, checkedAt);

		then(resultService).should().apply(id, result, checkedAt);
		then(refundMetrics).should().recordGrpcResult("PROCESSING");
	}

	@Test
	void reconcile_queryFailure_routesUnresolved() {
		UUID id = UUID.randomUUID();
		LocalDateTime checkedAt = LocalDateTime.of(2026, 7, 14, 10, 2);
		given(client.getRefundStatus(id)).willThrow(new PaymentRefundStatusQueryException("failed", new RuntimeException()));

		worker.reconcile(id, checkedAt);

		then(resultService).should().applyUnresolved(id, checkedAt);
		then(refundMetrics).should().recordGrpcResult("QUERY_FAILED");
	}
}
