# AI 정산 Infrastructure 설정 소유권 리팩토링 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** AI 정산 전용 Redis·실행 설정을 `global/config`에서 settlement infrastructure로 이동하고 messaging·persistence 책임별로 분리한다.

**Architecture:** 기존 `AiRedisConfig`를 Run 이벤트 listener 설정과 대화 상태 Lua Script 설정으로 분리해 각 Redis 어댑터 패키지에 둔다. `AiExecutionConfig`는 `SettlementRunExecutionConfig`로 이름을 바꿔 settlement infrastructure 루트에 두며, 모든 Bean 이름·qualifier·설정 키·실행 정책은 유지한다.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Framework 7, Spring Data Redis 4.1, JUnit 5, AssertJ, Mockito, Testcontainers, Gradle

## Global Constraints

- 변경 범위는 `ai-service/src/main/java/com/prompthub/ai/global/config/{AiRedisConfig,AiExecutionConfig}.java`, `ai-service/src/main/java/com/prompthub/ai/settlement/infrastructure/**`, 대응 테스트와 본 설계·계획 문서로 제한한다.
- `AiSettlementProperties`의 `ai.*` 설정 키와 기본값을 변경하지 않는다.
- `aiSettlementExecutor`, `aiSettlementRedisMessageListenerContainer`와 Redis Script Bean 7개의 이름을 유지한다.
- Redis channel pattern `ai:settlement:events:*`와 `redis/*.lua` resource 경로를 유지한다.
- executor의 pool size·queue capacity·thread prefix·rejection·shutdown 정책을 변경하지 않는다.
- `@EnableScheduling`을 유지한다.
- REST·gRPC·Redis·SSE 외부 계약을 변경하지 않는다.
- `TokenCountEstimator`, Micrometer, feature flag, Redis 유실·SSE 재연결 정책을 변경하지 않는다.
- `infrastructure/execution`이나 범용 `infrastructure/config` 패키지를 추가하지 않는다.
- ArchUnit을 추가하지 않는다.
- 구현은 RED → 최소 구현 GREEN 순서로 진행한다.
- 파일 수정은 `apply_patch`를 사용한다.
- 기존 Settlement Service, User Service와 AI 정산 1차·2A 미커밋 변경을 보존한다.
- stage, commit, push, PR은 별도 사용자 요청 전에는 수행하지 않는다.

---

## 파일 구조와 책임

**Create**

- `ai-service/src/main/java/com/prompthub/ai/settlement/infrastructure/persistence/redis/SettlementChatStateRedisConfig.java`
  - 정산 대화 상태 Lua Script Bean 7개를 구성한다.
- `ai-service/src/main/java/com/prompthub/ai/settlement/infrastructure/messaging/redis/SettlementRunEventRedisConfig.java`
  - 정산 Run 이벤트 listener container를 구성한다.
- `ai-service/src/main/java/com/prompthub/ai/settlement/infrastructure/SettlementRunExecutionConfig.java`
  - scheduling과 `aiSettlementExecutor`를 구성한다.
- `ai-service/src/test/java/com/prompthub/ai/settlement/infrastructure/messaging/redis/SettlementRunEventRedisConfigTest.java`
  - listener container의 connection factory와 구독 설정을 검증한다.
- `ai-service/src/test/java/com/prompthub/ai/settlement/infrastructure/SettlementRunExecutionConfigTest.java`
  - executor Bean 이름과 실행 정책을 검증한다.

**Modify**

- `ai-service/src/test/java/com/prompthub/ai/settlement/infrastructure/persistence/redis/RedisSettlementChatStateRepositoryIntegrationTest.java`
  - Script fixture를 새 persistence config에서 생성한다.

**Delete after replacement**

- `ai-service/src/main/java/com/prompthub/ai/global/config/AiRedisConfig.java`
- `ai-service/src/main/java/com/prompthub/ai/global/config/AiExecutionConfig.java`

---

### Task 1: Redis 상태 저장 Script 설정 분리

**Files:**

- Create: `ai-service/src/main/java/com/prompthub/ai/settlement/infrastructure/persistence/redis/SettlementChatStateRedisConfig.java`
- Modify: `ai-service/src/main/java/com/prompthub/ai/global/config/AiRedisConfig.java`
- Modify: `ai-service/src/test/java/com/prompthub/ai/settlement/infrastructure/persistence/redis/RedisSettlementChatStateRepositoryIntegrationTest.java`

**Interfaces:**

- Consumes: `redis/accept-run.lua`, `complete-run.lua`, `fail-run.lua`, `mark-run-cancelled.lua`, `cleanup-cancelled-conversation.lua`, `expire-stale-run.lua`, `update-stage.lua`
- Produces: 기존 이름과 결과 타입을 유지하는 `DefaultRedisScript` Bean 7개

