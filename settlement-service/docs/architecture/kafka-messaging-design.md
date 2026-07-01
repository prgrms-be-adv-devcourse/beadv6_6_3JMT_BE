# 정산 서비스 Kafka 메시징 설계 (수신 · 발행)

MSA 내부 통신을 Kafka 로 한다. 이 문서는 **"무엇을 주고받는지"가 아니라 "어떻게 구현하는지"**를 정한다.

- 주고받는 이벤트 목록(토픽·페이로드·멱등키)은 `event-catalog.md` 를 본다. 이 문서는 그 카탈로그를
  **클린아키텍처 룰(`.claude/rules/clean-architecture.md`)에 맞춰 어느 계층·패키지에 어떤 코드로 놓을지**를 정한다.
- 참고 구현: `order-service`(Transactional Outbox + `@KafkaListener`), `payment-service`(AFTER_COMMIT 직접 발행).
  두 서비스의 패턴을 가져오되 **패키지·네이밍은 정산 룰로 번역**한다(아래 §2 충돌표).

---

## 0. 결론 요약

| 항목 | 결정 |
|------|------|
| 발행(아웃바운드) 포트 | `application/port/SettlementEventPublisher` (비영속 아웃바운드 — 룰 §4) **※ 추후·파이널** |
| 발행 구현(어댑터) | `infrastructure/event/...` **※ 추후·파이널** |
| 수신(인바운드) 어댑터 | `infrastructure/event/listener/*` (`@KafkaListener`, 얇게 → usecase 호출) |
| 발행 전략 | **AFTER_COMMIT 직접 발행을 기본**으로, **정합성이 중요한 흐름만 Transactional Outbox** (하이브리드) |
| 멱등 | 소스 라인(order.*)은 `orderProductId`+상태 단위, 그 외 수신(product/seller)은 `eventId` 기반 (§4·§4-1) |
| 예외 | `infrastructure` 계층이므로 `SettlementException(SettlementErrorCode...)` — 카프카용 ErrorCode 신규 추가 |

---

## 1. 참고 패턴 (order/payment 에서 가져올 것)

`order-service` 에서 검증된 인프라 설정을 개념만 가져온다.

| 요소 | order-service 구현 | 정산에 가져올지 |
|------|-------------------|----------------|
| Consumer 커밋 | `ack-mode: manual`, `Acknowledgment.acknowledge()` 수동 커밋 | 가져온다 |
| 역직렬화 안전장치 | `ErrorHandlingDeserializer` 위임 | 가져온다 |
| 재시도·DLT | `DefaultErrorHandler` + `FixedBackOff(1s, 3회)` → `<topic>.DLT` | 가져온다 |
| 메시지 envelope | `eventId·eventType·version·occurredAt·aggregateId·payload` 래핑, `eventType` 으로 분기 | 가져온다 |
| 신뢰 패키지 | `spring.json.trusted.packages` 제한 | 가져온다(정산 패키지로) |
| 발행 | Outbox 폴링(`@Scheduled` 5초) | **선택적** — §3 참고 |

---

## 2. 패키지 배치 — order-service 와의 충돌표

order-service 코드를 그대로 복사하면 정산 룰을 위반한다. 아래대로 **번역**한다.

| order-service 위치 | → 정산 위치 | 근거(룰) |
|--------------------|-------------|----------|
| `infra.messaging.kafka` (패키지명 `infra`) | `infrastructure.event` | 정산은 `infrastructure` 풀네임 + 메시징은 `infrastructure/event`(§4) |
| Producer 가 `KafkaTemplate` 직접 호출 | 포트 `application/port/SettlementEventPublisher` + 어댑터 `infrastructure/event` | 비영속 아웃바운드는 포트 경유(§4). application 이 직접 Kafka 를 모름 |
| `@KafkaListener` Consumer | `infrastructure/event/listener/*` (인바운드 어댑터) | 수신 어댑터는 바깥 계층. **얇게 두고 usecase 호출**(배치 §5 "흐름 제어만"의 메시징판) |
| `application/event` 이벤트 DTO | `infrastructure/event/message/*` | 메시지 포맷(envelope)은 기술 세부사항 |
| `domain/model/OutboxEvent` | `domain/model/OutboxEvent` (도입 시) | order 선례 따름. 단 Outbox 채택 흐름에만(§3) |

### 권장 패키지 트리

