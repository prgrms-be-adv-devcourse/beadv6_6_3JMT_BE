package com.prompthub.ai.settlement.infrastructure.persistence.redis;

import com.prompthub.ai.global.config.AiRedisConfig;
import com.prompthub.ai.settlement.domain.repository.SettlementChatStateRepository.AcceptRunResult;
import com.prompthub.ai.settlement.domain.conversation.ChatMessage;
import com.prompthub.ai.settlement.domain.conversation.ChatPair;
import com.prompthub.ai.settlement.domain.conversation.ConversationSnapshot;
import com.prompthub.ai.settlement.domain.run.AgentRun;
import com.prompthub.ai.settlement.domain.run.RunStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class RedisSettlementChatStateRepositoryIntegrationTest {

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine")
            .withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;

    private StringRedisTemplate redisTemplate;
    private RedisSettlementChatStateRepository repository;

    @BeforeEach
    void setUp() {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(
                REDIS.getHost(),
                REDIS.getMappedPort(6379)
        );
        configuration.setDatabase(1);
        connectionFactory = new LettuceConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();

        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        try (RedisConnection connection = redisTemplate.getConnectionFactory().getConnection()) {
            connection.serverCommands().flushDb();
        }

        ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
        AiRedisConfig scripts = new AiRedisConfig();
        repository = new RedisSettlementChatStateRepository(
                redisTemplate,
                objectMapper,
                Duration.ofHours(24),
                20,
                scripts.acceptRunScript(),
                scripts.completeRunScript(),
                scripts.failRunScript(),
                scripts.cancelConversationScript(),
                scripts.expireStaleRunScript(),
                scripts.updateStageScript()
        );
    }

    @AfterAll
    static void tearDown() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void atomicallyAcceptsAndCompletesWhileFencingDuplicateAndLateCallbacks() {
        UUID actorId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        Instant startedAt = Instant.parse("2026-07-22T12:00:00Z");
        AgentRun firstRun = AgentRun.start(
                conversationId,
                actorId,
                "7월 정산을 알려줘",
                startedAt,
                Duration.ofSeconds(90)
        );

        AcceptRunResult accepted = repository.acceptRun(actorId, conversationId, firstRun);
        AgentRun competingRun = AgentRun.start(
                UUID.randomUUID(),
                actorId,
                "6월과 비교해줘",
                startedAt.plusSeconds(1),
                Duration.ofSeconds(90)
        );
        AcceptRunResult rejected = repository.acceptRun(actorId, UUID.randomUUID(), competingRun);

        assertThat(accepted.accepted()).isTrue();
        assertThat(rejected.accepted()).isFalse();
        assertThat(rejected.activeRunId()).contains(firstRun.runId());

        Instant completedAt = startedAt.plusSeconds(10);
        ChatPair pair = new ChatPair(
                ChatMessage.user(firstRun.question(), startedAt),
                ChatMessage.assistant("정산 답변", completedAt)
        );
        assertThat(repository.complete(actorId, firstRun.runId(), pair, "정산 답변", completedAt)).isTrue();
        assertThat(repository.complete(actorId, firstRun.runId(), pair, "중복 답변", completedAt)).isFalse();

        ConversationSnapshot snapshot = repository.findCurrentConversation(actorId, completedAt).orElseThrow();
        assertThat(snapshot.pairs()).containsExactly(pair);
        assertThat(snapshot.latestRun().status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(snapshot.activeRunId()).isNull();

        AgentRun nextRun = AgentRun.start(
                accepted.conversationId(),
                actorId,
                "다음 질문",
                completedAt.plusSeconds(1),
                Duration.ofSeconds(90)
        );
        assertThat(repository.acceptRun(actorId, UUID.randomUUID(), nextRun).accepted()).isTrue();
        assertThat(repository.complete(actorId, firstRun.runId(), pair, "늦은 답변", completedAt.plusSeconds(2)))
                .isFalse();
        assertThat(repository.findOwnedRun(actorId, nextRun.runId(), completedAt.plusSeconds(2)))
                .get()
                .extracting(AgentRun::status)
                .isEqualTo(RunStatus.RUNNING);

        Instant cancelledAt = completedAt.plusSeconds(3);
        assertThat(repository.cancelCurrentConversation(actorId, cancelledAt)).contains(nextRun.runId());
        assertThat(repository.findCurrentConversation(actorId, cancelledAt)).isEmpty();
        assertThat(repository.findOwnedRun(actorId, nextRun.runId(), cancelledAt))
                .get()
                .extracting(AgentRun::status)
                .isEqualTo(RunStatus.CANCELLED);
        assertThat(repository.complete(actorId, nextRun.runId(), pair, "취소 후 답변", cancelledAt)).isFalse();

        assertThat(redisTemplate.getConnectionFactory()).isInstanceOf(LettuceConnectionFactory.class);
        assertThat(((LettuceConnectionFactory) redisTemplate.getConnectionFactory()).getDatabase()).isEqualTo(1);
    }

    @Test
    void activeRun_lease가_먼저_만료돼도_실행은_실패_상태로_완료한다() {
        UUID actorId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        Instant startedAt = Instant.parse("2026-07-22T12:00:00Z");
        AgentRun run = AgentRun.start(
                conversationId,
                actorId,
                "이번 주 정산을 알려줘",
                startedAt,
                Duration.ofSeconds(90)
        );
        assertThat(repository.acceptRun(actorId, conversationId, run).accepted()).isTrue();
        redisTemplate.delete("ai:settlement:actor:{" + actorId + "}:active-run");

        Instant failedAt = startedAt.plusSeconds(1);

        assertThat(repository.fail(actorId, run.runId(), "AI_RUN_TIMEOUT", "실행 시간이 초과됐습니다.", failedAt))
                .isTrue();
        assertThat(repository.findOwnedRun(actorId, run.runId(), failedAt))
                .get()
                .extracting(AgentRun::status)
                .isEqualTo(RunStatus.FAILED);
    }
}
