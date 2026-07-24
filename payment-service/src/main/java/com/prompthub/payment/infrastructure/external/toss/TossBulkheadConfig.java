package com.prompthub.payment.infrastructure.external.toss;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TossBulkheadConfig {

    @Bean
    public BulkheadRegistry tossBulkheadRegistry(
        @Value("${resilience4j.bulkhead.instances.toss-confirm-bulkhead.max-concurrent-calls}")
        int maxConcurrentCalls,
        @Value("${resilience4j.bulkhead.instances.toss-confirm-bulkhead.max-wait-duration}")
        Duration maxWaitDuration
    ) {
        BulkheadConfig bulkheadConfig = BulkheadConfig.custom()
            .maxConcurrentCalls(maxConcurrentCalls)
            .maxWaitDuration(maxWaitDuration)
            .build();
        return BulkheadRegistry.of(bulkheadConfig);
    }

    @Bean("tossConfirmBulkhead")
    public Bulkhead tossConfirmBulkhead(BulkheadRegistry tossBulkheadRegistry) {
        return tossBulkheadRegistry.bulkhead("toss-confirm-bulkhead");
    }

    @Bean
    public MeterBinder tossBulkheadMetrics(BulkheadRegistry tossBulkheadRegistry) {
        return TaggedBulkheadMetrics.ofBulkheadRegistry(tossBulkheadRegistry);
    }
}
