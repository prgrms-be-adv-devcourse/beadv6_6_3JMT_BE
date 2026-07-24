package com.prompthub.ai.settlement.infrastructure.client.openai;

import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.OpenAIServiceException;
import com.prompthub.ai.global.config.AiSettlementProperties;
import com.prompthub.ai.global.exception.AiErrorCode;
import com.prompthub.ai.global.exception.AiException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class OpenAiCallRetryExecutor {

    private static final int MAX_ATTEMPTS = 2;
    private static final Duration RETRY_BACKOFF = Duration.ofMillis(250);

    private final String model;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final BackoffSleeper sleeper;
    private final ExecutorService providerCallExecutor;

    @Autowired
    public OpenAiCallRetryExecutor(
            AiSettlementProperties properties,
            MeterRegistry meterRegistry,
            Clock clock,
            @Qualifier("aiProviderCallExecutor") ExecutorService providerCallExecutor
    ) {
        this(properties.model(), meterRegistry, clock,
                duration -> Thread.sleep(duration.toMillis()), providerCallExecutor);
    }

    OpenAiCallRetryExecutor(
            String model,
            MeterRegistry meterRegistry,
            Clock clock,
            BackoffSleeper sleeper,
            ExecutorService providerCallExecutor
    ) {
        this.model = model;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        this.sleeper = sleeper;
        this.providerCallExecutor = providerCallExecutor;
    }

    public <T> T execute(UUID runId, Instant deadlineAt, Supplier<T> operation) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            assertBeforeDeadline(deadlineAt);
            Timer.Sample sample = Timer.start(meterRegistry);
            try {
                T result = executeWithinDeadline(deadlineAt, operation);
                assertBeforeDeadline(deadlineAt);
                recordCall(sample, "success", "NONE");
                return result;
            } catch (AiException exception) {
                recordCall(sample, "failure", exception.getErrorCode().getCode());
                throw exception;
            } catch (RuntimeException exception) {
                if (Thread.currentThread().isInterrupted()) {
                    recordCall(sample, "failure", AiErrorCode.RUN_TIMEOUT.getCode());
                    throw new AiException(AiErrorCode.RUN_TIMEOUT);
                }
                boolean transientFailure = isTransient(exception);
                String errorCode = transientFailure
                        ? "PROVIDER_TRANSIENT"
                        : AiErrorCode.AI_PROVIDER_UNAVAILABLE.getCode();
                recordCall(sample, "failure", errorCode);
                if (!transientFailure || attempt == MAX_ATTEMPTS) {
                    log.warn("OpenAI call failed. runId={}, model={}, attempt={}, errorCode={}",
                            runId, model, attempt, AiErrorCode.AI_PROVIDER_UNAVAILABLE.getCode());
                    throw new AiException(AiErrorCode.AI_PROVIDER_UNAVAILABLE);
                }
                if (!canWaitForRetry(deadlineAt)) {
                    log.warn("OpenAI retry skipped by run deadline. runId={}, model={}, attempt={}",
                            runId, model, attempt);
                    throw new AiException(AiErrorCode.RUN_TIMEOUT);
                }
                meterRegistry.counter(
                        "ai.openai.retries",
                        "model", model,
                        "outcome", "scheduled",
                        "error.code", "PROVIDER_TRANSIENT").increment();
                log.info("Retrying OpenAI call. runId={}, model={}, nextAttempt={}",
                        runId, model, attempt + 1);
                sleepBeforeRetry();
            }
        }
        throw new AiException(AiErrorCode.AI_PROVIDER_UNAVAILABLE);
    }

    private <T> T executeWithinDeadline(Instant deadlineAt, Supplier<T> operation) {
        Future<T> future = providerCallExecutor.submit(operation::get);
        try {
            Duration remaining = Duration.between(clock.instant(), deadlineAt);
            if (remaining.isZero() || remaining.isNegative()) {
                future.cancel(true);
                throw new AiException(AiErrorCode.RUN_TIMEOUT);
            }
            return future.get(remaining.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw new AiException(AiErrorCode.RUN_TIMEOUT);
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new AiException(AiErrorCode.RUN_TIMEOUT);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new AiException(AiErrorCode.AI_PROVIDER_UNAVAILABLE);
        }
    }

    private void assertBeforeDeadline(Instant deadlineAt) {
        if (Thread.currentThread().isInterrupted() || !clock.instant().isBefore(deadlineAt)) {
            throw new AiException(AiErrorCode.RUN_TIMEOUT);
        }
    }

    private boolean canWaitForRetry(Instant deadlineAt) {
        Duration remaining = Duration.between(clock.instant(), deadlineAt);
        return remaining.compareTo(RETRY_BACKOFF) > 0;
    }

    private void sleepBeforeRetry() {
        try {
            sleeper.sleep(RETRY_BACKOFF);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AiException(AiErrorCode.RUN_TIMEOUT);
        }
    }

    private boolean isTransient(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof OpenAIRetryableException
                    || current instanceof OpenAIIoException
                    || current instanceof SocketTimeoutException
                    || current instanceof HttpTimeoutException
                    || current instanceof ConnectException
                    || current instanceof TimeoutException) {
                return true;
            }
            if (current instanceof OpenAIServiceException serviceException
                    && (serviceException.statusCode() == 429 || serviceException.statusCode() >= 500)) {
                return true;
            }
            if (current instanceof SocketException socketException && isConnectionReset(socketException)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean isConnectionReset(SocketException exception) {
        String message = exception.getMessage();
        if (message == null) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("reset")
                || normalized.contains("broken pipe")
                || normalized.contains("connection aborted")
                || normalized.contains("connection closed");
    }

    private void recordCall(Timer.Sample sample, String outcome, String errorCode) {
        meterRegistry.counter(
                "ai.openai.calls",
                "model", model,
                "outcome", outcome,
                "error.code", errorCode).increment();
        sample.stop(meterRegistry.timer(
                "ai.openai.duration",
                "model", model,
                "outcome", outcome,
                "error.code", errorCode));
    }

    @FunctionalInterface
    interface BackoffSleeper {
        void sleep(Duration duration) throws InterruptedException;
    }
}
