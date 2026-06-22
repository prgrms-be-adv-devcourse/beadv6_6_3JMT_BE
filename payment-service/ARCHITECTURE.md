# payment-service 아키텍처 설계

## 아키텍처 개요

**클린 아키텍처(Clean Architecture, Robert C. Martin)** 를 기반으로 한다.
외부 기술 변경이 도메인 로직에 영향을 주지 않도록 레이어 간 의존 방향을 단방향으로 강제한다.
> 클린 아키텍처는 Ports & Adapters(헥사고날) 패턴과 의존 방향 철학을 공유하지만, `usecase`/`gateway` 용어를 사용하는 것이 클린 아키텍처 고유 명명이다.

```
interfaces ──┐
             ├──▶  application  ──▶  domain
infrastructure ─┘
```

- `domain` — 순수 비즈니스 규칙. 외부 기술 의존 없음
- `application` — 비즈니스 흐름 조율. `domain`만 의존
- `interfaces` — 외부(HTTP, Kafka)에서 앱을 호출하는 Driving Adapter
- `infrastructure` — 앱이 외부(DB, PG사, Kafka)를 호출하는 Driven Adapter

---

## 패키지 구조

```
com.prompthub.paymentservice
│
├── domain
│   ├── model          ← 핵심 도메인 객체(Entity) 및 상태 enum
│   └── event          ← 도메인 이벤트 (결제/환불 상태 변화)
│
├── application
│   ├── usecase        ← UseCase 인터페이스(Input Boundary) + Interactor 구현체
│   ├── gateway
│   │   ├── persistence   ← Repository 인터페이스 (DB 접근 경계)
│   │   ├── external      ← 외부 API Gateway 인터페이스 (예: PaymentGateway)
│   │   └── messaging     ← EventPublisher 인터페이스
│   └── dto
│       ├── command    ← 외부 → application 방향 입력 데이터
│       └── result     ← application → 외부 방향 출력 데이터
│
├── interfaces
│   ├── web            ← HTTP 요청 수신 (Controller, ExceptionHandler)
│   │   └── dto
│   │       ├── request   ← HTTP 요청 DTO
│   │       └── response  ← HTTP 응답 DTO
│   └── messaging      ← Kafka Consumer (메시지 수신)
│
└── infrastructure
    ├── persistence    ← DB 접근. gateway/persistence 구현체 + JPA 설정
    ├── external
    │   └── toss       ← Toss Payments 연동 (ACL, Client, DTO)
    │       └── dto    ← Toss API 응답 역직렬화 DTO
    └── messaging
        └── config     ← Kafka 설정 및 토픽 상수
```

---

## 레이어 상세

### domain

비즈니스 규칙과 도메인 모델만 존재한다. 외부 기술(Spring, JPA, Kafka 등)을 import하지 않는 것이 이상적이나, 현재는 `model`에 JPA Entity를 두는 실용적 타협을 적용하고 있다.

| 패키지 | 내용 | 의존 금지 |
|---|---|---|
| `domain.model` | Payment, Refund Entity, PaymentStatus, RefundStatus enum | 외부 레이어 전체 |
| `domain.event` | 결제/환불 상태 변화를 나타내는 값 객체 (순수 Java) | 외부 레이어 전체 |

### application

비즈니스 흐름을 조율한다. `domain`만 import하며, 외부 기술 구현에 직접 의존하지 않는다.
외부와의 경계는 `usecase`(Input Boundary)와 `gateway`(Output Boundary) 인터페이스를 통해 이루어진다.

| 패키지 | 내용 |
|---|---|
| `application.usecase` | UseCase 인터페이스(Input Boundary) + Interactor 구현체. `interfaces`가 인터페이스를 주입받아 호출 |
| `application.gateway.persistence` | Repository 인터페이스. `infrastructure.persistence`가 구현 |
| `application.gateway.external` | 외부 API Gateway 인터페이스. `infrastructure.external`이 구현 |
| `application.gateway.messaging` | EventPublisher 인터페이스. `infrastructure.messaging`이 구현 |
| `application.dto.command` | 외부 입력을 application으로 전달하는 불변 객체 (record 권장) |
| `application.dto.result` | application의 처리 결과를 외부로 전달하는 불변 객체 (record 권장) |

**의존 규칙**: `application`은 `interfaces`, `infrastructure`를 import하지 않는다.

### interfaces

Driving Adapter. 외부 시스템(HTTP 클라이언트, Kafka 브로커)이 애플리케이션을 호출하는 진입점이다.
`application.usecase`를 주입받아 비즈니스 흐름을 시작한다.

| 패키지 | 내용 |
|---|---|
| `interfaces.web` | REST Controller, ExceptionHandler |
| `interfaces.web.dto.request` | HTTP 요청 DTO. `@Valid`로 입력 유효성 검증 후 `command`로 변환 |
| `interfaces.web.dto.response` | HTTP 응답 DTO. `result`를 클라이언트 표현으로 변환 |
| `interfaces.messaging` | Kafka Consumer. 수신 메시지를 `command`로 변환해 UseCase 호출 |

**의존 규칙**: `infrastructure`를 직접 import하지 않는다.

### infrastructure

Driven Adapter. 애플리케이션이 외부 시스템(DB, PG사 API, Kafka 브로커)을 호출하는 구현체가 위치한다.
`application.gateway.*` 인터페이스를 구현한다.

| 패키지 | 내용 |
|---|---|
| `infrastructure.persistence` | Spring Data JPA Repository, `gateway.persistence` 구현체(Adapter), JPA Auditing 설정 |
| `infrastructure.external.toss` | Toss Payments API Client, `gateway.external` PaymentGateway 구현체 (ACL) |
| `infrastructure.external.toss.dto` | Toss API 응답 역직렬화 DTO. 이 패키지 외부에 노출하지 않는다 |
| `infrastructure.messaging` | `gateway.messaging` EventPublisher 구현체 (Kafka Producer) |
| `infrastructure.messaging.config` | Kafka Producer/Consumer 빈 설정, 토픽 상수 |

**의존 규칙**: `interfaces`를 직접 import하지 않는다.

---

## 의존성 규칙 요약

| 레이어 | import 허용 | import 금지 |
|---|---|---|
| `domain` | 없음 (외부 레이어 전체 금지) | application, interfaces, infrastructure |
| `application` | domain | interfaces, infrastructure |
| `interfaces` | application.usecase, application.dto | infrastructure |
| `infrastructure` | application.gateway.*, application.dto, domain.model | interfaces |

---

## 데이터 흐름 예시 (결제 승인)

```
HTTP POST /payments/confirm
  → interfaces.web.PaymentController
  → application.usecase.ConfirmPaymentUseCase (command 전달)
  → application.usecase.ConfirmPaymentInteractor
      → infrastructure.external.toss.TossPaymentGateway (gateway.external.PaymentGateway)
      → infrastructure.persistence.PaymentRepositoryAdapter (gateway.persistence.PaymentRepository)
      → infrastructure.messaging.PaymentEventPublisher (gateway.messaging.PaymentEventPublisher)
  → application.dto.result.PaymentResult 반환
  → interfaces.web.dto.response.PaymentResponse 변환
  → HTTP 200 응답
```
