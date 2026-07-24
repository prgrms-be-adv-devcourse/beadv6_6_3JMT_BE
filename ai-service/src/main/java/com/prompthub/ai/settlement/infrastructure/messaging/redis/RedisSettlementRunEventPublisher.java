package com.prompthub.ai.settlement.infrastructure.messaging.redis;

import com.prompthub.ai.global.exception.AiErrorCode;
import com.prompthub.ai.global.exception.AiException;
import com.prompthub.ai.settlement.application.event.RunEvent;
import com.prompthub.ai.settlement.application.port.SettlementRunEventPublisher;
import com.prompthub.ai.settlement.domain.run.RunStage;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

@Component
public class RedisSettlementRunEventPublisher implements SettlementRunEventPublisher {

    private static final String CHANNEL_PREFIX = "ai:settlement:events:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public RedisSettlementRunEventPublisher(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this(redisTemplate, objectMapper, Metrics.globalRegistry);
    }

    @Autowired
    public RedisSettlementRunEventPublisher(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void progress(UUID runId, RunStage stage, Instant occurredAt) {
        publish(RunEvent.progress(runId, stage, occurredAt));
    }

    @Override
    public void delta(UUID runId, long sequence, String text, Instant occurredAt) {
        publish(RunEvent.delta(runId, sequence, text, occurredAt));
    }

    @Override
    public void done(UUID runId, String answer, Instant completedAt) {
        publish(RunEvent.done(runId, answer, completedAt));
    }

    @Override
    public void failed(UUID runId, String code, String message, Instant failedAt) {
        publish(RunEvent.failed(runId, code, message, failedAt));
    }

    @Override
    public void cancelled(UUID runId, Instant cancelledAt) {
        publish(RunEvent.cancelled(runId, cancelledAt));
    }

    private void publish(RunEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            redisTemplate.convertAndSend(CHANNEL_PREFIX + event.runId(), payload);
        } catch (Exception exception) {
            meterRegistry.counter(
                    "ai.redis.errors",
                    "operation", "publish",
                    "error_code", AiErrorCode.AI_STATE_UNAVAILABLE.getCode()).increment();
            throw new AiException(AiErrorCode.AI_STATE_UNAVAILABLE, exception);
        }
    }
}
