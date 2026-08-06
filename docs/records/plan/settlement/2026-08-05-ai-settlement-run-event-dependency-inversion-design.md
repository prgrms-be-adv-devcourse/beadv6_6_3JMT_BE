# AI 정산 Run 이벤트 의존성 역전 설계

## 목적

AI Service의 `settlement` bounded context에서 Redis 이벤트 구독 어댑터가
presentation의 `SseEmitterRegistry`와 application의 `SettlementRunTaskRegistry`를 직접 호출하는
구조를 제거한다. Redis와 SSE 사이의 흐름을 application 유스케이스를 중심으로 재구성하되,
REST·Redis·SSE 계약과 실행 동작은 변경하지 않는다.

## 범위

이번 2차 리팩토링의 2A 단계는 Run 이벤트 처리 경계만 다룬다.

- `RedisSettlementRunEventSubscriber`의 직접 의존을 `SettlementRunUseCase` 하나로 축소한다.
- 기존 `SettlementRunUseCase`에 `handleEvent(RunEvent)`를 추가한다.
- `SettlementRunApplicationService`가 SSE 전달과 로컬 실행 취소 순서를 조율한다.
- application이 SSE 구현을 알지 않도록 `SettlementRunEventBroadcaster` 포트를 추가한다.
- `SseEmitterRegistry`가 `SettlementRunEventBroadcaster`를 구현한다.

다음 항목은 이번 범위에서 제외한다.

- Redis Pub/Sub 유실 복구와 이벤트 영속화
- SSE `Last-Event-ID` 기반 재연결·재생
- 다중 Pod 실행 소유권과 취소 전달 정책 변경
- Spring AI `TokenCountEstimator` 의존 위치 변경
- Micrometer 의존성 역전
- `AiSettlementProperties` 책임 분리
- REST 경로·JSON, Redis channel·payload, SSE event 이름·payload 변경
- ArchUnit 도입

## 규칙 적합성

공용 `clean-architecture.md` 규칙에 따라 infrastructure와 presentation은 서로 직접 의존하지 않고
application 포트를 중심으로 연결한다. Redis 메시지 수신은 인바운드 흐름이므로 기존
`SettlementRunUseCase`를 사용하고, SSE 전송은 비영속 아웃바운드 능력이므로
`SettlementRunEventBroadcaster`를 `application/usecase`에 둔다.

Run 이벤트 처리를 별도 1메서드 유스케이스와 서비스로 분리하지 않는다. 같은 `SettlementRun`
책임을 다루는 연산은 기존 `SettlementRunUseCase`와 `SettlementRunApplicationService`에 묶는다는
유스케이스 응집 규칙을 따른다. `Broadcaster`는 `Client`·`Gateway`·`Port` 같은 경계 오분류를 피하고,
특정 전송 기술을 드러내지 않는 능력 이름이다.

## 최종 구조

```text
com.prompthub.ai.settlement
├── application
│   ├── usecase
│   │   ├── SettlementRunUseCase
│   │   ├── SettlementRunEventBroadcaster
│   │   └── SettlementRunEventPublisher
│   └── service/run
│       ├── SettlementRunApplicationService
│       └── SettlementRunTaskRegistry
├── infrastructure
│   └── messaging/redis
│       ├── RedisSettlementRunEventPublisher
│       └── RedisSettlementRunEventSubscriber
└── presentation
    └── sse
        └── SseEmitterRegistry
```

새 프로덕션 타입은 `SettlementRunEventBroadcaster` 하나만 추가한다. 별도의
`HandleSettlementRunEventUseCase`나 `SettlementRunEventApplicationService`는 만들지 않는다.

## 인터페이스와 구현 책임

### SettlementRunUseCase

기존 조회·스트림 선점 연산을 유지하고 Run 이벤트 처리 연산을 추가한다.

```java
public interface SettlementRunUseCase {
    AgentRun getOwnedRun(UUID actorId, UUID runId);

    boolean claimFirstStream(UUID actorId, UUID runId);

    void handleEvent(RunEvent event);
}
```

### SettlementRunEventBroadcaster

현재 Pod에 연결된 Run 이벤트 구독자에게 이벤트를 전달하는 기술 중립 아웃바운드 포트다.

```java
public interface SettlementRunEventBroadcaster {
    void broadcast(RunEvent event);
}
```

### SettlementRunApplicationService

`handleEvent`에서 다음 순서를 조율한다.

