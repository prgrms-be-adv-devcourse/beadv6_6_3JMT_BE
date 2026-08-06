# AI 정산 패키지 아키텍처 리팩토링 설계

## 목적

AI Service의 `settlement` bounded context를 팀 공용 클린 아키텍처 컨벤션에 맞게 정리한다.
이번 단계는 패키지 위치와 경계 네이밍만 변경하며 REST·gRPC·Redis 계약과 실행 동작은 변경하지 않는다.

## 아키텍처 기준

AI Service는 `settlement`, `inspection`, `recommendation`이라는 서로 다른 bounded context를 가진다.
따라서 서비스 전체를 하나의 계층 우선 패키지로 합치지 않고 각 bounded context를 아키텍처 기준 패키지로 사용한다.

```text
com.prompthub.ai
├── settlement
│   ├── presentation
│   ├── application
│   ├── domain
│   └── infrastructure
├── inspection
│   ├── presentation
│   ├── application
│   ├── domain
│   └── infrastructure
├── recommendation
│   ├── presentation
│   ├── application
│   ├── domain
│   └── infrastructure
└── global
```

이번 작업은 `com.prompthub.ai.settlement`만 변경한다. `inspection`과 `recommendation`의 현재 코드는 이동하거나 수정하지 않는다.

## 최종 패키지 구조

```text
com.prompthub.ai.settlement
├── presentation
│   ├── controller
│   │   ├── SettlementChatController
│   │   └── SettlementRunEventController
│   ├── dto
│   │   ├── request
│   │   │   └── CreateSettlementChatMessageRequest
│   │   └── response
│   │       ├── AcceptedRunResponse
│   │       ├── AiApiResponse
│   │       └── ConversationResponse
│   └── sse
│       ├── SseEmitterRegistry
│       └── SseHeartbeatScheduler
├── application
│   ├── usecase
│   │   ├── SettlementChatUseCase
│   │   ├── SettlementRunUseCase
│   │   └── SettlementRunEventPublisher
│   ├── service
│   │   ├── conversation
│   │   └── run
│   ├── client
│   │   └── user
│   │       └── SellerSettlementAnalysisClient
│   └── gateway
│       └── external
│           └── SettlementAgentGateway
├── domain
│   ├── model
│   │   ├── conversation
│   │   └── run
│   ├── event
│   │   └── RunEvent
│   ├── exception
│   └── repository
└── infrastructure
    ├── grpc
    │   └── client
    │       └── user
    │           ├── SellerSettlementAnalysisGrpcClientAdapter
    │           └── SellerSettlementAnalysisGrpcClientConfig
    ├── external
    │   └── openai
    │       ├── SpringAiSettlementAgentAdapter
    │       ├── FinalAnswerPolicy
    │       ├── OpenAiCallRetryExecutor
    │       ├── SellerSettlementAnalysisTools
    │       ├── SettlementPromptFactory
    │       └── ToolExecutionGuard
    ├── persistence
    │   └── redis
    ├── messaging
    │   └── redis
    └── web
        ├── AiSettlementFeatureInterceptor
        └── AiSettlementWebMvcConfig
```

역할이 하나뿐인 `config`, `interceptor`, `rest` 하위 패키지는 만들지 않는다. 테스트 패키지는 대상 프로덕션 패키지를 그대로 따른다.

## 경계와 네이밍 변경

| 현재 | 변경 후 | 이유 |
| --- | --- | --- |
| `application.port.SellerSettlementAnalysisQuery` | `application.client.user.SellerSettlementAnalysisClient` | 내부 User Service 동기 호출 포트는 `Client` 사용 |
| `infrastructure.client.user.SellerSettlementQueryClient` | `infrastructure.grpc.client.user.SellerSettlementAnalysisGrpcClientAdapter` | gRPC 구현 기술과 Adapter 역할 명시 |
| `infrastructure.client.user.config.UserGrpcClientConfig` | `infrastructure.grpc.client.user.SellerSettlementAnalysisGrpcClientConfig` | 호출 대상과 기능을 이름에 명시하고 단일 config 패키지 제거 |
| `application.port.SettlementAgent` | `application.gateway.external.SettlementAgentGateway` | 시스템 밖 OpenAI 호출 포트는 `Gateway` 사용 |
| `infrastructure.client.openai.SpringAiSettlementAgent` | `infrastructure.external.openai.SpringAiSettlementAgentAdapter` | 제3자 OpenAI 어댑터임을 명시 |
| `application.port.SettlementRunEventPublisher` | `application.usecase.SettlementRunEventPublisher` | 내부 메시징 인프라 포트는 공용 규칙에 따라 `usecase` 배치 |
| `application.event.RunEvent` | `domain.event.RunEvent` | 실행 상태를 표현하는 기술 중립 이벤트 모델 |
| `domain.conversation`, `domain.run` | `domain.model.conversation`, `domain.model.run` | 도메인 모델 표준 위치 적용 |
| `presentation.rest` | `presentation.controller`와 `presentation.dto.request/response` | HTTP 진입점과 DTO 역할 분리 |
| `presentation.sse.SettlementRunEventController` | `presentation.controller.SettlementRunEventController` | 모든 HTTP/SSE Controller를 controller 패키지에 통합 |
| `infrastructure.web.config`, `infrastructure.web.interceptor` | `infrastructure.web` | 클래스 하나짜리 역할 패키지 제거 |

