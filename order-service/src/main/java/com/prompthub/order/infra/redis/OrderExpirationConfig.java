package com.prompthub.order.infra.redis;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(OrderExpirationProperties.class)
public class OrderExpirationConfig {

	@Bean
	public Clock clock() {
		return Clock.systemDefaultZone();
	}
}
