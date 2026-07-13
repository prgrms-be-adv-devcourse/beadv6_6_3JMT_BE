package com.prompthub.order.infra.scheduling.refund;

import com.prompthub.order.application.service.refund.RefundReconciliationClaimService;
import com.prompthub.order.application.service.refund.RefundReconciliationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Component
@Profile({"dev", "prod"})
@RequiredArgsConstructor
@ConditionalOnProperty(
	prefix = "prompthub.refund-reconciliation",
	name = "enabled",
	havingValue = "true"
)
public class RefundReconciliationScheduler {

	private final RefundReconciliationClaimService claimService;
	private final RefundReconciliationService reconciliationService;
	private final RefundReconciliationProperties properties;
	private final Clock clock;

	@Scheduled(fixedDelayString = "${prompthub.refund-reconciliation.fixed-delay-ms:5000}")
	public void reconcileDueRequests() {
		LocalDateTime now = LocalDateTime.now(clock);
		claimService.claimDueRequests(
			now,
			properties.batchSize(),
			Duration.ofMillis(properties.leaseMs())
		).forEach(refundRequestId -> reconcile(refundRequestId, now));
	}

	private void reconcile(UUID refundRequestId, LocalDateTime checkedAt) {
		try {
			reconciliationService.reconcile(refundRequestId, checkedAt);
		} catch (RuntimeException exception) {
			log.error("환불 재조정 결과 반영에 실패했습니다. refundRequestId={}", refundRequestId, exception);
		}
	}
}
