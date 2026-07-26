package com.prompthub.ai.settlement.infrastructure.client.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openai.errors.OpenAIRetryableException;
import com.prompthub.ai.global.exception.AiErrorCode;
import com.prompthub.ai.global.exception.AiException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("OpenAI 호출 재시도")
class OpenAiCallRetryExecutorTest {

    private static final Instant NOW = Instant.parse("2026-07-23T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private final ExecutorService providerCallExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void tearDown() {
        providerCallExecutor.shutdownNow();
    }

    @Test
    @DisplayName("transient 오류는 250ms backoff 뒤 한 번만 재시도한다")
    void retriesTransientFailureOnce() {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger sleeps = new AtomicInteger();
        OpenAiCallRetryExecutor executor = new OpenAiCallRetryExecutor(
                "gpt-5.6-luna",
                new SimpleMeterRegistry(),
                CLOCK,
                duration -> {
                    assertThat(duration).isEqualTo(Duration.ofMillis(250));
                    sleeps.incrementAndGet();
                },
                providerCallExecutor);

        String answer = executor.execute(
                UUID.randomUUID(),
                NOW.plusSeconds(10),
                () -> {
                    if (attempts.incrementAndGet() == 1) {
                        throw new OpenAIRetryableException("raw-provider-message");
                    }
                    return "ok";
                });

        assertThat(answer).isEqualTo("ok");
        assertThat(attempts).hasValue(2);
        assertThat(sleeps).hasValue(1);
    }

    @Test
    @DisplayName("backoff 전에 run deadline이 오면 재시도하지 않고 timeout으로 변환한다")
    void failsAsTimeoutWhenDeadlineCannotFitBackoff() {
        AtomicInteger attempts = new AtomicInteger();
        OpenAiCallRetryExecutor executor = new OpenAiCallRetryExecutor(
                "gpt-5.6-luna",
                new SimpleMeterRegistry(),
                CLOCK,
                duration -> {
                    throw new AssertionError("deadline을 넘는 sleep을 수행하면 안 됩니다.");
                },
                providerCallExecutor);

        assertThatThrownBy(() -> executor.execute(
                UUID.randomUUID(),
                NOW.plusMillis(100),
                () -> {
                    attempts.incrementAndGet();
                    throw new OpenAIRetryableException("raw-provider-message");
                }))
                .isInstanceOf(AiException.class)
                .satisfies(exception -> {
                    AiException aiException = (AiException) exception;
                    assertThat(aiException.getErrorCode()).isEqualTo(AiErrorCode.RUN_TIMEOUT);
                    assertThat(aiException.getCause()).isNull();
                    assertThat(aiException.getMessage()).doesNotContain("raw-provider-message");
                });
        assertThat(attempts).hasValue(1);
    }

    @Test
    @DisplayName("blocking provider call이 남은 run deadline을 넘으면 취소하고 timeout으로 변환한다")
    void cancelsBlockingCallAtRemainingRunDeadline() throws InterruptedException {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch cancelled = new CountDownLatch(1);
        CountDownLatch neverCompletes = new CountDownLatch(1);
        Clock systemClock = Clock.systemUTC();
        OpenAiCallRetryExecutor executor = new OpenAiCallRetryExecutor(
                "gpt-5.6-luna",
                new SimpleMeterRegistry(),
                systemClock,
                duration -> { },
                providerCallExecutor);

        assertThatThrownBy(() -> executor.execute(
                UUID.randomUUID(),
                systemClock.instant().plusMillis(250),
                () -> {
                    attempts.incrementAndGet();
                    try {
                        neverCompletes.await();
                        return "unreachable";
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        cancelled.countDown();
                        throw new IllegalStateException("cancelled");
                    }
                }))
                .isInstanceOf(AiException.class)
                .extracting(exception -> ((AiException) exception).getErrorCode())
                .isEqualTo(AiErrorCode.RUN_TIMEOUT);
        assertThat(attempts).hasValue(1);
        assertThat(cancelled.await(1, TimeUnit.SECONDS)).isTrue();
    }
}
