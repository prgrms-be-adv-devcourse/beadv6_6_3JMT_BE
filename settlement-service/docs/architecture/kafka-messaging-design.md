# 정산 서비스 Kafka 메시징 설계 (발행)

정산이 Kafka 를 쓰는 곳을 **어떻게 구현하는지** 정한다. **원천 데이터 수신은 gRPC pull 로 이관**됐으므로
(§4 · `trade-offs/order-data-sourcing.md`), 이 문서가 다루는 Kafka 사용은 이제 **발행(지급 완료 알림 —
추후·파이널)** 뿐이다.

- 주고받는 계약(무엇을 주고받는지)은 `integration-catalog.md` 를 본다. 이 문서는 그 계약을
  **클린아키텍처 룰(`.claude/rules/clean-architecture.md`)에 맞춰 어느 계층·패키지에 어떤 코드로 놓을지**를 정한다.
- 원천 수급을 왜 이벤트가 아니라 gRPC pull 로 하는지는 `trade-offs/order-data-sourcing.md`.
- 참고 구현: `order-service`(Transactional Outbox), `payment-service`(AFTER_COMMIT 직접 발행).
  두 서비스의 발행 패턴을 가져오되 **패키지·네이밍은 정산 룰로 번역**한다(§2).

---

## 0. 결론 요약

| 항목 | 결정 |
|------|------|
| 원천 수신(order 결제/환불) | ~~Kafka 컨슈머~~ → **gRPC pull 로 이관**(§4). 정산은 order 이벤트를 구독하지 않는다 |
| 발행(아웃바운드) 포트 | `application/port/SettlementEventPublisher` (비영속 아웃바운드 — 룰 §4) **※ 추후·파이널** |
| 발행 구현(어댑터) | `infrastructure/messaging/kafka/producer/*` **※ 추후·파이널** |
| 발행 전략 | **AFTER_COMMIT 직접 발행을 기본**으로, **정합성이 중요한 흐름만 Transactional Outbox** (하이브리드) |
| 예외 | `infrastructure` 계층이므로 `SettlementException(SettlementErrorCode...)` — 카프카용 ErrorCode 신규 추가 |

> Kafka 는 이제 **밖으로 알림을 내보내는 발행**에만 쓴다. 원천을 **받는** 경로는 gRPC pull 이라
> 컨슈머·수신 인프라가 필요 없다. 발행마저 현재 범위(추후·파이널)라, 지금 당장 붙일 Kafka 코드는 없다.

---

## 1. 참고 패턴 (order/payment 발행에서 가져올 것)

발행 신뢰성 패턴만 개념으로 가져온다. (수신 패턴은 pull 전환으로 불필요해졌다.)

| 요소 | 참고 구현 | 정산에 가져올지 |
|------|----------|----------------|
| AFTER_COMMIT 직접 발행 | payment-service | 기본으로 가져온다(§3-1) |
| Transactional Outbox 폴링(`@Scheduled`) | order-service | 정합성 critical 발행에만(§3-2) |
| 메시지 envelope | `eventId·eventType·version·occurredAt·aggregateId·payload` 래핑 | 발행 envelope 로 가져온다 |
| 신뢰 패키지 제한 | `spring.json.trusted.packages` | 발행 producer 설정에 반영 |

---

## 2. 패키지 배치 — 룰 번역

발행 코드는 정산 클린아키텍처 룰대로 놓는다. (메시징은 `infrastructure/messaging/kafka`, 발행은 `producer`.)

| 역할 | 정산 위치 | 근거(룰) |
|------|-----------|----------|
| 발행 포트 | `application/port/SettlementEventPublisher` | 비영속 아웃바운드는 포트 경유(§4). application 이 Kafka 를 직접 모른다 |
| 발행 어댑터 | `infrastructure/messaging/kafka/producer/*` | 메시징(Kafka) 어댑터는 바깥 계층(clean-architecture §2) |
| 발행 envelope/페이로드 DTO | `application/event/*` | 메시지 계약 DTO 위치(clean-architecture §2) |
| Outbox 엔티티(도입 시) | `domain/model/OutboxEvent` | order 선례. Outbox 채택 흐름에만(§3) |

### 권장 패키지 트리 (발행 도입 시)

```
application/
  port/
    SettlementEventPublisher.java          ← 아웃바운드 포트(인터페이스)
  event/
    SettlementEventEnvelope.java           ← 발행 envelope
    PayoutCompletedMessage.java            ← settlement.payout.completed 페이로드

infrastructure/messaging/kafka/
  config/
    KafkaConfig.java                       ← Producer Factory(발행용)
  producer/
    KafkaSettlementEventPublisher.java     ← SettlementEventPublisher 구현(직접 발행)
    OutboxRelay.java                       ← (정합성 critical 흐름 도입 시) @Scheduled 폴링 발행
```