1. `SettlementRunEventBroadcaster.broadcast(event)`를 호출한다.
2. 이벤트가 `CANCELLED`이면 `SettlementRunTaskRegistry.cancel(runId)`을 호출한다.

기존 조회, 소유권 확인, 최초 SSE 스트림 선점 동작은 변경하지 않는다.

### RedisSettlementRunEventSubscriber

Redis payload를 `RunEvent`로 역직렬화하고 `SettlementRunUseCase.handleEvent(event)`를 호출한다.
`SseEmitterRegistry`와 `SettlementRunTaskRegistry`를 더 이상 import하거나 주입받지 않는다.

### SseEmitterRegistry

`SettlementRunEventBroadcaster`를 구현한다. 기존 `dispatch(RunEvent)` 동작을
`broadcast(RunEvent)`로 제공하며, SSE event 변환·연결별 전송·terminal 연결 종료 책임을 유지한다.
Controller가 사용하는 연결 등록·snapshot·terminal 전송·heartbeat API도 유지한다.

## 실행 흐름

```text
실행 Pod
  → SettlementRunEventPublisher
  → RedisSettlementRunEventPublisher
  → Redis Pub/Sub

각 AI Pod
  → RedisSettlementRunEventSubscriber
  → SettlementRunUseCase.handleEvent(event)
  → SettlementRunApplicationService
      ├── SettlementRunEventBroadcaster.broadcast(event)
      │   └── SseEmitterRegistry
      └── CANCELLED이면 SettlementRunTaskRegistry.cancel(runId)
```

이벤트를 Redis에 발행하는 `SettlementRunEventPublisher`와 Redis에서 수신한 이벤트를 로컬 SSE
연결에 전달하는 `SettlementRunEventBroadcaster`는 방향과 책임이 다르므로 별도 포트로 유지한다.

## 오류 처리와 동작 불변 조건

- 잘못된 Redis payload는 Subscriber가 기존과 같이 `warn` 로그를 남기고 폐기한다.
- SSE 전송 중 끊어진 연결은 `SseEmitterRegistry`가 해당 세션만 제거한다.
- 한 SSE 연결의 실패가 다른 연결이나 Run 상태를 변경하지 않는다.
- 이벤트 전달 후 취소하는 기존 순서를 유지한다.
- 중복 `CANCELLED` 이벤트는 기존 `FutureTask.cancel()` 동작에 따라 안전하게 처리한다.
- Redis Pub/Sub 유실 시 별도 재생이나 보상 처리를 추가하지 않는다.
- REST 응답, Redis channel과 직렬화 형식, SSE event 이름과 payload는 변경하지 않는다.

## 테스트 전략

구조 변경 전 목표 계약을 테스트로 고정하고 다음 시나리오를 검증한다.

1. `SettlementRunApplicationService`가 일반 이벤트를 broadcaster에 한 번 전달하고 task를 취소하지 않는다.
2. `CANCELLED` 이벤트는 broadcaster 전달 후 `SettlementRunTaskRegistry.cancel()`을 호출한다.
3. `RedisSettlementRunEventSubscriber`가 정상 payload를 역직렬화해
   `SettlementRunUseCase.handleEvent()`에 전달한다.
4. 잘못된 payload는 유스케이스를 호출하지 않는다.
5. `SseEmitterRegistry`의 기존 이벤트별 SSE 변환, delta 거부, terminal 연결 종료 테스트를
   `broadcast()` 계약으로 유지한다.
6. Redis Pub/Sub 통합 테스트에서 발행된 이벤트가 유스케이스까지 전달되는지 확인한다.
7. `./gradlew :ai-service:test`로 AI Service 전체 회귀를 검증한다.
8. `rg`로 `RedisSettlementRunEventSubscriber`의 presentation import와 task registry 직접 의존이
   제거됐는지 확인한다.
9. `git diff --check`로 whitespace 오류를 확인한다.

## 완료 조건

- `RedisSettlementRunEventSubscriber`가 application의 `SettlementRunUseCase`에만 이벤트를 전달한다.
- infrastructure에서 presentation을 직접 import하지 않는다.
- `SettlementRunApplicationService`가 SSE 전달과 취소 순서를 조율한다.
- `SseEmitterRegistry`가 `SettlementRunEventBroadcaster`를 구현한다.
- 기존 외부 계약과 이벤트 처리 순서가 유지된다.
- AI Service 전체 테스트가 통과한다.
- 기존 Settlement Service, User Service 및 1차 AI 패키지 리팩토링 변경을 보존한다.
