package com.prompthub.order.infra.metrics;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxMonitorPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void defaultsMonitoringToEnabledWithFiveMinuteOldestThreshold() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();

            OutboxMonitorProperties properties = context.getBean(OutboxMonitorProperties.class);
            assertThat(properties.enabled()).isTrue();
            assertThat(properties.fixedDelayMs()).isEqualTo(60_000L);
            assertThat(properties.failedCountThreshold()).isEqualTo(1L);
            assertThat(properties.oldestUnpublishedThresholdMs()).isEqualTo(300_000L);
        });
    }

    @Test
    void bindsDisabledMonitorForTestProfiles() {
        contextRunner.withPropertyValues("prompthub.outbox-monitor.enabled=false")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(OutboxMonitorProperties.class).enabled()).isFalse();
            });
    }

    @Test
    void bindsPositiveOldestThresholdOverride() {
        contextRunner.withPropertyValues("prompthub.outbox-monitor.oldest-unpublished-threshold-ms=1")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(OutboxMonitorProperties.class).oldestUnpublishedThresholdMs())
                    .isEqualTo(1L);
            });
    }

    @Test
    void rejectsNonPositiveOldestThreshold() {
        assertThatCode(() -> new OutboxMonitorProperties(true, 60_000L, 1L, 1L))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> new OutboxMonitorProperties(true, 60_000L, 1L, 0L))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OutboxMonitorProperties.class)
    static class PropertiesConfiguration {
    }
}
