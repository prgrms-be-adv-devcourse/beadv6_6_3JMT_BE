package com.prompthub.ai.settlement.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.prompthub.ai.global.config.AiSettlementProperties;
import com.prompthub.ai.settlement.AiSettlementTestFixtures;
import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

class SettlementRunExecutionConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(
                    AiSettlementProperties.class,
                    () -> AiSettlementTestFixtures.properties(true))
            .withUserConfiguration(SettlementRunExecutionConfig.class);

    @Test
    void configuresNamedSettlementRunExecutorWithoutQueue() {
        contextRunner.run(context -> {
            assertThat(context).hasBean("aiSettlementExecutor");
            ThreadPoolTaskExecutor executor =
                    context.getBean("aiSettlementExecutor", ThreadPoolTaskExecutor.class);

            assertThat(executor.getCorePoolSize()).isEqualTo(4);
            assertThat(executor.getMaxPoolSize()).isEqualTo(4);
            assertThat(executor.getQueueCapacity()).isZero();
            assertThat(executor.getThreadNamePrefix()).isEqualTo("ai-settlement-run-");
            assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                    .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
            assertThat(ReflectionTestUtils.getField(
                    executor, "waitForTasksToCompleteOnShutdown"))
                    .isEqualTo(false);
        });
    }
}
