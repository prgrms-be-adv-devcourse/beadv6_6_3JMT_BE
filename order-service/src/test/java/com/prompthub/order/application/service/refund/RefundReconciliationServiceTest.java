package com.prompthub.order.application.service.refund;

import com.prompthub.order.application.client.PaymentRefundStatusClient;
import com.prompthub.order.application.dto.PaymentRefundStatusResult;
import com.prompthub.order.application.service.event.payment.PaymentRefundCompletedProcessor;
import com.prompthub.order.application.service.event.payment.PaymentRefundFailedProcessor;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderRefund;
import com.prompthub.order.domain.repository.OrderRefundRepository;
import com.prompthub.order.infra.messaging.kafka.event.PaymentRefundCompletedPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static com.prompthub.order.fixture.OrderFixture.PAYMENT_ID;
import static com.prompthub.order.fixture.OrderFixture.TOTAL_AMOUNT;
import static com.prompthub.order.fixture.OrderFixture.createPaidOrderWithProducts;
import static com.prompthub.order.fixture.OrderRefundFixture.createRequestedRefund;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class RefundReconciliationServiceTest {

	@Mock private PaymentRefundStatusClient client;
	@Mock private OrderRefundRepository repository;
	@Mock private RefundReconciliationResultService resultService;
	@Mock private PaymentRefundCompletedProcessor completedProcessor;
	@Mock private PaymentRefundFailedProcessor failedProcessor;
	@InjectMocks private RefundReconciliationService service;

	@Test
	void reconcile_completed_usesRequestLevelProcessor() {
		LocalDateTime checkedAt = LocalDateTime.of(2026, 7, 13, 12, 2);
		Order order = createPaidOrderWithProducts();
		OrderRefund refund = createRequestedRefund(order, UUID.randomUUID(), checkedAt.minusMinutes(2));
		order.getOrderProducts().forEach(refund::addProduct);
		given(repository.findById(refund.getId())).willReturn(Optional.of(refund));
		given(client.getRefundStatus(refund.getId())).willReturn(PaymentRefundStatusResult.completed(checkedAt));

		service.reconcile(refund.getId(), checkedAt);

		ArgumentCaptor<PaymentRefundCompletedPayload> captor = ArgumentCaptor.forClass(PaymentRefundCompletedPayload.class);
		then(completedProcessor).should().process(
			argThat(context -> context.eventType().equals("PAYMENT_REFUND_COMPLETED")
				&& context.occurredAt().equals(checkedAt)),
			captor.capture()
		);
		assertThat(captor.getValue().totalRefundAmount()).isEqualTo(TOTAL_AMOUNT);
	}
}
