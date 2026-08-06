package com.prompthub.admin.settlement.infrastructure.kubernetes;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class SettlementDeliveryKubernetesConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(
            name = "settlement.delivery.retry-job.enabled",
            havingValue = "true")
    KubernetesClient settlementDeliveryKubernetesClient() {
        return new KubernetesClientBuilder().build();
    }

    @Bean
    @ConditionalOnProperty(
            name = "settlement.delivery.retry-job.enabled",
            havingValue = "true")
    SettlementDeliveryRetryJobClient settlementDeliveryRetryJobClient(
            KubernetesClient client,
            @Value("${settlement.delivery.retry-job.namespace:prompthub}")
            String namespace,
            @Value("${settlement.delivery.retry-job.source-cron-job:settlement-weekly}")
            String sourceCronJobName,
            @Value("${settlement.delivery.retry-job.ttl-seconds:86400}")
            int ttlSeconds) {
        return new KubernetesSettlementDeliveryRetryJobClient(
                client,
                new SettlementDeliveryRetryJobFactory(ttlSeconds),
                namespace,
                sourceCronJobName);
    }

    @Bean
    @ConditionalOnProperty(
            name = "settlement.delivery.retry-job.enabled",
            havingValue = "false",
            matchIfMissing = true)
    SettlementDeliveryRetryJobClient disabledSettlementDeliveryRetryJobClient() {
        return new DisabledSettlementDeliveryRetryJobClient();
    }
}
