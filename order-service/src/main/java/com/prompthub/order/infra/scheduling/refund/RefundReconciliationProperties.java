package com.prompthub.order.infra.scheduling.refund;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "prompthub.refund-reconciliation")
public record RefundReconciliationProperties(
	boolean enabled,
	Duration fixedDelay,
	int batchSize,
	Duration leaseDuration
) {
}
