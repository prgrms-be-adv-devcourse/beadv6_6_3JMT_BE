package com.prompthub.ai.settlement.application.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class RunFutureRegistry {

    private final ConcurrentHashMap<UUID, RegisteredFutureTask> futures = new ConcurrentHashMap<>();

    public RunFutureRegistry(MeterRegistry meterRegistry) {
        meterRegistry.gauge("ai.chat.active.runs", futures, ConcurrentHashMap::size);
    }

    public FutureTask<Void> register(UUID runId, Runnable runnable, Runnable cleanup) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(runnable, "runnable");
        Objects.requireNonNull(cleanup, "cleanup");
        RegisteredFutureTask future = new RegisteredFutureTask(runId, runnable, cleanup);
        if (futures.putIfAbsent(runId, future) != null) {
            throw new IllegalStateException("동일한 run Future가 이미 등록되어 있습니다.");
        }
        return future;
    }

    public boolean cancel(UUID runId) {
        RegisteredFutureTask future = futures.get(runId);
        return future != null && future.cancel(true);
    }

    public int size() {
        return futures.size();
    }

    private final class RegisteredFutureTask extends FutureTask<Void> {

        private final UUID runId;
        private final Runnable cleanup;
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean cleaned = new AtomicBoolean();

        private RegisteredFutureTask(UUID runId, Runnable runnable, Runnable cleanup) {
            super(runnable, null);
            this.runId = runId;
            this.cleanup = cleanup;
        }

        @Override
        public void run() {
            started.set(true);
            try {
                super.run();
            } finally {
                cleanupOnce();
            }
        }

        @Override
        protected void done() {
            if (!started.get()) {
                cleanupOnce();
            }
        }

        private void cleanupOnce() {
            if (cleaned.compareAndSet(false, true)) {
                futures.remove(runId, this);
                cleanup.run();
            }
        }
    }
}
