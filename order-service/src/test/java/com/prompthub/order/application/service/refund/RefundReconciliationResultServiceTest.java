package com.prompthub.order.application.service.refund;

import com.prompthub.order.application.dto.PaymentRefundStatusResult;
import com.prompthub.order.application.dto.RefundCompletionCommand;
import com.prompthub.order.application.dto.RefundFailureCommand;
import com.prompthub.order.application.port.RefundMetricsPort;
import com.prompthub.order.domain.enums.OrderRefundStatus;
import com.prompthub.order.domain.model.OrderRefund;
import com.prompthub.order.domain.repository.OrderRefundRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static com.prompthub.order.fixture.OrderFixture.BUYER_ID;
import static com.prompthub.order.fixture.OrderFixture.ORDER_ID;
import static com.prompthub.order.fixture.OrderFixture.PAYMENT_ID;
import static com.prompthub.order.fixture.OrderRefundFixture.ORDER_PRODUCT_ID_1;
import static com.prompthub.order.fixture.OrderRefundFixture.REQUESTED_AT;
import static com.prompthub.order.fixture.OrderRefundFixture.paidProduct;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class RefundReconciliationResultServiceTest {

	@Mock OrderRefundRepository repository;
	@Mock OrderRefundCompletionService completionService;
	@Mock OrderRefundFailureService failureService;
	@Mock RefundMetricsPort refundMetrics;
	RefundReconciliationResultService service;
	OrderRefund refund;

	@BeforeEach
	void setUp() {
		service = new RefundReconciliationResultService(
			repository, new RefundReconciliationPolicy(), completionService, failureService, refundMetrics
		);
		refund = OrderRefund.request(ORDER_ID, PAYMENT_ID, BUYER_ID,
			List.of(paidProduct(ORDER_PRODUCT_ID_1, 10_000)), REQUESTED_AT);
		given(repository.findByIdForUpdate(refund.getId())).willReturn(Optional.of(refund));
	}

	@Test
	void processing_marksProcessingAndSchedulesNextAttempt() {
		service.apply(refund.getId(), PaymentRefundStatusResult.processing(), REQUESTED_AT.plusMinutes(2));

		assertThat(refund.getStatus()).isEqualTo(OrderRefundStatus.PROCESSING);
		assertThat(refund.getReconciliationAttempt()).isEqualTo(1);
		assertThat(refund.getNextCheckAt()).isEqualTo(REQUESTED_AT.plusMinutes(5));
	}

	@Test
	void processingAtUnknownBoundary_keepsProcessingAndSchedulesLongRetry() {
		refund.scheduleNext(3, REQUESTED_AT.plusMinutes(20));
		LocalDateTime checkedAt = REQUESTED_AT.plusMinutes(20);

		service.apply(refund.getId(), PaymentRefundStatusResult.processing(), checkedAt);

		assertThat(refund.getStatus()).isEqualTo(OrderRefundStatus.PROCESSING);
		assertThat(refund.getReconciliationAttempt()).isEqualTo(4);
		assertThat(refund.getNextCheckAt()).isEqualTo(checkedAt.plusMinutes(30));
	}

	@Test
	void fourthUnresolved_marksUnknownAndSchedulesLongRetry() {
		refund.scheduleNext(3, REQUESTED_AT.plusMinutes(20));
		LocalDateTime checkedAt = REQUESTED_AT.plusMinutes(20);

		service.applyUnresolved(refund.getId(), checkedAt);

		assertThat(refund.getStatus()).isEqualTo(OrderRefundStatus.UNKNOWN);
		assertThat(refund.getReconciliationAttempt()).isEqualTo(4);
		assertThat(refund.getNextCheckAt()).isEqualTo(checkedAt.plusMinutes(30));
		then(refundMetrics).should().recordUnknown();
	}

	@Test
	void exhaustedUnresolved_requiresManualReview() {
		refund.scheduleNext(6, REQUESTED_AT.plusHours(3));

		service.applyUnresolved(refund.getId(), REQUESTED_AT.plusHours(3));

		assertThat(refund.isManualReviewRequired()).isTrue();
		assertThat(refund.getNextCheckAt()).isNull();
		then(refundMetrics).should().recordManualReview();
	}

	@Test
	void completed_routesAuthoritativeLocalIdentifiers() {
		LocalDateTime refundedAt = REQUESTED_AT.plusMinutes(3);

		service.apply(refund.getId(), PaymentRefundStatusResult.completed(refundedAt), refundedAt);

		ArgumentCaptor<RefundCompletionCommand> captor = ArgumentCaptor.forClass(RefundCompletionCommand.class);
		then(completionService).should().complete(captor.capture());
		assertThat(captor.getValue().refundRequestId()).isEqualTo(refund.getId());
		assertThat(captor.getValue().paymentId()).isEqualTo(PAYMENT_ID);
		assertThat(captor.getValue().totalRefundAmount()).isEqualTo(10_000);
	}

	@Test
	void failed_routesNonRetryableCommandBecauseMergedProtoHasNoRetryableField() {
		LocalDateTime checkedAt = REQUESTED_AT.plusMinutes(3);

		service.apply(refund.getId(), PaymentRefundStatusResult.failed("PG_REJECTED", "거절"), checkedAt);

		ArgumentCaptor<RefundFailureCommand> captor = ArgumentCaptor.forClass(RefundFailureCommand.class);
		then(failureService).should().fail(captor.capture());
		assertThat(captor.getValue().retryable()).isFalse();
		assertThat(captor.getValue().failedAt()).isEqualTo(checkedAt);
	}
}
