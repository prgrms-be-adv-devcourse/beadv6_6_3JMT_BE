payment-service 클린 아키텍처 규칙. 코드 배치·레이어·패키지·의존 방향 작업 시 따른다.

> 아래 패키지 구조는 **새 코드의 배치 계약**이다. 새 클래스를 추가할 때 반드시 이 구조를 따른다.

## 아키텍처 개요

**클린 아키텍처(Robert C. Martin)** 기반. 외부 기술 변경이 도메인 로직에 영향을 주지 않도록 레이어 간 의존 방향을 단방향으로 강제한다. 헥사고날의 `port`/`adapter` 대신 클린 아키텍처 용어 `usecase`/`gateway`를 쓴다.

```
presentation ─┐
              ├─▶ application ─▶ domain
infrastructure ─┘
```

- `domain` — 순수 비즈니스 규칙. 외부 기술 의존 없음
- `application` — 비즈니스 흐름 조율. `domain`만 의존
- `presentation` — HTTP 입력 어댑터(REST Controller, ExceptionHandler)
- `infrastructure` — 앱이 외부(DB, PG사, Kafka)를 호출하는 Driven Adapter

## 패키지 구조

```
com.prompthub.paymentservice
├── domain
│   ├── model          ← 핵심 도메인 객체(Entity) 및 상태 enum
│   ├── event          ← 도메인 이벤트 (결제/환불 상태 변화, 순수 Java)
│   └── repository     ← Repository 인터페이스 (도메인이 정의하는 영속성 계약)
├── application
│   ├── usecase        ← UseCase 인터페이스(Input Boundary)
│   ├── service        ← UseCase 구현체 (비즈니스 흐름 조율)
│   ├── gateway
│   │   └── external      ← 외부 API Gateway 인터페이스 (예: PaymentGateway)
│   └── dto
│       ├── command    ← 외부 → application 입력 (record 권장)
│       └── result     ← application → 외부 출력 (record 권장)
├── presentation       ← REST Controller, ExceptionHandler
│   └── dto
│       ├── request    ← HTTP 요청 DTO (@Valid 검증)
│       └── response   ← HTTP 응답 DTO
└── infrastructure
    ├── persistence    ← Spring Data JPA Repo, domain.repository 구현체, JPA Auditing 설정
    ├── external
    │   └── toss       ← Toss Payments 연동 (ACL, Client)
    │       └── dto    ← Toss API 응답 역직렬화 DTO (패키지 외부 노출 금지)
    ├── scheduling     ← @Scheduled 주기 재처리 (환불 retry 등)
    └── messaging      ← 이벤트 발행 구현체 (@TransactionalEventListener), Kafka 설정
        └── config     ← Kafka Producer/Consumer 빈 설정, 토픽 상수
```

## 레이어별 핵심 규칙

트리 주석에 없는 아키텍처 제약만 기재한다. 패키지별 상세 역할은 위 패키지 구조 참조.

- **domain.model**: 외부 레이어 전체 의존 금지. 단, 아래 두 가지는 실용적 타협으로 허용한다. 그 외는 확대하지 않는다.
  - JPA 어노테이션(`@Entity`, `@Column` 등): 영속성 매핑 목적
  - SLF4J 로깅(`@Slf4j`, `log.debug/info` 등): 상태 전이 추적 목적
- **domain.repository**: Repository 인터페이스. 도메인이 영속성에 요구하는 계약을 정의한다. `infrastructure`가 구현하며, application 레이어는 이 인터페이스에만 의존한다.
- **application.usecase**: Input Boundary. UseCase 인터페이스만 위치한다. `presentation`은 이 인터페이스만 의존한다.
- **application.service**: UseCase 인터페이스 구현체. Spring `@Service` 빈으로 등록되며 비즈니스 흐름을 조율한다.
- **application.gateway.external**: 외부 API Gateway 출력 경계 인터페이스(DIP). `infrastructure`가 구현하며, application 레이어는 인터페이스에만 의존한다.
- **예외 패키지**: 도메인 불변 위반은 `domain.exception`, API 에러 코드(HTTP 상태·코드 매핑)는 `application.exception`. common-module의 `ErrorCode`를 구현하는 enum은 `application.exception`에 위치한다.
- **이벤트 발행**: Service 구현체는 `ApplicationEventPublisher.publishEvent(도메인이벤트)`로 Spring 내부 이벤트를 발행한다. `infrastructure.messaging`의 구현체가 `@TransactionalEventListener(phase = AFTER_COMMIT)`으로 구독해 Kafka로 전달한다. `ApplicationEventPublisher`는 `spring-context` 추상 인터페이스이므로 application 레이어 허용. AFTER_COMMIT이 핵심 — 트랜잭션 롤백 시 Kafka 메시지 발행이 차단된다.
- **스케줄러 예외**: `@Scheduled` 메서드 내부에서는 중첩 `@TransactionalEventListener` 제한(Spring Boot 4.1)으로 Spring 내부 이벤트를 쓰지 않는다. 대신 `TransactionSynchronizationManager.registerSynchronization().afterCommit()`에서 `KafkaPaymentEventPublisher`를 직접 호출한다.
- **presentation**: request DTO → `command` 변환 후 UseCase 호출, `result` → response DTO 변환. 요청 유효성 검증 규칙은 `api-error-handling.md` 참조.

## 의존성 규칙 요약

| 레이어 | import 허용 | import 금지 |
|---|---|---|
| `domain` | 없음 | application, presentation, infrastructure |
| `application` | domain, `ApplicationEventPublisher`(이벤트 발행 전용) | presentation, infrastructure |
| `presentation` | application.usecase, application.dto | infrastructure |
| `infrastructure` | application.gateway.external, application.dto, domain.* | presentation |

## 데이터 흐름 예시 (결제 승인)

```
HTTP POST /payments/confirm
  → presentation.PaymentController
  → application.usecase.ConfirmPaymentUseCase (command 전달)
  → application.service.ConfirmPaymentService
      → infrastructure.external.toss.TossPaymentGateway (gateway.external.PaymentGateway)
      → infrastructure.persistence.PaymentRepositoryAdapter (domain.repository.PaymentRepository)
      → ApplicationEventPublisher.publishEvent(PaymentApprovedEvent)  ← Spring 내부 이벤트
            ↓ [트랜잭션 커밋 후, @TransactionalEventListener AFTER_COMMIT]
        infrastructure.messaging.KafkaPaymentEventPublisher
            → KafkaTemplate.send("payment.approved", orderId, PaymentApprovedMessage)
  → application.dto.result.PaymentResult 반환
  → interfaces.web.dto.response.PaymentResponse 변환
  → HTTP 200 응답
```
