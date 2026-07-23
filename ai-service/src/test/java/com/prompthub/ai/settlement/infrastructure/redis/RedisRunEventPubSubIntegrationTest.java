package com.prompthub.ai.settlement.infrastructure.redis;

import com.prompthub.ai.settlement.application.service.RunFutureRegistry;
import com.prompthub.ai.settlement.presentation.sse.SseEmitterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class RedisRunEventPubSubIntegrationTest {

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine")
            .withExposedPorts(6379);

    private LettuceConnectionFactory connectionFactory;
    private RedisMessageListenerContainer firstContainer;
    private RedisMessageListenerContainer secondContainer;

    private SseEmitterRegistry firstRegistry;
    private SseEmitterRegistry secondRegistry;
    private RedisRunEventPublisher publisher;

    @BeforeEach
    void setUp() {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(
                REDIS.getHost(),
                REDIS.getMappedPort(6379)
        );
        configuration.setDatabase(1);
        connectionFactory = new LettuceConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();

        StringRedisTemplate redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

        firstRegistry = new SseEmitterRegistry();
        secondRegistry = new SseEmitterRegistry();
        firstContainer = listenerContainer(new RedisRunEventSubscriber(
                objectMapper,
                firstRegistry,
                new RunFutureRegistry(new SimpleMeterRegistry())
        ));
        secondContainer = listenerContainer(new RedisRunEventSubscriber(
                objectMapper,
                secondRegistry,
                new RunFutureRegistry(new SimpleMeterRegistry())
        ));
        firstContainer.start();
        secondContainer.start();

        publisher = new RedisRunEventPublisher(redisTemplate, objectMapper);
    }

    @AfterEach
    void tearDown() {
        if (firstContainer != null) {
            firstContainer.stop();
        }
        if (secondContainer != null) {
            secondContainer.stop();
        }
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void dispatchesDeltaOnlyToFirstStreamAndTerminalToEveryLocalEmitter() throws InterruptedException {
        UUID runId = UUID.randomUUID();
        RecordingEmitter firstStream = new RecordingEmitter(2);
        RecordingEmitter reconnectStream = new RecordingEmitter(1);
        firstRegistry.register(runId, firstStream, true);
        firstRegistry.register(runId, reconnectStream, false);

        Instant now = Instant.parse("2026-07-22T12:00:00Z");
        publisher.delta(runId, 1L, "정산", now);
        publisher.done(runId, "정산 답변", now.plusSeconds(1));

        assertThat(firstStream.await()).isTrue();
        assertThat(reconnectStream.await()).isTrue();
        assertThat(firstStream.awaitCompleted()).isTrue();
        assertThat(reconnectStream.awaitCompleted()).isTrue();
        assertThat(firstStream.sentCount()).isEqualTo(2);
        assertThat(reconnectStream.sentCount()).isEqualTo(1);
        assertThat(firstStream.completed()).isTrue();
        assertThat(reconnectStream.completed()).isTrue();
        assertThat(firstRegistry.connectionCount(runId)).isZero();
        assertThat(secondRegistry.connectionCount(runId)).isZero();
    }

    private RedisMessageListenerContainer listenerContainer(RedisRunEventSubscriber subscriber) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(subscriber, new PatternTopic("ai:settlement:events:*"));
        container.afterPropertiesSet();
        return container;
    }

    private static final class RecordingEmitter extends SseEmitter {

        private final AtomicInteger sentCount = new AtomicInteger();
        private final AtomicBoolean completed = new AtomicBoolean();
        private final CountDownLatch expectedEvents;
        private final CountDownLatch completion = new CountDownLatch(1);

        private RecordingEmitter(int expectedEvents) {
            this.expectedEvents = new CountDownLatch(expectedEvents);
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            sentCount.incrementAndGet();
            expectedEvents.countDown();
        }

        @Override
        public void complete() {
            completed.set(true);
            completion.countDown();
        }

        private boolean await() throws InterruptedException {
            return expectedEvents.await(5, TimeUnit.SECONDS);
        }

        private int sentCount() {
            return sentCount.get();
        }

        private boolean awaitCompleted() throws InterruptedException {
            return completion.await(5, TimeUnit.SECONDS);
        }

        private boolean completed() {
            return completed.get();
        }
    }
}
