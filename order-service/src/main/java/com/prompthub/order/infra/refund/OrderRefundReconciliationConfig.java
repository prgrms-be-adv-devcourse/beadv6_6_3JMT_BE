package com.prompthub.order.infra.refund;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OrderRefundReconciliationProperties.class)
public class OrderRefundReconciliationConfig {
}
