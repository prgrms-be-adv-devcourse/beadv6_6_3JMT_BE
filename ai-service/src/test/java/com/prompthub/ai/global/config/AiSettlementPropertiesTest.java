package com.prompthub.ai.global.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AiSettlementPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(PropertiesConfiguration.class)
            .withPropertyValues("ai.user-grpc-internal-token=test-token");

    @Test
    void bindsSafeDefaults() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();

            AiSettlementProperties properties = context.getBean(AiSettlementProperties.class);
            assertThat(properties.model()).isEqualTo("gpt-5.6-luna");
            assertThat(properties.reasoningEffort()).isEqualTo("low");
            assertThat(properties.maxCompletionTokens()).isEqualTo(2_000);
            assertThat(properties.historyMaxTokens()).isEqualTo(8_000);
            assertThat(properties.runTimeout()).isEqualTo(Duration.ofSeconds(90));
            assertThat(properties.userGrpcDeadline()).isEqualTo(Duration.ofSeconds(3));
            assertThat(properties.execution().maxConcurrentRuns()).isEqualTo(4);
            assertThat(properties.conversation().ttl()).isEqualTo(Duration.ofHours(24));
            assertThat(properties.conversation().maxPairs()).isEqualTo(20);
            assertThat(properties.sse().heartbeat()).isEqualTo(Duration.ofSeconds(15));
            assertThat(properties.settlement().chat().enabled()).isFalse();
        });
    }

    @Test
    void rejectsNonPositiveConcurrency() {
        contextRunner
                .withPropertyValues("ai.execution.max-concurrent-runs=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsNonPositiveDuration() {
        contextRunner
                .withPropertyValues("ai.sse.heartbeat=0s")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("heartbeat은 양수여야 합니다.");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AiSettlementProperties.class)
    static class PropertiesConfiguration {
    }
}
