package com.prompthub.order.infra.scheduling.refund;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RefundReconciliationProperties.class)
public class RefundReconciliationConfig {
}