인터페이스의 메서드 시그니처와 중첩 record 계약은 유지한다. 프로덕션 클래스 이름이 바뀌면 해당 테스트 클래스 이름도 동일한 어근으로 변경한다.

## 의존성 및 실행 흐름

이번 리팩토링 이후에도 실행 흐름은 동일하다.

```text
HTTP Controller
  → SettlementChatUseCase / SettlementRunUseCase
  → Application Service
  → SettlementAgentGateway
      ← SpringAiSettlementAgentAdapter
          → SellerSettlementAnalysisClient
              ← SellerSettlementAnalysisGrpcClientAdapter

Application Service
  → SettlementRunEventPublisher
      ← RedisSettlementRunEventPublisher
```

Spring Bean 이름 또는 타입 기반 주입이 클래스 이름 변경으로 깨지지 않도록 모든 생성자와 테스트 import를 함께 갱신한다.

## 동작 불변 조건

- REST 경로, 요청·응답 JSON, HTTP 상태 코드를 변경하지 않는다.
- gRPC proto, 메서드, metadata, deadline, 내부 인증 토큰 전달 방식을 변경하지 않는다.
- OpenAI prompt, tool schema, tool 최대 라운드, retry, 최종 답변 정책을 변경하지 않는다.
- Redis key, TTL, 직렬화 형식, Pub/Sub channel을 변경하지 않는다.
- SSE event 이름과 payload를 변경하지 않는다.
- 실행 제한, timeout, 취소, fencing 및 terminal 상태 전이를 변경하지 않는다.
- 설정 key와 기본값을 변경하지 않는다.

## 검증 전략

패키지 이동은 기존 동작 테스트를 새 패키지로 먼저 이동해 컴파일 실패를 확인한 뒤 프로덕션 코드를 이동하는 방식으로 검증한다.
ArchUnit은 이번 범위에서 사용하지 않는다.

1. 변경 대상 테스트의 package/import/class name을 목표 구조로 변경한다.
2. 대상 테스트를 실행해 새 프로덕션 타입이 없어 컴파일 실패하는지 확인한다.
3. 프로덕션 파일을 이동하고 package/import/class name을 갱신한다.
4. 대상 테스트를 다시 실행한다.
5. `./gradlew :ai-service:test`로 AI Service 전체 회귀를 검증한다.
6. 이전 패키지명과 이전 클래스명을 `rg`로 검색해 잔존 참조가 없는지 확인한다.
7. `git diff --check`와 최종 `git status --short`로 다른 작업 영역을 침범하지 않았는지 확인한다.

## 이번 단계에서 제외하는 구조 개선

다음 항목은 단순 이동이 아니라 책임과 실행 순서를 재설계해야 하므로 2차 리팩토링으로 분리한다.

- `RedisSettlementRunEventSubscriber`가 presentation의 `SseEmitterRegistry`를 직접 호출하는 의존성
- application의 Spring AI `TokenCountEstimator` 직접 의존성
- application의 Micrometer `MeterRegistry` 직접 의존성
- `AiSettlementProperties`에 OpenAI, gRPC, SSE, application 정책이 함께 들어 있는 설정 응집도 문제
- Redis Pub/Sub 유실, SSE 재연결 및 다중 Pod 취소 전달에 대한 운영 정책 변경

1차 작업 완료 후 각 항목의 대안, 영향 범위, 테스트 전략과 권장 순서를 별도 계획으로 제시한다.

## 완료 조건

- `com.prompthub.ai.settlement`가 승인된 최종 패키지 구조와 일치한다.
- 이전 `application.port`, `infrastructure.client`, `presentation.rest`, `domain.conversation`, `domain.run` 참조가 남지 않는다.
- AI 정산의 공개 계약과 실행 동작이 변경되지 않는다.
- AI Service 전체 테스트가 통과한다.
- 기존 Settlement Service와 User Service의 미커밋 변경은 보존된다.
- 커밋, push, PR은 별도 사용자 요청 전에는 수행하지 않는다.
