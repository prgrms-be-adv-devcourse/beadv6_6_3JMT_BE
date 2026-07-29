package com.prompthub.admin.settlement.infrastructure.kubernetes;

import static org.assertj.core.api.Assertions.assertThat;

import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class SettlementDeliveryKubernetesConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            SettlementDeliveryKubernetesConfiguration.class);

    @Test
    void 재전송기능이_활성화되면_KubernetesClient를_생성한다() {
        contextRunner
                .withPropertyValues(
                        "settlement.delivery.retry-job.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context)
                            .hasSingleBean(KubernetesClient.class);
                    assertThat(context)
                            .hasSingleBean(SettlementDeliveryRetryJobClient.class);
                });
    }
}
