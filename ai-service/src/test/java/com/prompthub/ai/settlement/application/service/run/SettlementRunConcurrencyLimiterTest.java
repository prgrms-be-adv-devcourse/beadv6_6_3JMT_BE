package com.prompthub.ai.settlement.application.service.run;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SettlementRunConcurrencyLimiterTest {

    @Test
    void leaseReturnsCapacityExactlyOnce() {
        SettlementRunConcurrencyLimiter gate = new SettlementRunConcurrencyLimiter(1);

        SettlementRunConcurrencyLimiter.Lease first = gate.tryAcquire().orElseThrow();
        assertThat(gate.tryAcquire()).isEmpty();

        first.close();
        first.close();

        SettlementRunConcurrencyLimiter.Lease second = gate.tryAcquire().orElseThrow();
        assertThat(gate.tryAcquire()).isEmpty();
        second.close();
    }
}