- [ ] **Step 1: 통합 테스트를 목표 설정 이름으로 먼저 변경**

`RedisSettlementChatStateRepositoryIntegrationTest`의 import와 fixture 타입을 다음과 같이 바꾼다.

```java
import com.prompthub.ai.settlement.infrastructure.persistence.redis.SettlementChatStateRedisConfig;

SettlementChatStateRedisConfig scripts = new SettlementChatStateRedisConfig();
```

기존 `scripts.acceptRunScript()`부터 `scripts.updateStageScript()`까지의 전달 순서와 assertion은 변경하지 않는다.

- [ ] **Step 2: RED 확인**

Run:

```bash
./gradlew :ai-service:test --tests "com.prompthub.ai.settlement.infrastructure.persistence.redis.RedisSettlementChatStateRepositoryIntegrationTest"
```

Expected: `SettlementChatStateRedisConfig`가 없어 `compileTestJava`가 실패한다.

- [ ] **Step 3: persistence Redis 설정 생성**

`SettlementChatStateRedisConfig.java`를 다음 내용으로 만든다.

```java
package com.prompthub.ai.settlement.infrastructure.persistence.redis;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;

@Configuration(proxyBeanMethods = false)
public class SettlementChatStateRedisConfig {

    @Bean("acceptRunScript")
    public DefaultRedisScript<List> acceptRunScript() {
        return script("redis/accept-run.lua", List.class);
    }

    @Bean("completeRunScript")
    public DefaultRedisScript<Long> completeRunScript() {
        return script("redis/complete-run.lua", Long.class);
    }

    @Bean("failRunScript")
    public DefaultRedisScript<Long> failRunScript() {
        return script("redis/fail-run.lua", Long.class);
    }

    @Bean("markRunCancelledScript")
    public DefaultRedisScript<List> markRunCancelledScript() {
        return script("redis/mark-run-cancelled.lua", List.class);
    }

    @Bean("cleanupCancelledConversationScript")
    public DefaultRedisScript<Long> cleanupCancelledConversationScript() {
        return script("redis/cleanup-cancelled-conversation.lua", Long.class);
    }

    @Bean("expireStaleRunScript")
    public DefaultRedisScript<Long> expireStaleRunScript() {
        return script("redis/expire-stale-run.lua", Long.class);
    }

    @Bean("updateStageScript")
    public DefaultRedisScript<Long> updateStageScript() {
        return script("redis/update-stage.lua", Long.class);
    }

    private static <T> DefaultRedisScript<T> script(String path, Class<T> resultType) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(resultType);
        return script;
    }
}
```

동시에 `AiRedisConfig`에서는 위 Script Bean 7개, `script` helper와 불필요해진 import를 제거한다.
Task 2 전까지 이 클래스에는 listener container Bean만 남긴다.

- [ ] **Step 4: GREEN 확인**

Run:

```bash
./gradlew :ai-service:test --tests "com.prompthub.ai.settlement.infrastructure.persistence.redis.RedisSettlementChatStateRepositoryIntegrationTest"
```

Expected: Redis Testcontainers에서 기존 상태 원자성·취소·만료 시나리오가 모두 통과한다.

- [ ] **Step 5: Script Bean 중복 제거 확인**

Run:

```bash
rg -n "acceptRunScript|completeRunScript|failRunScript|markRunCancelledScript|cleanupCancelledConversationScript|expireStaleRunScript|updateStageScript" ai-service/src/main/java/com/prompthub/ai/global/config/AiRedisConfig.java
```

Expected: 결과 없음.

---

### Task 2: Redis Run 이벤트 listener 설정 이동

**Files:**

- Create: `ai-service/src/main/java/com/prompthub/ai/settlement/infrastructure/messaging/redis/SettlementRunEventRedisConfig.java`
- Delete: `ai-service/src/main/java/com/prompthub/ai/global/config/AiRedisConfig.java`
- Test: `ai-service/src/test/java/com/prompthub/ai/settlement/infrastructure/messaging/redis/SettlementRunEventRedisConfigTest.java`

**Interfaces:**

- Consumes: `RedisConnectionFactory`, `RedisSettlementRunEventSubscriber`
- Produces: `RedisMessageListenerContainer aiSettlementRedisMessageListenerContainer(...)`

- [ ] **Step 1: 새 설정의 실패 테스트 작성**

`SettlementRunEventRedisConfigTest.java`를 다음 내용으로 만든다.

