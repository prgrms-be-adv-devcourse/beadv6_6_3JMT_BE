package com.prompthub.payment.infrastructure.external.toss;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TossBulkheadConfigTest {

    private final TossBulkheadConfig config = new TossBulkheadConfig();

    @Test
    void 설정값대로_confirm_Bulkhead를_생성한다() {
        BulkheadRegistry registry = config.tossBulkheadRegistry(20, Duration.ofMillis(200));

        Bulkhead confirmBulkhead = config.tossConfirmBulkhead(registry);

        assertThat(confirmBulkhead.getName()).isEqualTo("toss-confirm-bulkhead");
        assertThat(confirmBulkhead.getBulkheadConfig().getMaxConcurrentCalls()).isEqualTo(20);
        assertThat(confirmBulkhead.getBulkheadConfig().getMaxWaitDuration()).isEqualTo(Duration.ofMillis(200));
    }
}
