package com.prompthub.admin.settlement.infrastructure.kubernetes;

import static org.assertj.core.api.Assertions.assertThat;

import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import org.junit.jupiter.api.Test;

class KubernetesSettlementDeliveryRetryJobClientTest {

    @Test
    void 생성직후와_실행중인_Job은_활성으로_판단한다() {
        var created = new JobBuilder()
                .withNewMetadata()
                .withName("created")
                .endMetadata()
                .build();
        var running = new JobBuilder()
                .withNewMetadata()
                .withName("running")
                .endMetadata()
                .withNewStatus()
                .withActive(1)
                .endStatus()
                .build();

        assertThat(KubernetesSettlementDeliveryRetryJobClient.isActive(created))
                .isTrue();
        assertThat(KubernetesSettlementDeliveryRetryJobClient.isActive(running))
                .isTrue();
    }

    @Test
    void 완료되거나_실패한_Job은_활성이_아니다() {
        var completed = new JobBuilder()
                .withNewStatus()
                .addNewCondition()
                .withType("Complete")
                .withStatus("True")
                .endCondition()
                .endStatus()
                .build();
        var failed = new JobBuilder()
                .withNewStatus()
                .addNewCondition()
                .withType("Failed")
                .withStatus("True")
                .endCondition()
                .endStatus()
                .build();

        assertThat(KubernetesSettlementDeliveryRetryJobClient.isActive(completed))
                .isFalse();
        assertThat(KubernetesSettlementDeliveryRetryJobClient.isActive(failed))
                .isFalse();
    }
}
