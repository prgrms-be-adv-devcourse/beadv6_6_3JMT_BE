package com.prompthub.ai.settlement.infrastructure.redis;

import com.prompthub.ai.global.exception.AiErrorCode;
import com.prompthub.ai.global.exception.AiException;
import com.prompthub.ai.settlement.domain.run.RunStage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisRunEventPublisherTest {

    @Test
    void publishFailureRecordsOnlyFixedSafeMetricTags() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.convertAndSend(anyString(), anyString()))
                .thenThrow(new IllegalStateException("sensitive redis command"));
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RedisRunEventPublisher publisher = new RedisRunEventPublisher(
                redisTemplate,
                JsonMapper.builder().findAndAddModules().build(),
                meterRegistry
        );

        assertThatThrownBy(() -> publisher.progress(
                UUID.randomUUID(),
                RunStage.ANALYZING,
                Instant.parse("2026-07-22T12:00:00Z")
        )).isInstanceOfSatisfying(AiException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(AiErrorCode.AI_STATE_UNAVAILABLE));

        assertThat(meterRegistry.get("ai.redis.errors")
                .tags("operation", "publish", "error_code", "AI_STATE_UNAVAILABLE")
                .counter()
                .count()).isEqualTo(1.0);
        assertThat(meterRegistry.getMeters().stream()
                .flatMap(meter -> meter.getId().getTags().stream())
                .map(tag -> tag.getValue()))
                .doesNotContain("sensitive redis command");
    }
}
