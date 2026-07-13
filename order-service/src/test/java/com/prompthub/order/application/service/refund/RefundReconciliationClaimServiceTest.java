package com.prompthub.order.application.service.refund;

import com.prompthub.order.domain.model.Order;
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
import java.util.UUID;

import static com.prompthub.order.fixture.OrderFixture.createPaidOrderWithProducts;
import static com.prompthub.order.fixture.OrderRefundFixture.createRequestedRefund;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class RefundReconciliationClaimServiceTest {

	@Mock private OrderRefundRepository repository;
	@InjectMocks private RefundReconciliationClaimService service;

	@Test
	void claimDueRequests_setsLeaseAndReturnsIds() {
		LocalDateTime now = LocalDateTime.of(2026, 7, 13, 12, 2);
		OrderRefund refund = refund(now.minusMinutes(2));
		given(repository.findDueRefunds(now, 10)).willReturn(List.of(refund));

		assertThat(service.claimDueRequests(now, 10, Duration.ofSeconds(30))).containsExactly(refund.getId());
		assertThat(refund.getNextCheckAt()).isEqualTo(now.plusSeconds(30));
	}

	private OrderRefund refund(LocalDateTime requestedAt) {
		Order order = createPaidOrderWithProducts();
		return createRequestedRefund(order, UUID.randomUUID(), requestedAt);
	}
}
