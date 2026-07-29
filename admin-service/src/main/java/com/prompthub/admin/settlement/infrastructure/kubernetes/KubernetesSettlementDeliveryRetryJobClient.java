package com.prompthub.admin.settlement.infrastructure.kubernetes;

import io.fabric8.kubernetes.api.model.batch.v1.CronJob;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class KubernetesSettlementDeliveryRetryJobClient
        implements SettlementDeliveryRetryJobClient {

    private final KubernetesClient client;
    private final SettlementDeliveryRetryJobFactory jobFactory;
    private final String namespace;
    private final String sourceCronJobName;

    public KubernetesSettlementDeliveryRetryJobClient(
            KubernetesClient client,
            SettlementDeliveryRetryJobFactory jobFactory,
            String namespace,
            String sourceCronJobName) {
        this.client = client;
        this.jobFactory = jobFactory;
        this.namespace = namespace;
        this.sourceCronJobName = sourceCronJobName;
    }

    @Override
    public Set<UUID> findActiveDeliveryIds() {
        try {
            return client.batch().v1().jobs()
                    .inNamespace(namespace)
                    .withLabel(
                            SettlementDeliveryRetryJobFactory.PURPOSE_LABEL,
                            SettlementDeliveryRetryJobFactory.PURPOSE)
                    .list()
                    .getItems()
                    .stream()
                    .filter(KubernetesSettlementDeliveryRetryJobClient::isActive)
                    .map(job -> job.getMetadata().getLabels().get(
                            SettlementDeliveryRetryJobFactory.DELIVERY_ID_LABEL))
                    .filter(Objects::nonNull)
                    .map(this::toUuid)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toUnmodifiableSet());
        } catch (KubernetesClientException exception) {
            throw new SettlementDeliveryRetryJobException(
                    "실행 중인 정산 재전송 Job을 조회할 수 없습니다.",
                    exception);
        }
    }

    @Override
    public boolean isActive(UUID settlementDeliveryId) {
        return findActiveDeliveryIds().contains(settlementDeliveryId);
    }

    @Override
    public void launch(
            UUID settlementDeliveryId,
            int previousAttemptCount) {
        try {
            CronJob source = client.batch().v1().cronjobs()
                    .inNamespace(namespace)
                    .withName(sourceCronJobName)
                    .get();
            Job job = jobFactory.create(
                    source,
                    settlementDeliveryId,
                    previousAttemptCount);
            var jobs = client.batch().v1().jobs().inNamespace(namespace);
            Job existing = jobs.withName(job.getMetadata().getName()).get();
            if (existing != null) {
                if (isActive(existing)) {
                    throw new SettlementDeliveryRetryJobAlreadyExistsException(
                            "동일한 정산 재전송 Job이 이미 실행 중입니다.");
                }
                jobs.withName(job.getMetadata().getName()).delete();
            }
            jobs.resource(job).create();
        } catch (KubernetesClientException exception) {
            if (exception.getCode() == 409) {
                throw new SettlementDeliveryRetryJobAlreadyExistsException(
                        "동일한 정산 재전송 Job이 이미 생성되었습니다.",
                        exception);
            }
            throw new SettlementDeliveryRetryJobException(
                    "정산 재전송 Job을 생성할 수 없습니다.",
                    exception);
        }
    }

    static boolean isActive(Job job) {
        if (job.getStatus() == null || job.getStatus().getConditions() == null) {
            return true;
        }
        return job.getStatus().getConditions().stream()
                .noneMatch(condition ->
                        ("Complete".equals(condition.getType())
                                || "Failed".equals(condition.getType()))
                                && "True".equals(condition.getStatus()));
    }

    private UUID toUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
