package com.prompthub.admin.settlement.infrastructure.kubernetes;

import static org.assertj.core.api.Assertions.assertThat;

import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.PodSpecBuilder;
import io.fabric8.kubernetes.api.model.PodTemplateSpecBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.CronJob;
import io.fabric8.kubernetes.api.model.batch.v1.CronJobBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.JobSpecBuilder;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SettlementDeliveryRetryJobFactoryTest {

    private static final UUID DELIVERY_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000663");
    private final SettlementDeliveryRetryJobFactory factory =
            new SettlementDeliveryRetryJobFactory(86_400);

    @Test
    void CronJob_템플릿을_복제해_단발성_재전송_Job을_만든다() {
        CronJob source = sourceCronJob();

        var job = factory.create(source, DELIVERY_ID, 3);

        assertThat(job.getMetadata().getName())
                .isEqualTo("retry-00000000000000000000000000000663-a3");
        assertThat(job.getMetadata().getLabels())
                .containsEntry(
                        SettlementDeliveryRetryJobFactory.PURPOSE_LABEL,
                        SettlementDeliveryRetryJobFactory.PURPOSE)
                .containsEntry(
                        SettlementDeliveryRetryJobFactory.DELIVERY_ID_LABEL,
                        DELIVERY_ID.toString());
        assertThat(job.getSpec().getBackoffLimit()).isZero();
        assertThat(job.getSpec().getTtlSecondsAfterFinished()).isEqualTo(86_400);

        var container = job.getSpec().getTemplate().getSpec().getContainers()
                .stream()
                .filter(item -> item.getName().equals("settlement-service"))
                .findFirst()
                .orElseThrow();
        assertThat(container.getImage()).isEqualTo("settlement-image@sha256:test");
        assertThat(container.getArgs())
                .contains(
                        "--settlement.execution.mode=delivery-retry",
                        "--settlement.delivery.retry-id=" + DELIVERY_ID)
                .doesNotContain("--settlement.execution.mode=cronjob");

        assertThat(source.getSpec().getJobTemplate().getSpec()
                .getTemplate().getSpec().getContainers().getFirst().getArgs())
                .contains("--settlement.execution.mode=cronjob");
    }

    private CronJob sourceCronJob() {
        var container = new ContainerBuilder()
                .withName("settlement-service")
                .withImage("settlement-image@sha256:test")
                .withArgs(
                        "--spring.main.web-application-type=none",
                        "--settlement.execution.mode=cronjob")
                .build();
        var template = new PodTemplateSpecBuilder()
                .withNewMetadata()
                .withLabels(Map.of("app.kubernetes.io/name", "settlement-service"))
                .endMetadata()
                .withSpec(new PodSpecBuilder()
                        .withRestartPolicy("Never")
                        .withContainers(List.of(container))
                        .build())
                .build();
        return new CronJobBuilder()
                .withNewMetadata()
                .withName("settlement-weekly")
                .endMetadata()
                .withNewSpec()
                .withNewJobTemplate()
                .withSpec(new JobSpecBuilder()
                        .withBackoffLimit(1)
                        .withTemplate(template)
                        .build())
                .endJobTemplate()
                .endSpec()
                .build();
    }
}