```java
package com.prompthub.ai.settlement.infrastructure.messaging.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.Topic;
import org.springframework.test.util.ReflectionTestUtils;

class SettlementRunEventRedisConfigTest {

    @Test
    void configuresSettlementRunEventPatternListener() {
        RedisConnectionFactory connectionFactory = mock(RedisConnectionFactory.class);
        RedisSettlementRunEventSubscriber subscriber =
                mock(RedisSettlementRunEventSubscriber.class);
        SettlementRunEventRedisConfig config = new SettlementRunEventRedisConfig();

        RedisMessageListenerContainer container =
                config.aiSettlementRedisMessageListenerContainer(connectionFactory, subscriber);

        assertThat(container.getConnectionFactory()).isSameAs(connectionFactory);
        Map<MessageListener, Set<Topic>> listenerTopics =
                listenerTopics(container);
        assertThat(listenerTopics).containsKey(subscriber);
        assertThat(listenerTopics.get(subscriber))
                .containsExactly(new PatternTopic("ai:settlement:events:*"));
    }

    @SuppressWarnings("unchecked")
    private Map<MessageListener, Set<Topic>> listenerTopics(
            RedisMessageListenerContainer container
    ) {
        return (Map<MessageListener, Set<Topic>>)
                ReflectionTestUtils.getField(container, "listenerTopics");
    }
}
```

- [ ] **Step 2: RED 확인**

Run:

```bash
./gradlew :ai-service:test --tests "com.prompthub.ai.settlement.infrastructure.messaging.redis.SettlementRunEventRedisConfigTest"
```

Expected: `SettlementRunEventRedisConfig`가 없어 `compileTestJava`가 실패한다.

- [ ] **Step 3: listener 설정 이동 후 이전 클래스 제거**

`SettlementRunEventRedisConfig.java`를 다음 내용으로 만든다.

```java
package com.prompthub.ai.settlement.infrastructure.messaging.redis;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration(proxyBeanMethods = false)
public class SettlementRunEventRedisConfig {

    @Bean
    public RedisMessageListenerContainer aiSettlementRedisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            RedisSettlementRunEventSubscriber subscriber
    ) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(subscriber, new PatternTopic("ai:settlement:events:*"));
        return container;
    }
}
```

listener 설정 이동이 끝나면 `global/config/AiRedisConfig.java`를 삭제한다.

- [ ] **Step 4: GREEN 확인**

Run:

```bash
./gradlew :ai-service:test --tests "com.prompthub.ai.settlement.infrastructure.messaging.redis.SettlementRunEventRedisConfigTest" --tests "com.prompthub.ai.settlement.infrastructure.messaging.redis.RedisSettlementRunEventPubSubIntegrationTest"
```

Expected: 새 설정 단위 테스트와 기존 Redis Pub/Sub 통합 테스트가 통과한다.

- [ ] **Step 5: 이전 Redis config 제거 확인**

Run:

```bash
rg -n "AiRedisConfig|com\.prompthub\.ai\.global\.config\.AiRedisConfig" ai-service/src/main/java ai-service/src/test/java
```

Expected: 결과 없음.

---

### Task 3: 정산 Run 실행 설정 이동

**Files:**

- Create: `ai-service/src/main/java/com/prompthub/ai/settlement/infrastructure/SettlementRunExecutionConfig.java`
- Delete: `ai-service/src/main/java/com/prompthub/ai/global/config/AiExecutionConfig.java`
- Test: `ai-service/src/test/java/com/prompthub/ai/settlement/infrastructure/SettlementRunExecutionConfigTest.java`

**Interfaces:**

- Consumes: `AiSettlementProperties.execution().maxConcurrentRuns()`
- Produces: `ThreadPoolTaskExecutor` Bean named `aiSettlementExecutor`, scheduling 활성화

- [ ] **Step 1: 새 실행 설정의 실패 테스트 작성**

`SettlementRunExecutionConfigTest.java`를 다음 내용으로 만든다.

```java
package com.prompthub.ai.settlement.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.prompthub.ai.global.config.AiSettlementProperties;
import com.prompthub.ai.settlement.AiSettlementTestFixtures;
import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

class SettlementRunExecutionConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(
                    AiSettlementProperties.class,
                    () -> AiSettlementTestFixtures.properties(true))
            .withUserConfiguration(SettlementRunExecutionConfig.class);

    @Test
    void configuresNamedSettlementRunExecutorWithoutQueue() {
        contextRunner.run(context -> {
            assertThat(context).hasBean("aiSettlementExecutor");
            ThreadPoolTaskExecutor executor =
                    context.getBean("aiSettlementExecutor", ThreadPoolTaskExecutor.class);

            assertThat(executor.getCorePoolSize()).isEqualTo(4);
            assertThat(executor.getMaxPoolSize()).isEqualTo(4);
            assertThat(executor.getQueueCapacity()).isZero();
            assertThat(executor.getThreadNamePrefix()).isEqualTo("ai-settlement-run-");
            assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                    .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
            assertThat(ReflectionTestUtils.getField(
                    executor, "waitForTasksToCompleteOnShutdown"))
                    .isEqualTo(false);
        });
    }
}
```

