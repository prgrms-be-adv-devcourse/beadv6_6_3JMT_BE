package com.prompthub.ai.settlement.application.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class RunFutureRegistryTest {

    @Test
    void runningTaskKeepsCapacityUntilCallableActuallyExitsAfterCancel() throws InterruptedException {
        RunCapacityGate capacityGate = new RunCapacityGate(1);
        RunCapacityGate.Lease lease = capacityGate.tryAcquire().orElseThrow();
        RunFutureRegistry registry = new RunFutureRegistry(new SimpleMeterRegistry());
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        UUID runId = UUID.randomUUID();
        FutureTask<Void> task = registry.register(runId, () -> {
            started.countDown();
            boolean finished = false;
            while (!finished) {
                try {
                    finished = finish.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    // OpenAI client가 interrupt를 늦게 반영하는 상황을 결정적으로 재현한다.
                }
            }
        }, lease::close);
        Thread worker = new Thread(task);
        worker.start();
        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();

        assertThat(registry.cancel(runId)).isTrue();

        assertThat(capacityGate.tryAcquire()).isEmpty();
        assertThat(registry.size()).isEqualTo(1);

        finish.countDown();
        worker.join(1_000);

        assertThat(registry.size()).isZero();
        assertThat(capacityGate.tryAcquire()).isPresent();
    }
}
