package com.prompthub.order.application.service.refund;

import com.prompthub.order.domain.enums.OrderProductStatus;
import com.prompthub.order.domain.enums.OrderRefundStatus;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderRefund;
import com.prompthub.order.domain.repository.OrderRefundRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static com.prompthub.order.fixture.OrderFixture.createPaidOrderWithProducts;
import static com.prompthub.order.fixture.OrderRefundFixture.createRequestedRefundWithAllProducts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class RefundReconciliationResultServiceTest {

	@Mock private OrderRefundRepository repository;
	private RefundReconciliationResultService service;

	@BeforeEach
	void setUp() {
		service = new RefundReconciliationResultService(repository, new RefundReconciliationPolicy());
	}

	@Test
	void applyUnresolved_fourthCheck_marksTimeout() {
		LocalDateTime requestedAt = LocalDateTime.of(2026, 7, 13, 12, 0);
		Order order = createPaidOrderWithProducts();
		OrderRefund refund = createRequestedRefundWithAllProducts(order, UUID.randomUUID(), requestedAt);
		refund.recordReconciliationAttempt(requestedAt.plusMinutes(3));
		refund.recordReconciliationAttempt(requestedAt.plusMinutes(8));
		refund.recordReconciliationAttempt(requestedAt.plusMinutes(18));
		given(repository.findByIdForUpdate(refund.getId())).willReturn(Optional.of(refund));

		service.applyUnresolved(refund.getId(), requestedAt.plusMinutes(18));

		assertThat(refund.getStatus()).isEqualTo(OrderRefundStatus.TIMEOUT);
		assertThat(refund.isManualReviewRequired()).isTrue();
		assertThat(order.getOrderProducts()).extracting(item -> item.getOrderProductStatus())
			.containsOnly(OrderProductStatus.REFUND_TIMEOUT);
	}
}
