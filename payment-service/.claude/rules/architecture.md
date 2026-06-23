payment-service 클린 아키텍처 규칙. 코드 배치·레이어·패키지·의존 방향 작업 시 따른다.

> 현재 초기 단계: 엔티티/리포지토리/설정 뼈대만 존재하고, 대부분 패키지는 `.gitkeep` 자리만 잡혀 있다.
> 아래 패키지 구조는 목표 구조이자 **새 코드의 배치 계약**이다.

## 아키텍처 개요

**클린 아키텍처(Robert C. Martin)** 기반. 외부 기술 변경이 도메인 로직에 영향을 주지 않도록 레이어 간 의존 방향을 단방향으로 강제한다. 헥사고날의 `port`/`adapter` 대신 클린 아키텍처 용어 `usecase`/`gateway`를 쓴다.

```
interfaces ─┐
            ├─▶ application ─▶ domain
infrastructure ─┘
```

- `domain` — 순수 비즈니스 규칙. 외부 기술 의존 없음
- `application` — 비즈니스 흐름 조율. `domain`만 의존
- `interfaces` — 외부(HTTP, Kafka)에서 앱을 호출하는 Driving Adapter
- `infrastructure` — 앱이 외부(DB, PG사, Kafka)를 호출하는 Driven Adapter

## 패키지 구조

```
com.prompthub.paymentservice
├── domain
│   ├── model          ← 핵심 도메인 객체(Entity) 및 상태 enum
│   └── event          ← 도메인 이벤트 (결제/환불 상태 변화, 순수 Java)
├── application
│   ├── usecase        ← UseCase 인터페이스(Input Boundary) + Interactor 구현체
│   ├── gateway
│   │   ├── persistence   ← Repository 인터페이스 (DB 접근 경계)
│   │   └── external      ← 외부 API Gateway 인터페이스 (예: PaymentGateway)
│   └── dto
│       ├── command    ← 외부 → application 입력 (record 권장)
│       └── result     ← application → 외부 출력 (record 권장)
├── interfaces
│   ├── web            ← REST Controller, ExceptionHandler
│   │   └── dto
│   │       ├── request   ← HTTP 요청 DTO (@Valid 검증)
│   │       └── response  ← HTTP 응답 DTO
│   └── messaging      ← Kafka Consumer (메시지 수신)
└── infrastructure
    ├── persistence    ← Spring Data JPA Repo, gateway.persistence 구현체, JPA Auditing 설정
    ├── external
    │   └── toss       ← Toss Payments 연동 (ACL, Client)
    │       └── dto    ← Toss API 응답 역직렬화 DTO (패키지 외부 노출 금지)
    └── messaging      ← 이벤트 발행 구현체 (@TransactionalEventListener), Kafka 설정
        └── config     ← Kafka Producer/Consumer 빈 설정, 토픽 상수
```

## 레이어별 핵심 규칙

트리 주석에 없는 아키텍처 제약만 기재한다. 패키지별 상세 역할은 위 패키지 구조 참조.

- **domain.model**: 외부 레이어 전체 의존 금지. 현재 `model`에 JPA Entity를 두는 실용적 타협을 적용 — 더 확대하지 않는다.
- **application.usecase**: Input Boundary. `interfaces`가 주입받아 UseCase 인터페이스를 호출하고, 같은 패키지의 Interactor 구현체가 처리한다.
- **application.gateway.***: `infrastructure`가 구현하는 출력 경계 인터페이스(DIP). application 레이어는 인터페이스에만 의존하며, 구현체를 직접 참조하지 않는다. (DB·외부 API 경계 적용; 이벤트 발행은 아래 별도 규칙 참조)
- **이벤트 발행**: Interactor는 `ApplicationEventPublisher.publishEvent(도메인이벤트)`로 Spring 내부 이벤트를 발행한다. `infrastructure.messaging`의 구현체가 `@TransactionalEventListener(phase = AFTER_COMMIT)`으로 구독해 Kafka로 전달한다. `ApplicationEventPublisher`는 `spring-context` 추상 인터페이스이므로 application 레이어 허용. AFTER_COMMIT이 핵심 — 트랜잭션 롤백 시 Kafka 메시지 발행이 차단된다.
- **interfaces.web**: request DTO → `command` 변환 후 UseCase 호출, `result` → response DTO 변환. 요청 유효성 검증 규칙은 `api-error-handling.md` 참조.
- **interfaces.messaging**: 수신 메시지를 `command`로 변환해 UseCase 호출.

## 의존성 규칙 요약

| 레이어 | import 허용 | import 금지 |
|---|---|---|
| `domain` | 없음 | application, interfaces, infrastructure |
| `application` | domain, `ApplicationEventPublisher`(이벤트 발행 전용) | interfaces, infrastructure |
| `interfaces` | application.usecase, application.dto | infrastructure |
| `infrastructure` | application.gateway.*, application.dto, domain.model | interfaces |

## 데이터 흐름 예시 (결제 승인)

```
HTTP POST /payments/confirm
  → interfaces.web.PaymentController
  → application.usecase.ConfirmPaymentUseCase (command 전달)
  → application.usecase.ConfirmPaymentInteractor
      → infrastructure.external.toss.TossPaymentGateway (gateway.external.PaymentGateway)
      → infrastructure.persistence.PaymentRepositoryAdapter (gateway.persistence.PaymentRepository)
      → ApplicationEventPublisher.publishEvent(PaymentApprovedEvent)  ← Spring 내부 이벤트
            ↓ [트랜잭션 커밋 후, @TransactionalEventListener AFTER_COMMIT]
        infrastructure.messaging.KafkaPaymentEventPublisher
            → KafkaTemplate.send("payment.approved", orderId, PaymentApprovedMessage)
  → application.dto.result.PaymentResult 반환
  → interfaces.web.dto.response.PaymentResponse 변환
  → HTTP 200 응답
```