> 원천 수신은 gRPC pull 로 이관됐으므로 `consumer/*` 리스너·수신 config 는 두지 않는다.
> 기존 `infrastructure/messaging/kafka/consumer/order/*`(`OrderEventConsumer` 등)는 **제거 대상**이다(§4).

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
    **단, 발행은 추후(파이널 단계) 도입 — 현재 구현 범위 아님**(`integration-catalog.md` §3).

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
새로 만들지 않고 재사용한다. (같은 스케줄 인프라를 정산 배치 트리거와 공유한다.)

---

## 4. 원천 수신 — gRPC pull 로 이관 (Kafka 수신 폐기)

정산의 원천 데이터(결제·환불)는 원래 이 문서에서 `OrderEventListener` 로 `order.paid`/`order.refunded`
를 구독해 받는 것으로 설계했었다. **이 수신 경로는 gRPC pull 로 전환됐다.**

- **전환 이유:** 배치를 k8s CronJob 으로 분리하면서 상시 컨슈머를 없애고, 배치 시점에 order 를
  gRPC 로 당겨온다. Kafka 의 멱등·순서·DLT·백필·order outbox 전제가 모두 사라진다.
  (근거·설계는 `trade-offs/order-data-sourcing.md`.)
- **바뀐 흐름:** 배치 Step 0 가 `OrderSettlementQueryPort.getSettleableLines(period)` 로 그 기간의
  결제·환불 라인을 당겨 `settlement_source_line` 에 멱등 적재한다. `paidAt`/`refundedAt` 시각 기준으로
  결제·환불을 가르며, 멱등키는 `orderProductId | 상태`(PAID/REFUND) 다. (계약은 `integration-catalog.md` §1.)
- **제거 대상:** `infrastructure/messaging/kafka/consumer/order/*`(`OrderEventConsumer`·`OrderEventType`),
  Kafka **consumer** 설정, `application/event` 의 order 이벤트 수신 DTO, `settlement.kafka.listener.order.*`.
- **신규(수신 대체):** `infrastructure/client/order/`(gRPC 어댑터), `application/port/OrderSettlementQueryPort`.
  이 경로는 **메시징이 아니라 동기 조회**라 `infrastructure/client` 에 둔다(`internal-sync-transport.md`).

> 즉 정산의 인바운드 원천은 더 이상 Kafka 가 아니다. Kafka 는 아웃바운드 발행(추후)에만 남는다.

---

## 5. 예외 처리 (발행)

- 발행 어댑터는 `infrastructure` 계층이므로 `SettlementException(ErrorCode[, cause])` 를 쓴다
  (`controller-exception.md` §2-1/§2-4). 참고 서비스 예외 타입을 그대로 가져오지 않는다.
- `SettlementErrorCode` 에 카프카 발행용 코드를 **신규 추가**한다(예시):

```java
SETTLEMENT_EVENT_PUBLISH_FAILED("S-0xx", "정산 이벤트 발행에 실패했습니다.", HttpStatus.INTERNAL_SERVER_ERROR),
```

- persistence·드라이버·스택트레이스 등 내부 상세를 메시지로 노출하지 않는다(`controller-exception.md` §2-3).
- 빈 catch 금지, 와일드카드 import 금지(`code-style.md`).

---

## 6. 인프라 (발행 도입 시 추가)

| 항목 | 현재 | 추가할 것 |
|------|------|----------|
| `build.gradle` | `spring-boot-starter-kafka` (수신용으로 추가돼 있었음) | 발행만 쓰면 유지, 수신 설정은 제거 |
| `application.yml` | `spring.kafka.consumer.*`·`listener.*` | **consumer/listener 설정 제거**, 발행 시 `producer.*` 만 |

`application.yml` 발행용 예시(producer 만):

```yaml
spring:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JacksonJsonSerializer
```

> 원천 수신을 pull 로 뺐으므로 consumer(`group-id`·`ack-mode`·ErrorHandlingDeserializer·trusted.packages)
> 설정은 필요 없다. 발행을 실제 붙이는 파이널 단계 전까지는 Kafka 설정 자체가 없어도 된다.

---

## 7. 구현 순서 (제안)

1. **원천 수신 정리(선행)** — `consumer/order/*`·Kafka consumer 설정·order 이벤트 DTO 제거,
   gRPC pull(`infrastructure/client/order` + 배치 Step 0)로 대체. (`order-data-sourcing.md`)
2. **(추후·파이널) 발행** — `SettlementEventPublisher` 포트 + AFTER_COMMIT 어댑터로 `settlement.payout.completed`.
3. (후속) 지급 명령 이벤트 정식화 시 — Outbox 도입.

> 변경분은 PR 전 `verify-rules` 로 7종 룰 검증을 거친다.
</content>
