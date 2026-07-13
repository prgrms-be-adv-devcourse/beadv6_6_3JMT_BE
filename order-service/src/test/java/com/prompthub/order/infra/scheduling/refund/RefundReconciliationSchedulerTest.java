package com.prompthub.order.infra.scheduling.refund;

import com.prompthub.order.application.service.refund.RefundReconciliationClaimService;
import com.prompthub.order.application.service.refund.RefundReconciliationService;
import org.junit.jupiter.api.DisplayName;
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

	@Mock private RefundReconciliationClaimService claimService;
	@Mock private RefundReconciliationService reconciliationService;

	@Test
	@DisplayName("스케줄 실행은 due 요청을 lease로 선점한 뒤 트랜잭션 밖에서 각각 조회한다")
	void reconcileDueRequests_claimThenReconcileEach() {
		Clock clock = Clock.fixed(Instant.parse("2026-07-11T03:02:00Z"), ZoneId.of("Asia/Seoul"));
		LocalDateTime now = LocalDateTime.of(2026, 7, 11, 12, 2);
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();
		RefundReconciliationProperties properties = new RefundReconciliationProperties(true, 5_000, 100, 30_000);
		RefundReconciliationScheduler scheduler = new RefundReconciliationScheduler(
			claimService, reconciliationService, properties, clock
		);
		given(claimService.claimDueRequests(now, 100, Duration.ofSeconds(30)))
			.willReturn(List.of(first, second));

		scheduler.reconcileDueRequests();

		then(reconciliationService).should().reconcile(first, now);
		then(reconciliationService).should().reconcile(second, now);
	}
}