- [ ] **Step 2: RED 확인**

Run:

```bash
./gradlew :ai-service:test --tests "com.prompthub.ai.settlement.infrastructure.SettlementRunExecutionConfigTest"
```

Expected: `SettlementRunExecutionConfig`가 없어 `compileTestJava`가 실패한다.

- [ ] **Step 3: 실행 설정 이동 후 이전 클래스 제거**

`SettlementRunExecutionConfig.java`를 다음 내용으로 만든다.

```java
package com.prompthub.ai.settlement.infrastructure;

import com.prompthub.ai.global.config.AiSettlementProperties;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SettlementRunExecutionConfig {

    @Bean(name = "aiSettlementExecutor")
    public ThreadPoolTaskExecutor aiSettlementExecutor(AiSettlementProperties properties) {
        int maxConcurrentRuns = properties.execution().maxConcurrentRuns();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(maxConcurrentRuns);
        executor.setMaxPoolSize(maxConcurrentRuns);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("ai-settlement-run-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }
}
```

생성이 끝나면 `global/config/AiExecutionConfig.java`를 삭제한다.

- [ ] **Step 4: GREEN 확인**

Run:

```bash
./gradlew :ai-service:test --tests "com.prompthub.ai.settlement.infrastructure.SettlementRunExecutionConfigTest" --tests "com.prompthub.ai.settlement.application.service.conversation.SettlementChatApplicationServiceTest"
```

Expected: 실행 설정 단위 테스트와 executor를 소비하는 application service 테스트가 통과한다.

- [ ] **Step 5: 이전 실행 config 제거 확인**

Run:

```bash
rg -n "AiExecutionConfig|com\.prompthub\.ai\.global\.config\.AiExecutionConfig" ai-service/src/main/java ai-service/src/test/java
```

Expected: 결과 없음.

---

### Task 4: Spring Context와 전체 회귀 검증

**Files:**

- Verify: `ai-service/src/main/java/com/prompthub/ai/global/config/**`
- Verify: `ai-service/src/main/java/com/prompthub/ai/settlement/infrastructure/**`
- Verify: `ai-service/src/test/java/com/prompthub/ai/**`

**Interfaces:**

- Consumes: Task 1~3의 Configuration과 기존 Bean qualifier
- Produces: component scan, Redis 통합과 AI Service 전체 회귀 검증 결과

- [ ] **Step 1: Spring Context 로딩 검증**

Run:

```bash
./gradlew :ai-service:test --tests "com.prompthub.ai.AiServiceApplicationTest"
```

Expected: 이동한 세 Configuration이 component scan되고 중복 Bean 없이 context가 기동된다.

- [ ] **Step 2: 두 Redis 통합 테스트 재실행**

Run:

```bash
./gradlew :ai-service:test --tests "com.prompthub.ai.settlement.infrastructure.persistence.redis.RedisSettlementChatStateRepositoryIntegrationTest" --tests "com.prompthub.ai.settlement.infrastructure.messaging.redis.RedisSettlementRunEventPubSubIntegrationTest"
```

Expected: Lua Script 기반 상태 원자성 시나리오와 Pub/Sub→Application→SSE 흐름이 통과한다.

- [ ] **Step 3: 최종 패키지 구조 검사**

Run:

```bash
rg -n "class (AiRedisConfig|AiExecutionConfig)|import com\.prompthub\.ai\.global\.config\.(AiRedisConfig|AiExecutionConfig)" ai-service/src/main/java ai-service/src/test/java
```

Expected: 결과 없음.

Run:

```bash
rg -n "class SettlementRunEventRedisConfig|class SettlementChatStateRedisConfig|class SettlementRunExecutionConfig|aiSettlementExecutor|ai:settlement:events:\*" ai-service/src/main/java/com/prompthub/ai/settlement
```

Expected: 새 Configuration 3개와 보존된 Bean·channel 계약이 검색된다.

- [ ] **Step 4: AI Service 전체 테스트 재실행**

Run:

```bash
./gradlew :ai-service:test --rerun-tasks
```

Expected: AI Service 전체 테스트가 통과한다. 로컬 Kafka 미기동 경고는 Gradle 실패가 아니며, 종료 코드와 실패 테스트 수로 판단한다.

- [ ] **Step 5: diff와 작업 트리 검증**

Run:

```bash
git diff --check
git diff --cached --check
git status --short
```

Expected:

- 두 diff check가 출력 없이 종료 코드 0이다.
- 기존 Settlement/User/AI 1차·2A 변경과 이번 설정 소유권 변경만 존재한다.
- `ai.inspection`, `ai.recommendation`에는 이번 작업으로 인한 변경이 없다.
- 실행하지 않은 실환경 검증은 성공으로 표현하지 않는다.
