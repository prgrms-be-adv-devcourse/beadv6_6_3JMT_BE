package com.prompthub.ai.settlement.application.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RunCapacityGateTest {

    @Test
    void leaseReturnsCapacityExactlyOnce() {
        RunCapacityGate gate = new RunCapacityGate(1);

        RunCapacityGate.Lease first = gate.tryAcquire().orElseThrow();
        assertThat(gate.tryAcquire()).isEmpty();

        first.close();
        first.close();

        RunCapacityGate.Lease second = gate.tryAcquire().orElseThrow();
        assertThat(gate.tryAcquire()).isEmpty();
        second.close();
    }
}
