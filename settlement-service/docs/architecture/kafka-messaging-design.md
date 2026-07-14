# 정산 서비스 Kafka 메시징 설계 (발행)

정산이 Kafka 를 쓰는 곳을 **어떻게 구현하는지** 정한다. **원천 데이터 수신은 gRPC pull 로 이관**됐으므로
(§4 · `trade-offs/order-data-sourcing.md`), 이 문서가 다루는 Kafka 사용은 이제 **발행뿐**이다. 발행은 두 갈래다.

- **`SETTLEMENT_CREATED` (Transactional Outbox 구현, #301)** — 배치가 정산을 계산·생성할 때 셀러 정산
  (user-service `sellersettlement`) seed 용으로 발행한다. `settlement-events` 토픽, 배치 Step flush.
- **`settlement.payout.completed` (추후·파이널)** — 지급 완료 알림. 아직 미구현.

> 실제 구현/비활성/대기 현황은 `settlement-internal-comm-topology.md` 를, 발행 이벤트 계약은
> `integration-catalog.md` §3 을 본다.

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
| 발행(아웃바운드) 포트 | `application/port/SettlementEventPublisher` (비영속 아웃바운드 — 룰 §4) **✅ 구현됨** |
| 발행 구현(어댑터) | `infrastructure/messaging/kafka/producer/KafkaSettlementEventPublisher` **✅ 구현됨** |
| 발행 이벤트 | `SETTLEMENT_CREATED`(구현) / `settlement.payout.completed`(추후) |
| 발행 전략 | **AFTER_COMMIT 직접 발행을 기본**으로, **정합성이 중요한 흐름만 Transactional Outbox** (하이브리드). `SETTLEMENT_CREATED`는 같은 트랜잭션에 적재하고 배치 시작·마지막 Step에서 flush한다(§3-2) |
| 예외 | `infrastructure` 계층이므로 `SettlementException(SettlementErrorCode...)` — 카프카용 ErrorCode 신규 추가 |

> Kafka 는 이제 **밖으로 알림을 내보내는 발행**에만 쓴다. 원천을 **받는** 경로는 gRPC pull 이다.
> `SETTLEMENT_CREATED`는 Transactional Outbox로 구현됐고, 지급 완료 발행만 추후(파이널)로 남았다.

---

## 1. 참고 패턴 (order/payment 발행에서 가져올 것)

발행 신뢰성 패턴만 개념으로 가져온다. (수신 패턴은 pull 전환으로 불필요해졌다.)

| 요소 | 참고 구현 | 정산에 가져올지 |
|------|----------|----------------|
| AFTER_COMMIT 직접 발행 | payment-service | 기본으로 가져와 알림성 발행에 적용(§3-1) |
| Transactional Outbox (적재 + 릴레이) | order-service | 정합성 critical 발행에만. 단 릴레이는 order 의 `@Scheduled` 폴링이 아니라 정산 CronJob 에 맞춰 **배치 스텝 flush** 로 번역(§3-2) |
| 메시지 envelope | common `EventMessage<T>`(`eventId·eventType·occurredAt·aggregateType·aggregateId·payload`) | 개별 envelope 를 새로 만들지 않고 공통 래퍼를 그대로 쓴다(`common-kafka-event-message.md`) |
| 신뢰 패키지 제한 | `spring.json.trusted.packages` | 발행 producer 설정에 반영 |

---

## 2. 패키지 배치 — 룰 번역

발행 코드는 정산 클린아키텍처 룰대로 놓는다. (메시징은 `infrastructure/messaging/kafka`, 발행은 `producer`.)

| 역할 | 정산 위치 | 근거(룰) |
|------|-----------|----------|
| 발행 포트 | `application/port/SettlementEventPublisher` | 비영속 아웃바운드는 포트 경유(§4). application 이 Kafka 를 직접 모른다 |
| 발행 어댑터 | `infrastructure/messaging/kafka/producer/*` | 메시징(Kafka) 어댑터는 바깥 계층(clean-architecture §2) |
| 발행 envelope/페이로드 DTO | `application/event/*` | 메시지 계약 DTO 위치(clean-architecture §2) |
| Outbox 엔티티 | `domain/model/OutboxEvent` | `PENDING`·`PUBLISHED`·`FAILED` 상태와 재시도 횟수를 소유한다(§3) |

### 실제 패키지 트리 (✅ = 구현됨)

```
application/
  port/
    SettlementEventPublisher.java          ← 저장된 원문 JSON 발행 포트             ✅
    OutboxEventAppender.java               ← 정산 트랜잭션 내 Outbox 적재 포트       ✅
  service/
    OutboxApplicationService.java          ← cursor 기반 시작/마지막 flush          ✅
    OutboxEventPublishService.java         ← 이벤트별 REQUIRES_NEW 발행·상태 전이   ✅
  event/
    SettlementCreatedPayload.java          ← SETTLEMENT_CREATED 페이로드(~Payload)  ✅
    SettlementEventType.java               ← 도메인 EventType enum(code()=name())   ✅
    PayoutCompletedPayload.java            ← settlement.payout.completed 페이로드    (추후)

infrastructure/messaging/kafka/
  config/
    KafkaConfig.java                       ← DLT Object + Outbox String producer     ✅
  producer/
    KafkaSettlementEventPublisher.java     ← 저장 JSON 발행 후 broker ack 대기       ✅

infrastructure/persistence/outbox/
    JsonOutboxEventAppender.java            ← EventMessage 1회 직렬화·저장             ✅
    OutboxEventRepositoryAdapter.java       ← keyset 후보 조회                        ✅

infrastructure/batch/                       ← Outbox flush 는 상시 릴레이가 아니라 배치 스텝 (정산은 순수 CronJob)
  tasklet/
    RetryPendingOutboxTasklet.java          ← requestedAt 이전 PENDING 재시도          ✅
    FlushCurrentBatchOutboxTasklet.java     ← 현재 settlementBatchId PENDING flush    ✅
    RedriveOutboxTasklet.java               ← 지정 FAILED eventId 재처리              ✅
  config/
    OutboxRedriveJobConfig.java             ← redriveOutboxStep 전용 Job              ✅
```

> 발행 envelope 는 별도 타입을 두지 않는다. common `EventMessage<T>` 를 Outbox 적재 시 한 번 만들고
> 완성된 JSON을 보존한다. 재시도와 redrive는 새 envelope를 만들지 않아 `eventId`가 바뀌지 않는다.

> 원천 수신은 gRPC pull 로 이관됐으므로 `consumer/*` 리스너·수신 config 는 두지 않는다.
> 기존 `infrastructure/messaging/kafka/consumer/order/*`(`OrderEventConsumer` 등)는 **제거 대상**이다(§4).

---

## 3. 발행 전략 — 하이브리드 (AFTER_COMMIT 기본 + 정합성 critical 만 Outbox)

발행마다 신뢰성 요구가 다르다. **단순 알림은 가볍게, 정합성이 걸린 건 무겁게.**

정산은 **순수 CronJob** 이다 — 배치 Job 이 끝나면 파드가 내려가고 상시 프로세스가 없다
(`trade-offs/seller-settlement-separation.md`). 그래서 Outbox 를 쓰더라도 order-service 식
`@Scheduled` 상시 릴레이는 둘 수 없다. **릴레이를 배치 스텝으로 대체**한다(§3-2). 트리거가 스프링
`@Scheduled` 든 k8s CronJob 이든 이 전략은 같다 — 릴레이가 상시 프로세스가 아니라 배치 흐름 안의 스텝이면 된다.

### 3-1. 기본: AFTER_COMMIT 직접 발행

```
도메인 상태 전이 → ApplicationEvent 등록 → 트랜잭션 커밋 → @TransactionalEventListener(AFTER_COMMIT) → publisher.publish()
```

- DB 커밋 성공 후에만 발행하므로 "DB 롤백됐는데 이벤트만 나가는" 사고가 없다.
- 단점: 커밋과 발행 사이에 프로세스가 죽으면 **이벤트 유실** 가능(at-most-once).
- **적용 대상 — 유실돼도 배치 재실행/멱등으로 흡수되는 알림성 이벤트.**
  - `settlement.payout.completed` (지급 완료 → User 에 입금 알림). 트리거: `Settlement.payout(paidAt)` 직후.
    **단, 발행은 추후(파이널 단계) 도입 — 현재 구현 범위 아님**(`integration-catalog.md` §3).

> **`SETTLEMENT_CREATED`(셀러 정산 seed)는 #301에서 AFTER_COMMIT 직접 발행을 제거하고 Outbox 배치
> flush로 전환했다(§3-2).** 이 이벤트가 유실되면 `seller_settlement` 초기행이 생성되지 않아 해당 셀러
> 정산이 조회·지급 흐름에 아예 나타나지 않으므로 Outbox 대상이다
> (`trade-offs/seller-settlement-separation.md` "seed 경로",
> `superpowers/specs/2026-07-08-seller-settlement-event-publish-design.md` §6). 컨슈머 멱등(`settlementId`
> 유니크)은 **중복**만 막고 **유실**은 못 막으므로, at-least-once 를 배치 flush 로 맞춘다.

### 3-2. 예외: Transactional Outbox (릴레이 = 배치 스텝 flush)

```
[적재] Settlement 저장 + SourceLine 연결 + OutboxEvent(PENDING) 저장 (같은 트랜잭션)
   ↓
[시작] requestedAt 이전 PENDING 1회 시도
   ↓
[계산] 원천 pull → 배치 생성 → Settlement별 Outbox 적재 → SettlementBatch 완료
   ↓
[마지막] 현재 settlementBatchId의 PENDING 1회 시도
```

- 비즈니스 변경과 이벤트 적재가 **한 트랜잭션**이라 유실이 없다(at-least-once + 컨슈머 멱등으로 정확히 한 번 효과).
- **릴레이는 `@Scheduled` 상시 폴링이 아니라 배치 스텝이다.** 시작 Step은 `requestedAt` 이전 PENDING만,
  마지막 Step은 현재 `settlementBatchId`의 PENDING만 조회한다. 같은 Job 안에서 한 이벤트를 시작·마지막
  두 번 시도하지 않는다.
- 이벤트 한 건의 broker ack 대기와 상태 변경은 `REQUIRES_NEW`로 격리한다. Kafka 실패는 해당 이벤트에
  기록하고 다음 후보를 계속 처리하지만, DB·낙관적 잠금 오류는 Job 실패로 전파한다.
- 발행 실패 1~2회는 `PENDING`, 3회는 `FAILED`다. `FAILED`는 월 배치가 자동 재시도하지 않으며,
  운영자가 `outboxRedriveJob(eventId)`을 실행할 때만 새 재시도 주기로 되돌려 동일 JSON을 즉시 발행한다.
- 단점: Outbox 테이블 + flush 스텝 추가 비용, **발행 지연이 배치 주기에 묶인다**(상시 릴레이보다 느림).
  정산은 월 마감 도메인이라 이 지연을 수용한다.
- **적용 대상 — 유실되면 정합성이 깨지는 흐름.**
  - **`SETTLEMENT_CREATED`(셀러 정산 seed).** 유실 시 셀러 정산 누락(§3-1 콜아웃).
    #301에서 AFTER_COMMIT → 배치 flush Outbox로 전환했다.
  - 향후 `Settlement.requestPayout()` 의 **지급 명령**(PG/User 로). 유실되면 승인된 정산이 영영 지급되지
    않는다. (현재 카탈로그 미정의 — 정식화 시 같은 배치 flush Outbox 로 구현한다.)

### 3-3. 판단 기준 (한 줄 규칙)

> **"유실되면 돈이 안 나가거나 장부·정산이 틀어지는가?" → 예면 Outbox, 아니면 AFTER_COMMIT.**
> **릴레이 방식은 런타임이 정한다 — 정산은 CronJob 이라 항상 배치 스텝 flush.**

`@EnableScheduling`(`global/config/SchedulingConfig`)은 로컬·단일 인스턴스에서 배치를 주기 트리거하는
용도다. **CronJob 배포에서는 배치 트리거 자체가 k8s CronJob 으로 넘어가므로 상시 스케줄러가 없다.**
따라서 Outbox 릴레이를 상시 스케줄러에 얹지 않고 배치 흐름(`order pull → 계산 → 완료 → flush`) 안의
스텝으로 둔다.

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

> 즉 정산의 인바운드 원천은 더 이상 Kafka 가 아니다. Kafka 는 아웃바운드 발행(`SETTLEMENT_CREATED` 구현,
> 지급 완료는 추후)에만 남는다.

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

## 6. 인프라 (현재 구성)

| 항목 | 현재 상태 |
|------|----------|
| `build.gradle` | `spring-boot-starter-kafka` |
| `KafkaConfig`(코드) | DLT용 Object template(`JacksonJsonSerializer`)과 Outbox 원문용 String template(`StringSerializer`, `acks=all`)을 분리한다. order consumer 설정은 비활성 폴백으로 유지한다 |
| `application-local.yml` | `spring.kafka.bootstrap-servers` + `spring.kafka.consumer.*`(group-id·auto-offset-reset·enable-auto-commit) + `settlement.kafka.listener.order.enabled: false` |
| 발행 토픽 | `settlement.kafka.producer.topic`(= `settlement-events`)을 Outbox 적재 시 행에 저장한다. publisher는 행의 topic·aggregateId·payload를 그대로 사용한다 |
| 운영 DB | 애플리케이션 배포 전에 `docs/sql/301-settlement-outbox.sql`을 적용해야 한다(`ddl-auto: validate`) |
| Kubernetes | 월 CronJob은 중복 실행 방지를 위해 `concurrencyPolicy: Forbid` 권장. redrive는 `eventId`를 주입하는 one-off Job. manifest와 배치 전용 JVM runner는 #301 범위 밖 |

> 발행(producer factory)은 코드로 이미 붙어 있다. 수신(consumer factory)은 order 원천을 pull 로 뺐지만
> `OrderEventConsumer` 를 비활성(기본 OFF)으로 남겨둔 동안 config·yml 에 함께 유지된다(order 서버 가동 후 제거).

---

## 7. 구현 순서 (제안)

1. ~~**원천 수신 정리**~~ — gRPC pull(`infrastructure/client/order` + 배치 Step 0) 전환 완료(#260).
   `consumer/order/*`·order 이벤트 DTO 는 비활성으로 잔류(order 서버 가동 후 제거). (`order-data-sourcing.md`)
2. ~~**발행(SETTLEMENT_CREATED)**~~ — 최초 AFTER_COMMIT 발행 완료(#258).
3. ~~**SETTLEMENT_CREATED 유실 보강 — 배치 flush Outbox**~~ — 정산·SourceLine과 같은 트랜잭션 적재,
   시작/마지막 flush, 3회 실패 `FAILED`, targeted redrive Job 구현 완료(#301).
4. **(추후·파이널) 발행(payout.completed)** — 지급 완료 알림. AFTER_COMMIT 패턴으로 추가.
5. (후속) 지급 명령 이벤트 정식화 시 — 같은 배치 flush Outbox 로 구현.

> 변경분은 PR 전 `verify-rules` 로 7종 룰 검증을 거친다.
</content>
