# AI 정산 Infrastructure 설정 소유권 리팩토링 설계

## 목적

AI Service의 `global/config`에 있는 정산 전용 Redis·실행 설정을 `settlement` bounded context로
이동한다. 설정 클래스를 실제 기술 책임을 구현하는 어댑터 옆에 배치해 소유권을 분명히 하되,
Spring Bean 이름·설정 키·Redis 계약·실행 동작은 변경하지 않는다.

## 현재 문제

`AiRedisConfig`와 `AiExecutionConfig`는 이름과 위치상 AI Service 전체 공통 설정처럼 보이지만 실제로는
AI 정산 기능만 구성한다.

- `AiRedisConfig`
  - 정산 Run 이벤트용 Redis Pub/Sub listener container를 생성한다.
  - 정산 대화 상태 저장소용 Lua Script Bean 7개를 생성한다.
- `AiExecutionConfig`
  - 정산 Run 비동기 실행기 `aiSettlementExecutor`를 생성한다.
  - 정산 SSE heartbeat에 필요한 scheduling을 활성화한다.

특히 `AiRedisConfig`에는 messaging과 persistence 설정이 함께 있어 하나의 기술 책임으로 응집되지 않는다.
공용 클린 아키텍처 규칙상 `global`은 여러 기능이 공유하는 횡단 관심사에 사용하고, 특정 bounded context의
기술 설정은 해당 context의 infrastructure에 두는 것이 적합하다.

## 범위

이번 리팩토링은 다음 설정 소유권 이동만 수행한다.

- `AiRedisConfig`를 messaging 설정과 persistence 설정으로 분리한다.
- `AiExecutionConfig`를 정산 infrastructure로 이동하고 정산 Run 책임이 드러나는 이름으로 바꾼다.
- 이동한 클래스의 package와 이를 직접 참조하는 테스트 import를 갱신한다.

다음 항목은 제외한다.

- `AiSettlementProperties` 분리 또는 설정 키 변경
- application 계층의 Micrometer 의존성 역전
- `SettlementConversationHistorySelector` 이동
- feature flag 중복 검사 정리
- Redis Pub/Sub 유실 복구와 SSE 재연결 정책
- executor 동시성·queue·shutdown 정책 변경
- Lua Script 내용과 Redis key·TTL 변경
- ArchUnit 도입

## 최종 구조

```text
com.prompthub.ai
├── global/config
│   ├── AiAgentConfig
│   └── AiSettlementProperties
└── settlement/infrastructure
    ├── SettlementRunExecutionConfig
    ├── messaging/redis
    │   ├── SettlementRunEventRedisConfig
    │   ├── RedisSettlementRunEventPublisher
    │   └── RedisSettlementRunEventSubscriber
    └── persistence/redis
        ├── SettlementChatStateRedisConfig
        └── RedisSettlementChatStateRepository
```

단일 클래스만 담는 `infrastructure/execution`이나 범용 `infrastructure/config` 패키지는 만들지 않는다.
실행 설정은 `settlement/infrastructure`에 평평하게 두고, Redis 설정은 이미 관련 타입이 모인
messaging·persistence의 `redis` 패키지에 각각 둔다.

## 클래스별 책임

### SettlementRunEventRedisConfig

위치: `settlement/infrastructure/messaging/redis`

정산 Run 이벤트 구독에 필요한 `RedisMessageListenerContainer`만 구성한다.

