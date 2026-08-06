package com.prompthub.order.infra.redis;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(OrderExpirationProperties.class)
public class OrderExpirationConfig {

	private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

	@Bean
	public Clock clock() {
		return Clock.system(SERVICE_ZONE);
	}
}
