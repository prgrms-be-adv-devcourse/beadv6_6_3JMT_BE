package com.prompthub.ai.settlement.application.service.run;

import com.prompthub.ai.global.config.AiSettlementProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class SettlementRunConcurrencyLimiter {

    private final Semaphore semaphore;

    @Autowired
    public SettlementRunConcurrencyLimiter(AiSettlementProperties properties) {
        this(properties.execution().maxConcurrentRuns());
    }

    SettlementRunConcurrencyLimiter(int maxConcurrentRuns) {
        if (maxConcurrentRuns < 1) {
            throw new IllegalArgumentException("maxConcurrentRuns는 1 이상이어야 합니다.");
        }
        this.semaphore = new Semaphore(maxConcurrentRuns, false);
    }

    public Optional<Lease> tryAcquire() {
        if (!semaphore.tryAcquire()) {
            return Optional.empty();
        }
        return Optional.of(new Lease(semaphore));
    }

    public static final class Lease implements AutoCloseable {

        private final Semaphore semaphore;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(Semaphore semaphore) {
            this.semaphore = semaphore;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                semaphore.release();
            }
        }
    }
}
