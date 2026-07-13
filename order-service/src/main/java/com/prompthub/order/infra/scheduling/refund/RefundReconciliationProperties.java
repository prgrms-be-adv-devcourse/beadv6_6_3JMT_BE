package com.prompthub.order.infra.scheduling.refund;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "prompthub.refund-reconciliation")
public record RefundReconciliationProperties(
	boolean enabled,
	long fixedDelayMs,
	int batchSize,
	long leaseMs
) {
}
