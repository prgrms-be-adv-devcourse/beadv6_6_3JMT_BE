package com.prompthub.user.sellersettlement.infrastructure.messaging.kafka.consumer.settlement.dlt;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "user.kafka.dlt.slack")
public record SettlementDltSlackProperties(String webhookUrl) {
}