```
application/
  port/
    SettlementEventPublisher.java          ← 아웃바운드 포트(인터페이스)
  usecase/
    RecordSettlementSourceUseCase.java     ← 수신 처리 인바운드 포트(주문 확정 → 소스 라인 적재)
  service/
    RecordSettlementSourceApplicationService.java  ← 위 usecase 구현(묶음 펼침·멱등·트랜잭션 경계)

infrastructure/event/
  config/
    KafkaConfig.java                       ← Producer/Consumer Factory, ErrorHandler, DLT
  listener/
    OrderEventListener.java                ← @KafkaListener(order.paid/order.refunded) → usecase
  publisher/
    KafkaSettlementEventPublisher.java     ← SettlementEventPublisher 구현(직접 발행)
  message/
    SettlementEventEnvelope.java           ← 발행 envelope
    PayoutCompletedMessage.java            ← settlement.payout.completed 페이로드
  outbox/                                  ← (정합성 critical 흐름 도입 시에만)
    OutboxRelay.java                       ← @Scheduled 폴링 발행
```

> 정산이 받는 이벤트는 `order.paid` / `order.refunded` 뿐이다(`event-catalog.md` §1). 판매자 정보·상품 수는
> 이벤트가 아니라 **동기 조회**로 가져오므로(같은 문서 §2) 별도 리스너를 두지 않는다.
> 기존 `infrastructure/event/ProductPromptEventListener.java` 골격은 **제거 대상**이다.

---

## 3. 발행 전략 — 하이브리드 (AFTER_COMMIT 기본 + 정합성 critical 만 Outbox)

발행마다 신뢰성 요구가 다르다. **단순 알림은 가볍게, 돈이 걸린 명령은 무겁게.**

### 3-1. 기본: AFTER_COMMIT 직접 발행

```
도메인 상태 전이 → ApplicationEvent 등록 → 트랜잭션 커밋 → @TransactionalEventListener(AFTER_COMMIT) → publisher.publish()
```

- DB 커밋 성공 후에만 발행하므로 "DB 롤백됐는데 이벤트만 나가는" 사고가 없다.
- 단점: 커밋과 발행 사이에 프로세스가 죽으면 **이벤트 유실** 가능(at-most-once).
- **적용 대상 — 유실돼도 치명적이지 않은 알림성 이벤트.**
  - `settlement.payout.completed` (지급 완료 → User 에 입금 알림). 트리거: `Settlement.payout(paidAt)` 직후.
    **단, 발행은 추후(파이널 단계) 도입 — 현재 구현 범위 아님**(`event-catalog.md` §2).

### 3-2. 예외: Transactional Outbox

```
도메인 상태 전이 + OutboxEvent 저장 (같은 트랜잭션) → OutboxRelay(@Scheduled) 폴링 → Kafka 발행 → PUBLISHED 마킹 (실패 시 재시도/DLT)
```

- 비즈니스 변경과 이벤트 적재가 **한 트랜잭션**이라 유실이 없다(at-least-once + 멱등으로 정확히 한 번 효과).
- 단점: Outbox 테이블·릴레이·스케줄러 추가 비용, 발행 지연(폴링 주기).
- **적용 대상 — 유실되면 돈/정합성이 깨지는 흐름.**
  - 향후 `Settlement.requestPayout()` 으로 **PG/User 에 보내는 "지급 명령"**. 이 이벤트가 유실되면
    승인된 정산이 영영 지급되지 않는다 → 반드시 Outbox.
  - (현재 카탈로그엔 미정의. 지급 명령 이벤트를 정식화할 때 Outbox 로 구현한다.)

### 3-3. 판단 기준 (한 줄 규칙)

> **"유실되면 돈이 안 나가거나 장부가 틀어지는가?" → 예면 Outbox, 아니면 AFTER_COMMIT.**

`@EnableScheduling` 은 이미 `global/config/SchedulingConfig` 에 있으므로, Outbox 도입 시 릴레이용 스케줄 설정을
새로 만들지 않고 재사용한다.

---

## 4. 수신(인바운드) 흐름

```
OrderEventListener(@KafkaListener: order.paid/order.refunded, manual ack)
  → eventType 분기
  → RecordSettlementSourceUseCase.record(message)       ← application (트랜잭션 경계·멱등)
      → orderProducts 펼침(주문 단위 묶음 → 라인 N개)
      → SettlementSourceLine.paid()/refunded()          ← domain (항목마다)
      → SettlementSourceRepository.save(line)            ← orderProductId+상태 단위 멱등
  → acknowledgment.acknowledge()                         ← 처리 성공 후에만 커밋
```

- **리스너는 얇게**: 역직렬화 + 분기 + ack 만. 비즈니스는 usecase 에 위임(룰 §5 원칙).
- **묶음 펼침**: `order.paid`/`order.refunded` 는 주문 단위 묶음이라, usecase 가 `orderProducts` 를
  펼쳐 항목마다 소스 라인을 적재한다. 한 주문 안 일부 항목만 실패해도 재처리가 멱등하도록 항목 단위로 처리한다.
- **멱등**: 라인 멱등 단위는 **`orderProductId` + 상태(PAID/REFUND)** 다. 동일 항목 재수신 시 중복 적재를
  막는다(`event-catalog.md` §1-1 구현 메모의 멱등키 컬럼 확정과 연동). 중복은 리스너/usecase 에서 잡아
  **로그 후 ack**(재전송 중단) — 빈 catch 금지(`code-style.md`), 반드시 로깅.