```java
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

Bean method 이름과 pattern topic을 유지한다.

### SettlementChatStateRedisConfig

위치: `settlement/infrastructure/persistence/redis`

`RedisSettlementChatStateRepository`가 사용하는 Lua Script Bean 7개만 구성한다.

- `acceptRunScript`
- `completeRunScript`
- `failRunScript`
- `markRunCancelledScript`
- `cleanupCancelledConversationScript`
- `expireStaleRunScript`
- `updateStageScript`

각 `@Bean` 이름, `RedisScript` 결과 타입과 `redis/*.lua` resource 경로를 유지한다. Script 생성 helper는
이 설정 클래스 내부의 private static 메서드로 유지한다.

### SettlementRunExecutionConfig

위치: `settlement/infrastructure`

```java
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SettlementRunExecutionConfig {

    @Bean(name = "aiSettlementExecutor")
    public ThreadPoolTaskExecutor aiSettlementExecutor(AiSettlementProperties properties) {
        // 기존 설정 유지
    }
}
```

AI Service에서 `@Scheduled`를 사용하는 타입은 정산의 `SseHeartbeatScheduler` 하나뿐이므로 scheduling
활성화도 정산 실행 설정이 소유한다.

## 설정 로딩과 의존 흐름

`AiServiceApplication`은 `com.prompthub.ai` 하위를 component scan하므로 이동한 세 `@Configuration`
클래스는 별도 `@Import` 없이 자동 등록된다.

```text
AiServiceApplication component scan
  ├── SettlementRunExecutionConfig
  │     └── aiSettlementExecutor
  ├── SettlementRunEventRedisConfig
  │     └── aiSettlementRedisMessageListenerContainer
  └── SettlementChatStateRedisConfig
        └── Redis Script Bean 7개
```

`SettlementChatApplicationService`의 `@Qualifier("aiSettlementExecutor")`와
`RedisSettlementChatStateRepository`의 Script qualifier는 변경하지 않는다.

## 동작 불변 조건

- `aiSettlementExecutor` Bean 이름을 유지한다.
- executor의 core/max pool size는 `ai.execution.max-concurrent-runs` 값을 사용한다.
- executor queue capacity는 0을 유지한다.
- thread name prefix는 `ai-settlement-run-`을 유지한다.
- rejected execution handler는 `AbortPolicy`를 유지한다.
- shutdown 시 작업 완료를 기다리지 않는 정책을 유지한다.
- `@EnableScheduling`을 유지해 SSE heartbeat가 계속 실행되도록 한다.
- `aiSettlementRedisMessageListenerContainer` Bean method 이름을 유지한다.
- Redis channel pattern `ai:settlement:events:*`를 유지한다.
- Lua Script Bean 이름·결과 타입·resource 경로를 유지한다.
- `AiSettlementProperties`의 `ai.*` 설정 키와 기본값을 변경하지 않는다.
- REST·gRPC·Redis·SSE 외부 계약을 변경하지 않는다.

## 테스트 전략

패키지 이동과 책임 분리는 기존 동작을 고정한 테스트를 목표 타입 기준으로 먼저 작성해 RED를 확인한 뒤
최소 설정 구현으로 GREEN을 만든다.

1. `SettlementRunExecutionConfigTest`에서 `aiSettlementExecutor` Bean 이름과 pool·queue·thread·shutdown
   정책을 검증한다.
2. `SettlementRunEventRedisConfigTest`에서 listener container가 주어진 connection factory를 사용하도록
   구성되는지 검증한다.
3. `RedisSettlementChatStateRepositoryIntegrationTest`가 `SettlementChatStateRedisConfig`의 Script Bean을
   사용하도록 import와 fixture를 바꾸고 기존 원자성 시나리오를 그대로 실행한다.
4. Redis Pub/Sub 통합 테스트로 이벤트 발행·구독 흐름이 유지되는지 확인한다.
5. `AiServiceApplicationTest`로 이동한 Configuration이 component scan에서 정상 로딩되는지 확인한다.
6. `./gradlew :ai-service:test --rerun-tasks`로 AI Service 전체 회귀를 검증한다.
7. `rg`로 `AiRedisConfig`, `AiExecutionConfig`와 이전 package import가 남지 않았는지 확인한다.
8. `git diff --check`, `git diff --cached --check`와 최종 status로 기존 다른 서비스 변경이 보존됐는지
   확인한다.

## 완료 조건

- `global/config`에 `AiRedisConfig`와 `AiExecutionConfig`가 남지 않는다.
- Redis event·state와 Run execution 설정이 승인된 settlement infrastructure 위치에 존재한다.
- messaging 설정과 persistence Script 설정이 별도 클래스에 응집된다.
- 기존 Bean 이름, qualifier, 설정 키와 실행 동작이 유지된다.
- Redis 상태 저장소·Pub/Sub 통합 테스트와 AI Service 전체 테스트가 통과한다.
- 기존 Settlement Service, User Service 및 AI 정산 1차·2A 변경을 보존한다.
- stage, commit, push, PR은 별도 사용자 요청 전에는 수행하지 않는다.
