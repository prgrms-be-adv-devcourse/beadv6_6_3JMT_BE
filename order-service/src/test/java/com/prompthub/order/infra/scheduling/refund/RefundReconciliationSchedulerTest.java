package com.prompthub.order.infra.scheduling.refund;

import com.prompthub.order.application.service.refund.RefundReconciliationClaimService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class RefundReconciliationSchedulerTest {

	@Mock RefundReconciliationClaimService claimService;
	@Mock RefundReconciliationWorker worker;

	@Test
	void scheduler_claimsShortLeaseThenRunsWorkersOutsideClaimCall() {
		Clock clock = Clock.fixed(Instant.parse("2026-07-14T01:02:00Z"), ZoneId.of("Asia/Seoul"));
		LocalDateTime now = LocalDateTime.of(2026, 7, 14, 10, 2);
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();
		RefundReconciliationProperties properties = new RefundReconciliationProperties(
			true, Duration.ofSeconds(30), 50, Duration.ofMinutes(1)
		);
		RefundReconciliationScheduler scheduler = new RefundReconciliationScheduler(
			claimService, worker, properties, clock
		);
		given(claimService.claimDueRequests(now, 50, Duration.ofMinutes(1))).willReturn(List.of(first, second));

		scheduler.reconcileDueRequests();

		then(worker).should().reconcile(first, now);
		then(worker).should().reconcile(second, now);
	}
}
