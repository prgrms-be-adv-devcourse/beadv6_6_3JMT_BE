package com.prompthub.order.application.service.refund;

import com.prompthub.order.application.port.RefundMetricsPort;
import com.prompthub.order.domain.model.OrderRefund;
import com.prompthub.order.domain.repository.OrderRefundRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

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
class RefundReconciliationClaimServiceTest {

	@Mock OrderRefundRepository repository;
	@Mock RefundMetricsPort refundMetrics;
	@InjectMocks RefundReconciliationClaimService service;

	@Test
	void claimDueRequests_leasesRowsAndReturnsImmutableIds() {
		LocalDateTime now = REQUESTED_AT.plusMinutes(2);
		OrderRefund refund = OrderRefund.request(ORDER_ID, PAYMENT_ID, BUYER_ID,
			List.of(paidProduct(ORDER_PRODUCT_ID_1, 10_000)), REQUESTED_AT);
		given(repository.findDueRefunds(now, 50)).willReturn(List.of(refund));

		List<java.util.UUID> ids = service.claimDueRequests(now, 50, Duration.ofMinutes(1));

		assertThat(ids).containsExactly(refund.getId());
		assertThat(refund.getNextCheckAt()).isEqualTo(now.plusMinutes(1));
		then(refundMetrics).should().recordReconciliationDelay(Duration.ZERO);
		assertThatThrownByMutation(ids);
	}

	private void assertThatThrownByMutation(List<java.util.UUID> ids) {
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> ids.add(java.util.UUID.randomUUID()))
			.isInstanceOf(UnsupportedOperationException.class);
	}
}
