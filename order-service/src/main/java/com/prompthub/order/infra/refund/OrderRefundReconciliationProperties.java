package com.prompthub.order.infra.refund;

import com.prompthub.order.application.service.refund.OrderRefundReconciliationPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "prompthub.order.refund-reconciliation")
public record OrderRefundReconciliationProperties(
	@DefaultValue("10") int initialDelayMinutes
) implements OrderRefundReconciliationPolicy {

	public OrderRefundReconciliationProperties {
		if (initialDelayMinutes <= 0) {
			throw new IllegalArgumentException("initialDelayMinutes must be positive");
		}
	}
}
