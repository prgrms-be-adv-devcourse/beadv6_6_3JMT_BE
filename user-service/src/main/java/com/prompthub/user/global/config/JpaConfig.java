package com.prompthub.user.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.time.LocalDateTime;
import java.util.Optional;

@Configuration
@EnableJpaAuditing(dateTimeProviderRef = "auditingAware")
public class JpaConfig {

    @Bean
    public DateTimeProvider auditingAware() {
        return () -> Optional.of(LocalDateTime.now());
    }
}
