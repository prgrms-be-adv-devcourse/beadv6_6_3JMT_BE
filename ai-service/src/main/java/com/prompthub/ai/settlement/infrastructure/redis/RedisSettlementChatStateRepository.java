package com.prompthub.ai.settlement.infrastructure.redis;

import com.prompthub.ai.global.config.AiSettlementProperties;
import com.prompthub.ai.global.exception.AiErrorCode;
import com.prompthub.ai.global.exception.AiException;
import com.prompthub.ai.settlement.application.port.SettlementChatStateRepository;
import com.prompthub.ai.settlement.domain.AgentRun;
import com.prompthub.ai.settlement.domain.ChatPair;
import com.prompthub.ai.settlement.domain.ConversationSnapshot;
import com.prompthub.ai.settlement.domain.RunStage;
import com.prompthub.ai.settlement.domain.RunStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public class RedisSettlementChatStateRepository implements SettlementChatStateRepository {

    private static final String KEY_PREFIX = "ai:settlement:";
    private static final String RUN_PREFIX = KEY_PREFIX + "run:";
    private static final String MESSAGES_PREFIX = KEY_PREFIX + "conversation:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration conversationTtl;
    private final int maxPairs;
    private final RedisScript<List> acceptRunScript;
    private final RedisScript<Long> completeRunScript;
    private final RedisScript<Long> failRunScript;
    private final RedisScript<String> cancelConversationScript;
    private final RedisScript<Long> expireStaleRunScript;
    private final RedisScript<Long> updateStageScript;
    private final MeterRegistry meterRegistry;

    @Autowired
    public RedisSettlementChatStateRepository(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            AiSettlementProperties properties,
            @Qualifier("acceptRunScript") RedisScript<List> acceptRunScript,
            @Qualifier("completeRunScript") RedisScript<Long> completeRunScript,
            @Qualifier("failRunScript") RedisScript<Long> failRunScript,
            @Qualifier("cancelConversationScript") RedisScript<String> cancelConversationScript,
            @Qualifier("expireStaleRunScript") RedisScript<Long> expireStaleRunScript,
            @Qualifier("updateStageScript") RedisScript<Long> updateStageScript,
            MeterRegistry meterRegistry
    ) {
        this(
                redisTemplate,
                objectMapper,
                properties.conversation().ttl(),
                properties.conversation().maxPairs(),
                acceptRunScript,
                completeRunScript,
                failRunScript,
                cancelConversationScript,
                expireStaleRunScript,
                updateStageScript,
                meterRegistry
        );
    }

    RedisSettlementChatStateRepository(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            Duration conversationTtl,
            int maxPairs,
            RedisScript<List> acceptRunScript,
            RedisScript<Long> completeRunScript,
            RedisScript<Long> failRunScript,
            RedisScript<String> cancelConversationScript,
            RedisScript<Long> expireStaleRunScript,
            RedisScript<Long> updateStageScript
    ) {
        this(
                redisTemplate,
                objectMapper,
                conversationTtl,
                maxPairs,
                acceptRunScript,
                completeRunScript,
                failRunScript,
                cancelConversationScript,
                expireStaleRunScript,
                updateStageScript,
                Metrics.globalRegistry
        );
    }

    RedisSettlementChatStateRepository(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            Duration conversationTtl,
            int maxPairs,
            RedisScript<List> acceptRunScript,
            RedisScript<Long> completeRunScript,
            RedisScript<Long> failRunScript,
            RedisScript<String> cancelConversationScript,
            RedisScript<Long> expireStaleRunScript,
            RedisScript<Long> updateStageScript,
            MeterRegistry meterRegistry
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.conversationTtl = conversationTtl;
        this.maxPairs = maxPairs;
        this.acceptRunScript = acceptRunScript;
        this.completeRunScript = completeRunScript;
        this.failRunScript = failRunScript;
        this.cancelConversationScript = cancelConversationScript;
        this.expireStaleRunScript = expireStaleRunScript;
        this.updateStageScript = updateStageScript;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public AcceptRunResult acceptRun(UUID actorId, UUID proposedConversationId, AgentRun run) {
        return protect(() -> {
            if (!actorId.equals(run.actorId()) || run.status() != RunStatus.RUNNING) {
                throw new IllegalArgumentException("actor와 RUNNING run이 일치해야 합니다.");
            }
            long activeLeaseMillis = Duration.between(run.startedAt(), run.deadlineAt()).toMillis();
            List<?> result = redisTemplate.execute(
                    acceptRunScript,
                    List.of(
                            conversationPointerKey(actorId),
                            messagesKey(proposedConversationId),
                            activeRunKey(actorId),
                            runKey(run.runId())
                    ),
                    proposedConversationId.toString(),
                    run.runId().toString(),
                    actorId.toString(),
                    run.question(),
                    Long.toString(run.startedAt().toEpochMilli()),
                    Long.toString(run.deadlineAt().toEpochMilli()),
                    Long.toString(conversationTtl.toMillis()),
                    Long.toString(activeLeaseMillis),
                    MESSAGES_PREFIX
            );
            if (result == null || result.size() != 2) {
                throw new IllegalStateException("accept-run.lua 결과가 올바르지 않습니다.");
            }
            if (asLong(result.get(0)) == 1L) {
                return AcceptRunResult.accepted(UUID.fromString(asString(result.get(1))));
            }
            return AcceptRunResult.runInProgress(UUID.fromString(asString(result.get(1))));
        });
    }

    @Override
    public Optional<ConversationSnapshot> findCurrentConversation(UUID actorId, Instant now) {
        return protect(() -> {
            String pointerKey = conversationPointerKey(actorId);
            Map<String, String> pointer = redisTemplate.<String, String>opsForHash().entries(pointerKey);
            if (pointer.isEmpty()) {
                return Optional.empty();
            }

            UUID conversationId = UUID.fromString(required(pointer, "conversation-id"));
            UUID latestRunId = UUID.fromString(required(pointer, "latest-run-id"));
            Optional<AgentRun> latestRun = findOwnedRunInternal(actorId, latestRunId, now);
            if (latestRun.isEmpty()) {
                return Optional.empty();
            }

            List<String> serializedPairs = redisTemplate.opsForList().range(messagesKey(conversationId), 0, -1);
            List<ChatPair> pairs = new ArrayList<>();
            if (serializedPairs != null) {
                for (String serializedPair : serializedPairs) {
                    pairs.add(objectMapper.readValue(serializedPair, ChatPair.class));
                }
            }

            String active = redisTemplate.opsForValue().get(activeRunKey(actorId));
            UUID activeRunId = active == null ? null : UUID.fromString(active);
            Long ttlMillis = redisTemplate.getExpire(pointerKey, TimeUnit.MILLISECONDS);
            Instant expiresAt = ttlMillis == null || ttlMillis < 0 ? now : now.plusMillis(ttlMillis);
            return Optional.of(new ConversationSnapshot(
                    conversationId,
                    pairs,
                    latestRun.orElseThrow(),
                    activeRunId,
                    expiresAt
            ));
        });
    }

    @Override
    public Optional<AgentRun> findOwnedRun(UUID actorId, UUID runId, Instant now) {
        return protect(() -> findOwnedRunInternal(actorId, runId, now));
    }

    @Override
    public boolean updateStage(UUID runId, RunStage stage, Instant occurredAt) {
        return protect(() -> {
            if (stage == RunStage.DONE) {
                return false;
            }
            Long result = redisTemplate.execute(
                    updateStageScript,
                    List.of(runKey(runId)),
                    stage.name(),
                    Long.toString(occurredAt.toEpochMilli())
            );
            return Long.valueOf(1L).equals(result);
        });
    }

    @Override
    public boolean complete(UUID actorId, UUID runId, ChatPair pair, String answer, Instant completedAt) {
        return protect(() -> {
            Optional<AgentRun> run = readOwnedRun(actorId, runId);
            if (run.isEmpty() || run.orElseThrow().status() != RunStatus.RUNNING) {
                return false;
            }
            if (!pair.assistant().content().equals(answer)) {
                throw new IllegalArgumentException("pair assistant와 terminal answer가 일치해야 합니다.");
            }
            String serializedPair = objectMapper.writeValueAsString(pair);
            Long result = redisTemplate.execute(
                    completeRunScript,
                    List.of(
                            conversationPointerKey(actorId),
                            messagesKey(run.orElseThrow().conversationId()),
                            activeRunKey(actorId),
                            runKey(runId)
                    ),
                    runId.toString(),
                    actorId.toString(),
                    Long.toString(completedAt.toEpochMilli()),
                    answer,
                    Long.toString(completedAt.toEpochMilli()),
                    serializedPair,
                    Integer.toString(maxPairs),
                    Long.toString(conversationTtl.toMillis())
            );
            return Long.valueOf(1L).equals(result);
        });
    }

    @Override
    public boolean fail(UUID actorId, UUID runId, String code, String message, Instant failedAt) {
        return protect(() -> {
            if (readOwnedRun(actorId, runId).isEmpty()) {
                return false;
            }
            Long result = redisTemplate.execute(
                    failRunScript,
                    List.of(activeRunKey(actorId), runKey(runId)),
                    runId.toString(),
                    actorId.toString(),
                    code,
                    message,
                    Long.toString(failedAt.toEpochMilli()),
                    Long.toString(conversationTtl.toMillis())
            );
            return Long.valueOf(1L).equals(result);
        });
    }

    @Override
    public Optional<UUID> cancelCurrentConversation(UUID actorId, Instant cancelledAt) {
        return protect(() -> {
            String cancelledRunId = redisTemplate.execute(
                    cancelConversationScript,
                    List.of(conversationPointerKey(actorId), activeRunKey(actorId)),
                    actorId.toString(),
                    Long.toString(cancelledAt.toEpochMilli()),
                    Long.toString(conversationTtl.toMillis()),
                    RUN_PREFIX,
                    MESSAGES_PREFIX
            );
            if (cancelledRunId == null || cancelledRunId.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(UUID.fromString(cancelledRunId));
        });
    }

    @Override
    public boolean expireStaleRun(UUID actorId, UUID runId, Instant now) {
        return protect(() -> expireStaleRunInternal(actorId, runId, now));
    }

    @Override
    public boolean claimFirstStream(UUID actorId, UUID runId) {
        return protect(() -> {
            Optional<AgentRun> run = readOwnedRun(actorId, runId);
            if (run.isEmpty() || run.orElseThrow().status() != RunStatus.RUNNING) {
                return false;
            }
            Boolean claimed = redisTemplate.<String, String>opsForHash()
                    .putIfAbsent(runKey(runId), "first-stream-attached", "1");
            return Boolean.TRUE.equals(claimed);
        });
    }

    private Optional<AgentRun> findOwnedRunInternal(UUID actorId, UUID runId, Instant now) {
        Optional<AgentRun> run = readOwnedRun(actorId, runId);
        if (run.isPresent()
                && run.orElseThrow().status() == RunStatus.RUNNING
                && now.isAfter(run.orElseThrow().deadlineAt())) {
            expireStaleRunInternal(actorId, runId, now);
            return readOwnedRun(actorId, runId);
        }
        return run;
    }

    private Optional<AgentRun> readOwnedRun(UUID actorId, UUID runId) {
        Map<String, String> fields = redisTemplate.<String, String>opsForHash().entries(runKey(runId));
        if (fields.isEmpty() || !actorId.toString().equals(fields.get("actor-id"))) {
            return Optional.empty();
        }
        return Optional.of(toAgentRun(fields));
    }

    private boolean expireStaleRunInternal(UUID actorId, UUID runId, Instant now) {
        Long result = redisTemplate.execute(
                expireStaleRunScript,
                List.of(activeRunKey(actorId), runKey(runId)),
                actorId.toString(),
                runId.toString(),
                Long.toString(now.toEpochMilli()),
                AiErrorCode.RUN_TIMEOUT.getCode(),
                AiErrorCode.RUN_TIMEOUT.getMessage(),
                Long.toString(conversationTtl.toMillis())
        );
        return Long.valueOf(1L).equals(result);
    }

    private AgentRun toAgentRun(Map<String, String> fields) {
        return AgentRun.restore(
                UUID.fromString(required(fields, "run-id")),
                UUID.fromString(required(fields, "conversation-id")),
                UUID.fromString(required(fields, "actor-id")),
                required(fields, "question"),
                RunStatus.valueOf(required(fields, "status")),
                RunStage.valueOf(required(fields, "stage")),
                instant(fields, "started-at"),
                instant(fields, "deadline-at"),
                nullableInstant(fields.get("completed-at")),
                nullableInstant(fields.get("failed-at")),
                nullableInstant(fields.get("cancelled-at")),
                fields.get("answer"),
                fields.get("error-code"),
                fields.get("error-message")
        );
    }

    private static Instant instant(Map<String, String> fields, String key) {
        return Instant.ofEpochMilli(Long.parseLong(required(fields, key)));
    }

    private static Instant nullableInstant(String value) {
        return value == null ? null : Instant.ofEpochMilli(Long.parseLong(value));
    }

    private static String required(Map<String, String> fields, String key) {
        String value = fields.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Redis run field가 누락됐습니다. field=" + key);
        }
        return value;
    }

    private static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(asString(value));
    }

    private static String asString(Object value) {
        if (value instanceof byte[] bytes) {
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
        return String.valueOf(value);
    }

    private static String conversationPointerKey(UUID actorId) {
        return KEY_PREFIX + "actor:{" + actorId + "}:conversation";
    }

    private static String activeRunKey(UUID actorId) {
        return KEY_PREFIX + "actor:{" + actorId + "}:active-run";
    }

    private static String messagesKey(UUID conversationId) {
        return MESSAGES_PREFIX + "{" + conversationId + "}:messages";
    }

    private static String runKey(UUID runId) {
        return RUN_PREFIX + "{" + runId + "}";
    }

    private <T> T protect(StateOperation<T> operation) {
        try {
            return operation.execute();
        } catch (AiException exception) {
            throw exception;
        } catch (Exception exception) {
            meterRegistry.counter(
                    "ai.redis.errors",
                    "operation", "state",
                    "error_code", AiErrorCode.AI_STATE_UNAVAILABLE.getCode()).increment();
            throw new AiException(AiErrorCode.AI_STATE_UNAVAILABLE, exception);
        }
    }

    @FunctionalInterface
    private interface StateOperation<T> {
        T execute() throws Exception;
    }
}