- **실패**: 처리 중 예외는 다시 던져 `DefaultErrorHandler` 가 재시도 → 초과 시 `<topic>.DLT` 로 보낸다.

### 4-1. ⚠ 선행 과제 — Order 측 신규 발행

정산 소스 인입 방식은 **Order 의 `order.paid` / `order.refunded` 주문 단위 묶음 이벤트 구독**으로 확정했다
(`event-catalog.md` §1-1·§3). Payment 의 `payment.approved` 는 주문/결제 단위라 상품·판매자 분해가 안 돼
직접 구독하지 않는다. 따라서 구현 착수 전 Order 팀과 다음을 맞춰야 한다.

1. **Order 측 신규 발행** — Order 는 현재 outbound 이벤트가 없다. 주문상품을 `PAID`/`REFUNDED` 로 전이시킨
   직후 `orderProducts`(각 `orderProductId`·`sellerId`·`productId`·`amount`)를 묶어 발행하는 작업이 필요하다.
2. **기존 폴링 폐기** — 정산 배치의 `GET /internal/orders/paid` 폴링은 위 이벤트로 대체·폐기한다.
3. **멱등키 컬럼 확정** — 묶음이라 라인 멱등 단위가 `orderProductId` + 상태다.
   `SettlementSourceLine.event_id`(단일 unique)에 무엇을 넣을지 확정한다.

> 토픽 네이밍 확인: 카탈로그는 `order.paid`/`order.refunded`(dot) 표기다. order-service 실제 구현이
> 묶음 토픽 + `eventType` 분기 방식(예: `order-events`)이면, 구독 대상 토픽명을 발행처와 맞춰 확정한다.

---

## 5. 예외 처리

- 발행/수신 어댑터는 `infrastructure` 계층이므로 `SettlementException(ErrorCode[, cause])` 를 쓴다
  (`controller-exception.md` §2-1/§2-4). `OrderException`(order 코드) 을 그대로 가져오지 않는다.
- `SettlementErrorCode` 에 카프카용 코드를 **신규 추가**한다(예시):

```java
SETTLEMENT_EVENT_DESERIALIZE_FAILED("S-0xx", "정산 이벤트 메시지 역직렬화에 실패했습니다.", HttpStatus.INTERNAL_SERVER_ERROR),
SETTLEMENT_EVENT_PUBLISH_FAILED   ("S-0xx", "정산 이벤트 발행에 실패했습니다.",        HttpStatus.INTERNAL_SERVER_ERROR),
```

- persistence·드라이버·스택트레이스 등 내부 상세를 메시지로 노출하지 않는다(`controller-exception.md` §2-3).
- 빈 catch 금지, 와일드카드 import 금지(`code-style.md`).

---

## 6. 누락 인프라 (구현 시 추가)

| 항목 | 현재 | 추가할 것 |
|------|------|----------|
| `build.gradle` | `spring-boot-starter-kafka` 없음 | `implementation 'org.springframework.boot:spring-boot-starter-kafka'` |
| `application.yml` | `spring.kafka.*` 없음 | bootstrap-servers, `group-id: settlement-service`, `ack-mode: manual`, ErrorHandlingDeserializer, `spring.json.trusted.packages: com.prompthub.settlement.*` |

`application.yml` 예시(order-service 설정을 정산 group-id 로 번역):

```yaml
spring:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JacksonJsonSerializer
    consumer:
      group-id: settlement-service
      auto-offset-reset: earliest
      enable-auto-commit: false
      key-deserializer: org.springframework.kafka.support.serializer.ErrorHandlingDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.ErrorHandlingDeserializer
      properties:
        spring.deserializer.key.delegate.class: org.apache.kafka.common.serialization.StringDeserializer
        spring.deserializer.value.delegate.class: org.springframework.kafka.support.serializer.JacksonJsonDeserializer
        spring.json.trusted.packages: com.prompthub.settlement.*
    listener:
      ack-mode: manual
```

---

## 7. 구현 순서 (제안)

1. 인프라 토대 — `build.gradle` 의존성 + `application.yml` + `infrastructure/event/config/KafkaConfig`.
2. **수신 먼저** — `OrderEventListener`(order.paid/refunded) → `RecordSettlementSourceUseCase` →
   `orderProducts` 펼침 → `SettlementSourceLine` 적재. (단, §4-1 Order 측 신규 발행 협의 선행)
3. **(추후·파이널) 발행** — `SettlementEventPublisher` 포트 + AFTER_COMMIT 어댑터로 `settlement.payout.completed`.
4. (후속) 지급 명령 이벤트 정식화 시 — Outbox 도입.

> 변경분은 PR 전 `verify-rules` 로 7종 룰 검증을 거친다.
