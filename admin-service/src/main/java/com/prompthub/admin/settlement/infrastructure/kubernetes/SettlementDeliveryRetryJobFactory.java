package com.prompthub.admin.settlement.infrastructure.kubernetes;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.batch.v1.CronJob;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.JobSpec;
import io.fabric8.kubernetes.api.model.batch.v1.JobSpecBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class SettlementDeliveryRetryJobFactory {

    public static final String PURPOSE_LABEL =
            "prompthub.io/job-purpose";
    public static final String DELIVERY_ID_LABEL =
            "prompthub.io/settlement-delivery-id";
    public static final String PURPOSE = "settlement-delivery-retry";
    private static final String SETTLEMENT_CONTAINER = "settlement-service";
    private static final String EXECUTION_MODE_PREFIX =
            "--settlement.execution.mode=";
    private static final String RETRY_ID_PREFIX =
            "--settlement.delivery.retry-id=";

    private final int ttlSecondsAfterFinished;

    public SettlementDeliveryRetryJobFactory(int ttlSecondsAfterFinished) {
        if (ttlSecondsAfterFinished < 0) {
            throw new IllegalArgumentException(
                    "완료된 재전송 Job의 TTL은 0 이상이어야 합니다.");
        }
        this.ttlSecondsAfterFinished = ttlSecondsAfterFinished;
    }

    public Job create(
            CronJob source,
            UUID deliveryId,
            int previousAttemptCount) {
        if (source == null
                || source.getSpec() == null
                || source.getSpec().getJobTemplate() == null
                || source.getSpec().getJobTemplate().getSpec() == null) {
            throw new SettlementDeliveryRetryJobException(
                    "정산 CronJob 템플릿을 찾을 수 없습니다.");
        }

        JobSpec spec = new JobSpecBuilder(
                source.getSpec().getJobTemplate().getSpec()).build();
        spec.setBackoffLimit(0);
        spec.setTtlSecondsAfterFinished(ttlSecondsAfterFinished);
        updateTemplate(spec, deliveryId);

        return new JobBuilder()
                .withNewMetadata()
                .withName(jobName(deliveryId, previousAttemptCount))
                .withLabels(Map.of(
                        PURPOSE_LABEL, PURPOSE,
                        DELIVERY_ID_LABEL, deliveryId.toString()))
                .endMetadata()
                .withSpec(spec)
                .build();
    }

    static String jobName(UUID deliveryId, int previousAttemptCount) {
        return "retry-"
                + deliveryId.toString().replace("-", "")
                + "-a"
                + previousAttemptCount;
    }

    private void updateTemplate(JobSpec spec, UUID deliveryId) {
        if (spec.getTemplate() == null || spec.getTemplate().getSpec() == null) {
            throw new SettlementDeliveryRetryJobException(
                    "정산 CronJob의 Pod 템플릿을 찾을 수 없습니다.");
        }

        ObjectMeta metadata = spec.getTemplate().getMetadata();
        if (metadata == null) {
            metadata = new ObjectMeta();
            spec.getTemplate().setMetadata(metadata);
        }
        Map<String, String> labels = metadata.getLabels() == null
                ? new HashMap<>()
                : new HashMap<>(metadata.getLabels());
        labels.put(PURPOSE_LABEL, PURPOSE);
        labels.put(DELIVERY_ID_LABEL, deliveryId.toString());
        metadata.setLabels(labels);

        Container container = spec.getTemplate().getSpec().getContainers()
                .stream()
                .filter(item -> SETTLEMENT_CONTAINER.equals(item.getName()))
                .findFirst()
                .orElseThrow(() -> new SettlementDeliveryRetryJobException(
                        "정산 CronJob의 settlement-service 컨테이너를 찾을 수 없습니다."));
        List<String> args = container.getArgs() == null
                ? new ArrayList<>()
                : new ArrayList<>(container.getArgs());
        args.removeIf(arg -> arg.startsWith(EXECUTION_MODE_PREFIX)
                || arg.startsWith(RETRY_ID_PREFIX));
        args.add(EXECUTION_MODE_PREFIX + "delivery-retry");
        args.add(RETRY_ID_PREFIX + deliveryId);
        container.setArgs(args);
    }
}
