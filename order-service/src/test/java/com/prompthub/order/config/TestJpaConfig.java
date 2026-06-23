package com.prompthub.order.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.time.LocalDateTime;
import java.util.Optional;

@TestConfiguration
@EnableJpaAuditing(dateTimeProviderRef = "auditingAware")
public class TestJpaConfig {

	@Bean
	public DateTimeProvider auditingAware() {
		return () -> Optional.of(LocalDateTime.now());
	}
}
