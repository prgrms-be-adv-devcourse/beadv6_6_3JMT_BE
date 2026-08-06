# 결제 실패·타임아웃 공통 보상 테스트 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** 결제 실패 이벤트와 결제 결과 미수신 타임아웃이 동일한 로컬 보상 트랜잭션으로 수렴하고, 늦은 결제 승인·중복 처리·Redis 정리·동시성 상황에서도 최종 불변식을 지키는지 테스트로 고정한다.

**Architecture:** OrderFailureCompensationService를 결제 실패와 타임아웃의 단일 DB 보상 경계로 검증한다. 실제 PostgreSQL에서 주문·주문상품·Cart·처리 이력의 원자성과 잠금을 검증하고, Embedded Kafka와 Redis/PostgreSQL Testcontainers로 메시징 및 AFTER_COMMIT 경계를 검증한다.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Gradle Groovy DSL, JUnit 5, Mockito, AssertJ, Awaitility, Spring Data JPA, PostgreSQL Testcontainers, Redis Testcontainers, Embedded Kafka, Flyway

## Global Constraints

- 수정 범위는 order-service/**와 그 내부 docs/**로 제한한다.
- 모든 기능 변경은 RED → GREEN → REFACTOR 순서로 진행한다.
- 트랜잭션·유일 제약·PESSIMISTIC_WRITE 검증에 H2를 사용하지 않는다.
- Kafka topic, eventType, manual ACK, retry/DLT 정책은 기존 계약을 유지한다.
- Redis key schema와 Worker retry/DLQ 정책은 기존 계약을 유지한다.
- 주문 관련 동시 쓰기는 Order → Cart 순서로 잠금을 획득한다.
- Cart 변경은 항상 Cart aggregate root 잠금에 참여한다.
- 테스트를 통과시키기 위한 Thread.sleep, 무제한 retry, 전역 JVM lock을 추가하지 않는다.
- 운영 데이터 삭제 규칙을 임의로 만들지 않는다.
- 사용자가 별도로 승인하지 않으면 stage, commit, push, PR 생성은 수행하지 않는다.

---

## 1. 문서 목적

이 문서는 결제 실패 이벤트와 결제 결과 미수신 타임아웃이 동일한 로컬 보상 트랜잭션을 사용하도록 변경할 때 필요한 테스트를 정의한다.

테스트의 최우선 목표는 개별 메서드 호출 여부가 아니라 다음 최종 불변식을 검증하는 것이다.

- 실패가 최종 결과이면 주문은 FAILED, 대상 주문상품은 FAILED, 주문 상품은 구매자 Cart에 중복 없이 존재한다.
- 실제 결제 승인이 존재하면 처리 순서와 무관하게 주문은 COMPLETED, 주문상품은 PAID, 주문 상품은 Cart에 존재하지 않는다.
- 주문·주문상품·Cart·Kafka 처리 이력은 하나의 로컬 트랜잭션에서 함께 commit 또는 rollback된다.
- 중복 실패 이벤트와 만료 Worker 재실행은 성공적인 no-op으로 종료된다.
- Redis 정리는 DB commit 이후에만 수행되며, 정리 실패가 DB 결과를 되돌리지 않는다.
- 모든 상태 변경 경로는 Order → Cart 잠금 순서와 동일한 Cart 루트 잠금 정책을 따른다.

기존 production package인 com.prompthub.order를 그대로 미러링하고, 현재 존재하는 테스트 지원 코드와 명명 규칙을 우선한다.

## 2. 테스트 대상 흐름

### 2.1 결제 실패 이벤트

~~~text
PaymentFailedEventHandler
  → PaymentFailedProcessor
  → OrderFailureCompensationService.compensatePaymentFailure
  → DB commit
  → OrderExpirationCleanupRequestedEvent
  → OrderExpirationCleanupListener AFTER_COMMIT
  → Kafka ACK
~~~

### 2.2 결제 결과 미수신 타임아웃

~~~text
OrderExpirationWorker
  → OrderFailureCompensationService.compensateTimeout
  → DB commit
  → OrderExpirationCleanupRequestedEvent
  → OrderExpirationCleanupListener AFTER_COMMIT
~~~

### 2.3 늦은 결제 승인

~~~text
PaymentApprovedEventHandler
  → PaymentApprovedProcessor
  → Order 루트 잠금
  → Cart 루트 잠금
  → CREATED 또는 FAILED 주문을 COMPLETED로 전환
  → 대상 주문상품을 PAID로 전환
  → 주문 상품을 Cart에서 제거
  → DB commit
  → AFTER_COMMIT Redis 정리
~~~

## 3. 테스트에서 고정할 핵심 계약

### 3.1 상태 전이 계약

| 현재 주문 상태 | 입력 | 기대 주문 상태 | 기대 주문상품 상태 | 기대 Cart 상태 |
|---|---|---|---|---|
| CREATED | PAYMENT_FAILED | FAILED | PENDING만 FAILED | 주문 상품 모두 존재 |
| CREATED | timeout | FAILED | PENDING만 FAILED | 주문 상품 모두 존재 |
| FAILED | 동일 실패 재처리 | FAILED | 변경 없음 | 중복 없이 유지 |
| FAILED | 늦은 PAYMENT_APPROVED | COMPLETED | 대상 상품 PAID | 주문 상품 모두 제거 |
| COMPLETED | 늦은 PAYMENT_FAILED | COMPLETED | PAID 유지 | 변경 없음 |
| CREATED | PAYMENT_APPROVED | COMPLETED | 대상 상품 PAID | 결제 대기 중 재추가된 주문 상품 제거 |
| COMPLETED | 중복 승인 | COMPLETED | PAID 유지 | 제거 상태 유지 |

혼합 상태 주문상품에 대한 PENDING 한정 변경은 현재 Order.markFailed 동작과 차이가 있을 수 있다. 테스트 RED 단계에서 실제 차이를 확인한 뒤, 도메인 상태 전이 정책 변경이 필요한 경우 별도 리뷰 체크포인트를 둔다.

### 3.2 Cart 집합 계약

- Cart는 productId 기준 집합처럼 동작한다.
- Cart가 없으면 구매자당 하나만 생성한다.
- 같은 buyer_id의 Cart는 둘 이상 존재할 수 없다.
- 같은 cart_id, product_id 조합의 CartProduct는 둘 이상 존재할 수 없다.
- 복구 시 이미 존재하는 상품은 유지하고 누락된 상품만 추가한다.
- 승인 시 주문에 포함된 상품만 제거하고 관계없는 Cart 상품은 유지한다.
- 상품 순서는 테스트 계약으로 삼지 않는다.

### 3.3 Kafka 처리 이력 계약

- 멱등성 키는 eventId + consumerGroup이다.
- 같은 키의 이벤트 재수신은 보상 로직을 재실행하지 않고 성공 처리한다.
- 같은 eventId라도 consumerGroup이 다르면 독립 처리 이력으로 취급한다.
- 최초 수신 이벤트가 이미 FAILED 또는 COMPLETED 주문에 대한 no-op이어도 처리 이력을 저장한다.
- timeout 경로는 Kafka 이벤트가 아니므로 processed-event를 생성하지 않는다.
- 현재 Order Service payment consumer group 값은 order-service이다.

### 3.4 Redis 정리 계약

- 만료 키와 retry 정보 삭제는 DB commit 이후에 실행한다.
- DB rollback 시 Redis 정리 listener는 실행되지 않는다.
- Redis 정리 예외는 listener 내부에서 로깅 후 격리한다.
- Redis 정리 실패 여부와 무관하게 주문·주문상품·Cart·processed-event commit 결과는 유지된다.
- 동일 key 삭제는 여러 번 실행해도 성공하는 멱등 연산이어야 한다.

## 4. 테스트 계층과 책임 분리

| 계층 | 목적 | 검증 대상 | 사용 기술 |
|---|---|---|---|
| 단위 테스트 | 분기, 매핑, 위임, 호출 순서 | Processor/Worker 위임, payload 호환, Order → Cart 호출 순서 | JUnit 5, Mockito, AssertJ |
| 도메인 테스트 | 상태 전이 규칙 | CREATED → FAILED, FAILED → COMPLETED, PENDING 한정 변경 | JUnit 5 |
| JPA 통합 테스트 | 실제 영속성 결과와 트랜잭션 | 원자성, add-if-absent, processed-event, unique 제약 | PostgreSQL Testcontainers |
| Redis 통합 테스트 | commit 경계와 key 정리 | AFTER_COMMIT, rollback 미호출, cleanup 실패 격리 | Redis Testcontainers |
| Kafka 통합 테스트 | 수신부터 ACK/DLT까지 | manual ACK, 중복 이벤트, retry, DLT, payload 매핑 | Embedded Kafka, Awaitility |
| 동시성 테스트 | 잠금과 최종 수렴 | 실패/승인/Cart 수정 경합, 교착 방지, 단일 Cart | PostgreSQL Testcontainers |
| 회귀 테스트 | 기존 정책 보존 | Worker Redis retry/DLQ, 기존 승인·주문 생성 동작 | 기존 테스트와 추가 회귀 테스트 |

동일한 도메인 조합을 모든 계층에서 반복하지 않는다. 단위 테스트는 분기와 협력 관계를, PostgreSQL 통합 테스트는 실제 트랜잭션과 잠금을, Kafka 테스트는 메시징 경계만 검증한다.

## 5. 실제 파일 구성

현재 코드베이스를 기준으로 다음 파일을 생성하거나 확장한다.

~~~text
order-service/src/test/java/com/prompthub/order/
├── support/
│   ├── PostgreSqlIntegrationTestSupport.java
│   ├── RedisContainerSupport.java
│   ├── KafkaTestRecordFactory.java
│   ├── ConcurrentScenarioRunner.java
│   └── DatabaseStateProbe.java
├── fixture/
│   └── PaymentEventFixture.java (기존 보상 fixture 재사용)
├── application/service/order/
│   ├── OrderFailureCompensationServiceTest.java
│   ├── OrderFailureCompensationJpaTest.java
│   ├── OrderFailureCompensationTransactionIntegrationTest.java
│   └── OrderFailureCompensationConcurrencyTest.java
├── application/service/event/
│   ├── PaymentFailedProcessorTest.java
│   ├── PaymentFailedEventHandlerTest.java
│   ├── PaymentApprovedProcessorTest.java
│   └── PaymentEventTransactionIntegrationTest.java
├── infra/persistence/
│   ├── CartUniquenessMigrationTest.java
│   └── AggregateRootLockPersistenceTest.java
├── infra/messaging/kafka/
│   └── PaymentEventConsumerIntegrationTest.java
└── infra/redis/
    ├── OrderExpirationWorkerTest.java
    ├── RedisOrderExpirationStoreTest.java
    ├── OrderExpirationCleanupListenerTest.java
    ├── OrderExpirationCleanupAfterCommitIntegrationTest.java
    └── OrderExpirationRedisPolicyIntegrationTest.java
~~~

### 5.1 테스트 지원 코드 책임

**PaymentEventFixture 재사용**

다음 시나리오를 한 줄로 생성할 수 있어야 한다.

- 판매자 2명, 상품 4개, 주문 1건, 주문상품 4건인 CREATED/PENDING 주문
- 바로 구매 단건 주문
- Cart가 없는 구매자
- 일부 상품만 이미 들어 있는 Cart
- FAILED/FAILED 보상 완료 주문
- COMPLETED/PAID 결제 완료 주문
- PENDING과 비-PENDING 주문상품이 섞인 방어 시나리오

별도 OrderCompensationFixture를 중복 생성하지 않고 기존 PaymentEventFixture를 확장해 사용한다. 각 테스트는 독립된 ID를 사용하고 테스트 간 mutable entity를 공유하지 않는다.

**DatabaseStateProbe**

트랜잭션 rollback 검증에서 1차 캐시 영향을 피하기 위해 새 트랜잭션과 새 persistence context로 다음 값을 읽는다.

- 주문 상태
- 주문상품별 상태
- 구매자 Cart 개수
- CartProduct productId 집합과 row 수
- eventId + consumerGroup 처리 이력 개수

**ConcurrentScenarioRunner**

- 각 작업을 별도 REQUIRES_NEW 트랜잭션에서 실행한다.
- CyclicBarrier 또는 CountDownLatch로 경합 시점을 제어한다.
- Thread.sleep을 동기화 수단으로 사용하지 않는다.
- 모든 Future에 제한 시간을 적용한다.
- timeout, deadlock, lock acquisition exception을 테스트 실패로 처리한다.

## 6. 상세 테스트 시나리오

### 6.1 공통 보상 서비스 단위 테스트

**대상:** OrderFailureCompensationServiceTest

#### COMP-U-01 잠금 순서가 Order → Cart이다

Given CREATED 주문과 구매자 Cart가 존재한다.

When compensatePaymentFailure를 호출한다.

Then 핵심 협력 순서는 다음과 같다.

1. processed-event 중복 fast path 확인
2. Order 루트 PESSIMISTIC_WRITE 조회
3. Order lock 이후 processed-event 재확인
4. 주문상품 초기화
5. Cart 루트 PESSIMISTIC_WRITE 조회 또는 생성
6. CartProduct add-if-absent
7. processed-event 저장

Mockito InOrder는 Order lock이 Cart lock보다 먼저라는 사실에만 사용한다. 실제 잠금 효과는 PostgreSQL 동시성 테스트에서 검증한다.

#### COMP-U-02 timeout과 payment failure가 같은 핵심 보상 규칙을 사용한다

두 public entry point가 서로 다른 상태 전이 구현을 갖지 않아야 한다. private 메서드를 직접 테스트하지 않고 동일한 초기 픽스처에 대한 최종 DB 결과가 같은지 COMP-PG-08에서 검증한다.

#### COMP-U-03 중복 processed-event는 성공 no-op이다

이미 eventId + consumerGroup 처리 이력이 있으면 Order와 Cart를 변경하지 않고 정상 종료한다. Redis self-healing을 위해 cleanup 내부 이벤트는 다시 발행한다.

#### COMP-U-04 timeout은 processed-event를 저장하지 않는다

Worker 재실행 멱등성은 주문 상태와 Cart add-if-absent로 보장하며 Kafka 처리 이력을 생성하지 않는다.

#### COMP-U-05 실제 만료 전 timeout 후보는 보상하지 않는다

Order lock 획득 후 OrderExpirationPolicy.paymentTimeoutMinutes 기준으로 만료 여부를 재검증한다. 아직 만료되지 않았다면 false를 반환하고 상태·Cart·processed-event·cleanup을 변경하지 않는다.

### 6.2 PostgreSQL 보상 결과 테스트

**대상:** OrderFailureCompensationJpaTest

#### COMP-PG-01 장바구니 상품 4개 주문 실패 시 4개가 복구된다

초기 상태:

- 주문 1건: CREATED
- 주문상품 4건: 모두 PENDING
- 판매자: 2명 이상
- 구매자 Cart: 존재하지만 주문 상품은 없음

검증:

- 주문 FAILED
- 주문상품 4건 모두 FAILED
- CartProduct productId 집합이 주문의 productId 4개와 정확히 일치
- CartProduct row 수 4
- processed-event row 수 1
- 판매자 수와 무관하게 주문 1건 유지

#### COMP-PG-02 바로 구매 단건도 Cart에 복구된다

구매 출처를 별도 저장하지 않고 productId 한 개가 새 Cart 또는 기존 Cart에 복구되는지 검증한다.

#### COMP-PG-03 이미 Cart에 있는 동일 상품은 중복 추가되지 않는다

4개 중 2개가 이미 Cart에 있는 상태에서 보상 후 총 row 수가 4이고 각 productId별 row 수가 1인지 검증한다.

#### COMP-PG-04 구매자의 Cart가 없으면 하나만 생성된다

보상 후 cart.buyer_id row가 정확히 한 개이고 모든 주문 상품이 해당 Cart에 연결되는지 검증한다.

#### COMP-PG-05 CREATED 주문의 PENDING 상품만 FAILED로 바뀐다

PENDING, PAID, FAILED 상태가 섞인 방어 픽스처에서 PENDING만 FAILED로 변경되고 기존 PAID/FAILED는 유지되는지 검증한다. 현재 도메인 규칙과 충돌하면 RED 결과를 설계 리뷰 체크포인트로 사용한다.

#### COMP-PG-06 이미 FAILED인 주문 재보상은 성공 no-op이다

첫 보상 전후 snapshot을 비교해 두 번째 timeout 또는 별도 실패 이벤트가 주문, 주문상품, Cart row를 변경하지 않는지 검증한다. Kafka 경로의 새 eventId는 processed-event만 추가할 수 있다.

#### COMP-PG-07 COMPLETED 주문의 늦은 실패는 상태와 Cart를 변경하지 않는다

COMPLETED/PAID 주문과 관계없는 Cart 상품 한 개를 준비한다. 실패 처리 후 상태와 관계없는 Cart 상품을 유지하고 최초 실패 eventId의 processed-event 한 건만 저장하는지 검증한다.

#### COMP-PG-08 payment failure와 timeout의 DB 결과가 동일하다

같은 초기 픽스처를 두 개 생성해 각 경로를 실행한다. processed-event 존재 여부를 제외한 주문 상태, 주문상품 상태, Cart 상품 집합이 동일해야 한다.

#### COMP-PG-09 같은 eventId라도 consumerGroup이 다르면 처리 이력을 구분한다

ProcessedEventService 수준에서 eventId가 같고 consumerGroup이 다른 두 행을 저장할 수 있는지 검증한다. OrderFailureCompensationService의 consumer group은 order-service로 고정되어 있으므로 테스트를 위해 production API에 consumerGroup 인자를 추가하지 않는다.

### 6.3 트랜잭션 원자성 테스트

**대상:** OrderFailureCompensationTransactionIntegrationTest

테스트 클래스나 메서드 전체에 @Transactional을 붙이지 않는다. 서비스 트랜잭션 종료 후 새 persistence context에서 결과를 조회한다.

#### TX-PG-01 Cart 복구 중 예외가 발생하면 전체 rollback된다

Cart 저장 협력 객체를 test spy로 대체해 예외를 발생시킨다.

기대 결과:

- 주문 CREATED
- 주문상품 PENDING
- CartProduct 추가 없음
- processed-event 없음
- AFTER_COMMIT Redis 정리 호출 없음

#### TX-PG-02 processed-event 저장 실패 시 전체 rollback된다

processed-event 저장 또는 flush 지점에서 예외를 발생시킨다.

기대 결과:

- 주문과 주문상품 상태 원복
- 새 Cart가 생성됐다면 Cart도 rollback
- CartProduct 없음
- processed-event 없음
- Redis 정리 없음

#### TX-PG-03 cleanup 내부 이벤트 발행 전에 예외가 발생하면 전체 rollback된다

트랜잭션 마지막 단계의 event publisher 또는 명시적인 test failure hook에서 예외를 발생시켜 dirty checking 이후에도 DB가 commit되지 않는지 확인한다.

#### TX-PG-04 rollback 후 같은 이벤트 재처리는 정상 성공한다

첫 실행은 의도적으로 실패시키고 failure hook을 제거한 뒤 같은 eventId를 재전송한다. 첫 실행 처리 이력이 없으므로 두 번째 실행이 실제 보상을 완료해야 한다.

### 6.4 Cart 유일 제약과 잠금 테스트

**대상:** CartUniquenessMigrationTest

#### DB-MIG-01 cart(buyer_id) unique 제약이 존재한다

동일 buyerId로 Cart 두 건을 insert하고 flush하면 PostgreSQL unique violation이 발생해야 한다.

#### DB-MIG-02 cart_product(cart_id, product_id) unique 제약이 존재한다

같은 Cart와 productId 조합을 두 번 insert하고 flush하면 PostgreSQL unique violation이 발생해야 한다.

#### DB-MIG-03 Flyway clean → migrate가 PostgreSQL에서 성공한다

빈 PostgreSQL에 전체 migration을 적용하고 애플리케이션 스키마 검증이 성공해야 한다.

#### DB-MIG-04 기존 중복 데이터 처리 정책을 고정한다

확정 정책은 V5 migration에서 임의 row를 삭제하지 않고, 기존 중복 데이터가 있으면 unique 제약 추가를 명확히 실패시키는 방식이다. 운영 데이터 정리는 별도 승인과 별도 작업으로 수행한다.

**대상:** AggregateRootLockPersistenceTest와 신규 CartRootLockIntegrationTest

다음 경로가 같은 Cart 루트 잠금에 참여하는지 검증한다.

- Cart 상품 추가
- Cart 상품 삭제
- 주문 생성 시 Cart 상품 제거
- 결제 승인 시 Cart 상품 제거
- 실패/timeout 보상 시 Cart 상품 추가

실제 PostgreSQL 테스트에서는 한 트랜잭션이 Cart 루트 lock을 보유할 때 다른 경로가 같은 Cart 변경을 완료하지 못하고 대기하는지 확인한다.

### 6.5 늦은 승인과 성공 경로 테스트

**대상:** PaymentApprovedProcessorTest와 PaymentEventTransactionIntegrationTest

#### APP-PG-01 보상 후 늦은 승인은 FAILED → COMPLETED로 전환한다

초기 상태:

- 주문 FAILED
- 주문상품 모두 FAILED
- 보상 상품이 Cart에 존재
- 관계없는 Cart 상품 한 개 존재

승인 후:

- 주문 COMPLETED
- 주문상품 모두 PAID
- 주문 productId가 Cart에서 제거
- 관계없는 Cart 상품 유지

#### APP-PG-02 결제 대기 중 재추가된 상품은 정상 승인 시 제거된다

CREATED/PENDING 주문 상품을 사용자가 Cart에 다시 추가한 뒤 승인 이벤트를 처리한다. 최종 상태는 COMPLETED/PAID이고 해당 상품은 Cart에서 제거되어야 한다.

#### APP-PG-03 중복 승인은 멱등적이다

동일 승인 이벤트를 두 번 처리해도 주문·주문상품·Cart·Outbox·processed-event 결과가 변하지 않는다. stale Redis key 정리를 위해 cleanup 이벤트만 다시 발행한다.

#### APP-PG-04 성공 주문의 늦은 실패는 승인 결과를 뒤집지 않는다

승인 완료 후 실패 이벤트를 처리한다. COMPLETED/PAID와 Cart 상태는 유지되고 실패 eventId는 처리 이력에 기록된다.

### 6.6 Payment Failed payload와 handler 단위 테스트

**대상:** PaymentFailedPayloadTest, PaymentFailedProcessorTest, PaymentFailedEventHandlerTest

#### PAY-U-01 userId를 buyerId로 하위 호환 매핑한다

payload에 userId만 있으면 PaymentFailedPayload.buyerId로 역직렬화돼 compensation service에 전달되어야 한다.

#### PAY-U-02 buyerId를 명시하면 buyerId를 사용한다

현재 DTO는 JsonAlias로 userId와 buyerId를 같은 필드에 매핑한다. 두 필드를 동시에 보내는 모호한 payload는 producer 계약에서 금지하고, 단일 명시 필드만 테스트한다.

#### PAY-U-03 code와 reason이 없으면 nullable metadata로 전달한다

없는 값을 임의 기본 문자열로 생성하지 않는다.

#### PAY-U-04 실패 시각이 없으면 envelope occurredAt을 사용한다

payload.failedAt이 있으면 이를 우선하고, 없으면 envelope occurredAt을 사용한다.

#### PAY-U-05 buyerId와 userId가 모두 없으면 처리 실패한다

잘못된 이벤트를 ACK하지 않고 기존 Kafka retry/DLT 흐름으로 전달한다.

#### PAY-H-01 processor 성공 시 manual ACK한다

정상 보상, 이미 처리된 이벤트, 성공 주문에 대한 no-op은 모두 ACK 대상이다.

#### PAY-H-02 processor 예외 시 ACK하지 않고 예외를 전파한다

기존 listener error handler가 retry와 DLT를 수행할 수 있도록 예외를 삼키지 않는다.

### 6.7 Embedded Kafka 통합 테스트

**대상:** PaymentEventConsumerIntegrationTest

Kafka 테스트는 실제 PostgreSQL Testcontainers와 함께 실행한다. 비동기 검증은 Awaitility를 사용하고 고정 sleep을 사용하지 않는다.

#### KAFKA-IT-01 PAYMENT_FAILED 수신이 전체 보상을 완료한다

userId만 포함한 실제 envelope를 발행한다.

검증:

- 주문 FAILED
- 주문상품 FAILED
- Cart 복구
- processed-event 저장
- 재전달 없음
- DLT 없음

#### KAFKA-IT-02 동일 eventId 중복 수신은 성공 no-op이다

같은 record를 두 번 발행한다.

- processed-event 한 건
- CartProduct 중복 없음
- 주문 상태 추가 변경 없음
- 두 번째 record도 정상 소비

#### KAFKA-IT-03 COMPLETED 주문의 늦은 실패는 상태를 변경하지 않는다

처리 이력은 남지만 COMPLETED/PAID와 Cart 상태는 유지돼야 한다.

#### KAFKA-IT-04 metadata가 없는 현재 producer payload를 처리한다

paymentId, orderId, userId만 포함한 payload와 envelope occurredAt으로 정상 보상되는지 검증한다.

#### KAFKA-IT-05 영구 실패 시 기존 retry 횟수 이후 DLT로 이동한다

현재 error handler 계약은 최초 시도 한 번과 추가 재시도 세 번으로 총 네 번 호출한 뒤 같은 partition의 payment-events.DLT로 이동하는 것이다.

각 실패 시:

- DB CREATED/PENDING
- Cart 미복구
- processed-event 없음

DLT record에서는 원본 key, eventId, orderId, 원본 topic 및 기존 exception header를 검증한다.

#### KAFKA-IT-06 일시 실패 후 재시도 성공 시 DLT로 이동하지 않는다

첫 N회만 실패하고 다음 시도에 성공하도록 failure stub을 구성한다. 최종 DB 상태와 처리 이력은 한 번만 commit돼야 한다.

#### KAFKA-IT-07 Redis cleanup 실패는 commit된 이벤트를 DLT로 보내지 않는다

AFTER_COMMIT cleanup port가 예외를 던지도록 구성한다. listener가 예외를 격리하므로 DB 결과는 commit되고 원본 메시지는 ACK되며 DLT record는 없어야 한다.

### 6.8 OrderExpirationWorker 회귀 테스트

**대상:** OrderExpirationWorkerTest와 OrderExpirationRedisPolicyIntegrationTest

#### EXP-U-01 Worker는 compensateTimeout만 호출한다

Worker 내부에 별도 주문 상태 변경이나 Cart 복구 구현이 없어야 한다.

#### EXP-U-02 보상 성공과 성공 no-op을 같은 완료 처리로 본다

이미 FAILED 또는 COMPLETED인 주문 재실행은 retry 증가나 expiration DLQ 이동 없이 종료되어야 한다.

#### EXP-U-03 보상 예외는 기존 Redis retry 정책으로 전달한다

compensation service 예외는 기존 incrementRetryCount 분기로 전달되어야 한다.

#### EXP-REDIS-01 일시 실패 시 retry 정보가 증가한다

order:expiration:retry의 기존 count semantics를 characterization test로 고정한다.

#### EXP-REDIS-02 최대 retry 초과 시 DLQ 정책이 유지된다

order:expiration:dlq로 이동하고 expiration과 retry 정보를 기존 정책대로 정리한다.

#### EXP-REDIS-03 재실행 성공 시 retry와 expiration 정보가 제거된다

DB commit 이후 두 정보가 제거되는지 실제 Redis에서 검증한다.

### 6.9 Redis AFTER_COMMIT 테스트

**대상:** OrderExpirationCleanupAfterCommitIntegrationTest와 OrderExpirationCleanupListenerTest

#### REDIS-IT-01 DB commit 전에는 Redis key를 삭제하지 않는다

외부 TransactionTemplate 안에서 보상 서비스를 호출한다. callback 내부에서는 expiration/retry key가 존재하고 transaction 종료 이후에만 삭제되는지 확인한다.

#### REDIS-IT-02 DB rollback이면 Redis 정리를 수행하지 않는다

보상 트랜잭션을 의도적으로 rollback시키고 Redis key가 유지되는지 검증한다.

#### REDIS-IT-03 Redis 정리 실패가 DB commit을 되돌리지 않는다

cleanup port가 예외를 던지도록 구성한다.

- 주문 FAILED
- 주문상품 FAILED
- Cart 복구 완료
- processed-event 저장 완료
- 호출자에게 cleanup 예외 미전파

#### REDIS-IT-04 성공 no-op에서도 Redis 정리를 재시도한다

이미 FAILED인 timeout 재실행, 별도 eventId의 실패 no-op, 이미 처리한 동일 eventId fast path에서 DB 상태와 Cart를 변경하지 않고 cleanup 이벤트를 다시 요청하는지 검증한다.

### 6.10 PostgreSQL 동시성 테스트

**대상:** OrderFailureCompensationConcurrencyTest

모든 작업은 실제 PostgreSQL의 별도 트랜잭션과 별도 EntityManager에서 실행한다.

#### CONC-PG-01 실패가 먼저 잠금을 획득하고 승인이 대기해도 승인이 우선한다

1. 실패 보상 트랜잭션이 Order lock을 획득한 뒤 barrier에서 대기한다.
2. 승인 트랜잭션을 시작해 같은 Order lock에서 대기시킨다.
3. 실패 보상을 commit한다.
4. 승인이 FAILED → COMPLETED, FAILED → PAID, Cart 상품 제거를 수행한다.

최종 불변식은 COMPLETED/PAID이며 주문 상품은 Cart에 없어야 한다.

#### CONC-PG-02 승인이 먼저 commit되면 늦은 실패가 no-op이다

최종 불변식은 COMPLETED/PAID, 주문 상품 Cart 미존재이며 실패 processed-event는 저장돼야 한다.

#### CONC-PG-03 Kafka 실패와 timeout이 동시에 실행돼도 한 번만 복구된다

- 주문 상태 전이 한 번
- CartProduct productId별 한 행
- Kafka processed-event 한 행
- timeout 처리 이력 없음
- deadlock 없음

#### CONC-PG-04 동일 실패 이벤트 두 건이 동시에 실행돼도 멱등적이다

eventId + consumerGroup unique 제약과 Order lock으로 processed-event 한 행과 CartProduct productId별 한 행만 남아야 한다.

#### CONC-PG-05 Cart가 없는 상태에서 보상과 사용자 Cart 추가가 경합한다

동일 buyerId를 대상으로 보상과 일반 Cart 추가를 동시에 실행한다.

목표 불변식:

- cart.buyer_id row 한 개
- 동일 productId row 한 개
- 최종 주문이 FAILED면 주문 상품이 Cart에 존재

현재 승인된 구현은 absent Cart 생성 경합에서 한 트랜잭션이 unique violation으로 rollback될 수 있고 Kafka/Worker retry로 회복한다. 사용자 HTTP 요청까지 unique violation을 숨기는 DB upsert 또는 제한 재시도는 현재 범위 밖이다. CONC-PG-05에서 외부 예외 미노출까지 요구하려면 별도 설계 승인 후 production 변경을 진행한다.

#### CONC-PG-06 결제 대기 중 사용자 재추가가 승인보다 먼저 완료되면 승인이 제거한다

사용자 Cart 추가 commit 이후 승인 처리가 Cart lock을 획득하도록 제어한다. 최종 상태는 COMPLETED/PAID이며 주문 상품은 Cart에 없어야 한다.

#### CONC-PG-07 승인 완료 이후 사용자가 새로 추가한 상품은 유지된다

승인 commit 이후 별도 사용자 행위로 동일 상품을 다시 추가한다. 이는 승인 이후의 새로운 사용자 의도이므로 Cart에 남아야 한다.

#### CONC-PG-08 주문 생성·승인·보상·Cart 변경 경로 사이에 교착이 없다

다음 pair를 latch로 제어해 bounded timeout 안에 완료되는지 확인한다.

- 주문 생성 ↔ Cart 추가
- 주문 생성 ↔ Cart 삭제
- 실패 보상 ↔ Cart 추가
- 실패 보상 ↔ Cart 삭제
- 승인 ↔ 실패 보상
- 승인 ↔ Cart 추가

## 7. 테스트 데이터 기준

| 객체 | 기준 |
|---|---|
| Buyer | 테스트별 고유 buyer UUID |
| Seller | seller-1, seller-2에 대응하는 고유 UUID |
| Products | product-1부터 product-4에 대응하는 고유 UUID |
| Order | 상품 네 개를 포함한 단일 orderId |
| Consumer group | order-service |
| Event | 테스트별 고유 UUID, 중복 테스트에서만 재사용 |

기본 네 상품 주문:

| 주문상품 | 판매자 | 초기 상태 |
|---|---|---|
| product-1 | seller-1 | PENDING |
| product-2 | seller-1 | PENDING |
| product-3 | seller-2 | PENDING |
| product-4 | seller-2 | PENDING |

검증은 entity collection 순서가 아니라 productId 집합과 row count를 사용한다.

## 8. 구현 순서

각 Task는 failing test를 먼저 작성하고 기대한 이유로 실패하는지 확인한 뒤 최소 구현으로 통과시킨다.

### Task 0: 기존 정책 Characterization

**Files:**

- Modify: order-service/src/test/java/com/prompthub/order/infra/redis/OrderExpirationWorkerTest.java
- Modify: order-service/src/test/java/com/prompthub/order/infra/messaging/kafka/PaymentEventConsumerIntegrationTest.java
- Modify: order-service/src/test/java/com/prompthub/order/application/service/event/PaymentApprovedProcessorTest.java
- Modify: order-service/src/test/java/com/prompthub/order/application/service/order/OrderCreatorTest.java

**Interfaces:**

- Consumes: 기존 Worker, Kafka error handler, 승인 Processor, OrderCreator
- Produces: 이후 Task가 보존해야 하는 retry, DLT, 잠금, Cart 제거 계약

- [x] **Step 1: Worker retry/DLQ, Kafka 총 네 번 호출, consumer group, Redis key를 characterization test로 고정한다**
- [x] **Step 2: 관련 테스트를 실행해 변경 전 정책에서 PASS하는지 확인한다**
- [x] **Step 3: 승인과 주문 생성 경로의 Cart 잠금 사용을 테스트로 고정한다**
- [ ] **Step 4: 승인된 경우에만 characterization test를 commit한다**

### Task 1: PostgreSQL·Redis·Kafka 테스트 기반

**Files:**

- Modify: order-service/build.gradle
- Modify: order-service/src/test/java/com/prompthub/order/support/PostgreSqlIntegrationTestSupport.java
- Create: order-service/src/test/java/com/prompthub/order/support/RedisContainerSupport.java
- Reuse: order-service/src/test/java/com/prompthub/order/fixture/PaymentEventFixture.java
- Create: order-service/src/test/java/com/prompthub/order/support/DatabaseStateProbe.java
- Create: order-service/src/test/java/com/prompthub/order/support/ConcurrentScenarioRunner.java

**Interfaces:**

- Produces: 실제 PostgreSQL/Redis 연결, 독립 fixture, 새 persistence context 조회, bounded concurrency runner

- [x] **Step 1: PostgreSQL과 Redis container smoke test를 RED로 작성한다**
- [x] **Step 2: 필요한 Testcontainers 의존성과 DynamicPropertySource 설정을 최소 추가한다**
- [x] **Step 3: fixture, state probe, concurrency runner를 추가한다**
- [x] **Step 4: smoke test를 GREEN으로 만들고 테스트 종료 후 데이터 격리를 확인한다**
- [ ] **Step 5: 승인된 경우에만 테스트 기반 변경을 commit한다**

### Task 2: 공통 보상 핵심 결과

**Files:**

- Modify: order-service/src/test/java/com/prompthub/order/application/service/order/OrderFailureCompensationServiceTest.java
- Create: order-service/src/test/java/com/prompthub/order/application/service/order/OrderFailureCompensationJpaTest.java
- Modify only for GREEN: order-service/src/main/java/com/prompthub/order/application/service/order/OrderFailureCompensationService.java

**Interfaces:**

- Consumes: OrderFailureCompensationService.compensatePaymentFailure, compensateTimeout
- Produces: COMP-U-01부터 COMP-U-05, COMP-PG-01부터 COMP-PG-09의 고정된 결과

- [ ] **Step 1: 네 상품 복구, 바로 구매, 부분 중복 Cart, Cart 미존재 테스트를 RED로 작성한다**
- [ ] **Step 2: 실패와 timeout 결과 동등성, FAILED/COMPLETED no-op 테스트를 RED로 작성한다**
- [ ] **Step 3: 가장 작은 대상 테스트를 실행해 기대한 이유로 실패하는지 확인한다**
- [ ] **Step 4: 필요한 최소 production 변경만 수행한다**
- [ ] **Step 5: 보상 단위/JPA 테스트를 GREEN으로 만든다**
- [ ] **Step 6: 중복된 fixture와 assertion을 정리하고 회귀 테스트를 다시 실행한다**
- [ ] **Step 7: 승인된 경우에만 공통 보상 결과 변경을 commit한다**

### Task 3: 원자성과 unique 제약

**Files:**

- Modify: order-service/src/test/java/com/prompthub/order/application/service/order/OrderFailureCompensationTransactionIntegrationTest.java
- Modify: order-service/src/test/java/com/prompthub/order/infra/persistence/CartUniquenessMigrationTest.java
- Modify only for GREEN: order-service/src/main/resources/db/migration/V5__add_cart_compensation_uniqueness.sql

**Interfaces:**

- Produces: TX-PG-01부터 TX-PG-04, DB-MIG-01부터 DB-MIG-04

- [ ] **Step 1: Cart 저장, processed-event 저장, cleanup event 발행 실패 지점을 각각 RED로 작성한다**
- [ ] **Step 2: rollback 후 같은 eventId 재처리 성공 테스트를 RED로 작성한다**
- [ ] **Step 3: unique 제약과 Flyway clean/migrate 테스트를 실행한다**
- [ ] **Step 4: 부분 commit이 없도록 최소 트랜잭션 경계 변경만 수행한다**
- [ ] **Step 5: 모든 원자성 및 migration 테스트를 GREEN으로 만든다**
- [ ] **Step 6: 승인된 경우에만 원자성 테스트와 필요한 구현을 commit한다**

### Task 4: 실패 이벤트 payload, Processor, Handler, Kafka

**Files:**

- Modify: order-service/src/test/java/com/prompthub/order/infra/messaging/kafka/event/PaymentFailedPayloadTest.java
- Modify: order-service/src/test/java/com/prompthub/order/application/service/event/PaymentFailedProcessorTest.java
- Modify: order-service/src/test/java/com/prompthub/order/application/service/event/PaymentFailedEventHandlerTest.java
- Modify: order-service/src/test/java/com/prompthub/order/infra/messaging/kafka/PaymentEventConsumerIntegrationTest.java

**Interfaces:**

- Produces: PAY-U-01부터 PAY-U-05, PAY-H-01부터 PAY-H-02, KAFKA-IT-01부터 KAFKA-IT-07

- [ ] **Step 1: userId 호환, nullable metadata, failedAt fallback 테스트를 RED로 작성한다**
- [ ] **Step 2: 성공 ACK와 예외 미ACK/전파 테스트를 RED로 작성한다**
- [ ] **Step 3: Kafka 정상, 중복, 늦은 실패, metadata 최소 payload 테스트를 추가한다**
- [ ] **Step 4: 영구 실패 총 네 번 호출과 DLT header 테스트를 추가한다**
- [ ] **Step 5: 일시 실패 성공과 cleanup 실패 격리 테스트를 추가한다**
- [ ] **Step 6: 최소 production 변경으로 테스트를 GREEN으로 만든다**
- [ ] **Step 7: 승인된 경우에만 이벤트 경계 변경을 commit한다**

### Task 5: 만료 Worker 공통화와 Redis 정책

**Files:**

- Modify: order-service/src/test/java/com/prompthub/order/infra/redis/OrderExpirationWorkerTest.java
- Create: order-service/src/test/java/com/prompthub/order/infra/redis/OrderExpirationRedisPolicyIntegrationTest.java
- Modify only for GREEN: order-service/src/main/java/com/prompthub/order/infra/redis/OrderExpirationWorker.java

**Interfaces:**

- Produces: EXP-U-01부터 EXP-U-03, EXP-REDIS-01부터 EXP-REDIS-03

- [ ] **Step 1: Worker가 compensateTimeout만 호출하는 테스트를 RED로 작성한다**
- [ ] **Step 2: 성공 no-op과 보상 예외 retry 테스트를 작성한다**
- [ ] **Step 3: 실제 Redis retry 증가, DLQ 이동, 성공 cleanup 테스트를 작성한다**
- [ ] **Step 4: 기존 retry/DLQ semantics를 유지하는 최소 변경으로 GREEN을 만든다**
- [ ] **Step 5: 승인된 경우에만 Worker와 Redis 회귀 변경을 commit한다**

### Task 6: 늦은 승인과 성공 시 Cart 제거

**Files:**

- Modify: order-service/src/test/java/com/prompthub/order/application/service/event/PaymentApprovedProcessorTest.java
- Modify: order-service/src/test/java/com/prompthub/order/application/service/event/PaymentEventTransactionIntegrationTest.java
- Modify only for GREEN: order-service/src/main/java/com/prompthub/order/application/service/event/PaymentApprovedProcessor.java

**Interfaces:**

- Produces: APP-PG-01부터 APP-PG-04

- [ ] **Step 1: FAILED → COMPLETED/PAID와 관계없는 Cart 상품 유지 테스트를 RED로 작성한다**
- [ ] **Step 2: 결제 대기 중 재추가 상품 제거와 중복 승인 테스트를 작성한다**
- [ ] **Step 3: 성공 주문의 늦은 실패 no-op을 실제 DB 결과로 검증한다**
- [ ] **Step 4: 최소 production 변경으로 승인 테스트를 GREEN으로 만든다**
- [ ] **Step 5: 승인된 경우에만 승인 수렴 변경을 commit한다**

### Task 7: AFTER_COMMIT Redis 정리

**Files:**

- Modify: order-service/src/test/java/com/prompthub/order/infra/redis/OrderExpirationCleanupAfterCommitIntegrationTest.java
- Modify: order-service/src/test/java/com/prompthub/order/infra/redis/OrderExpirationCleanupListenerTest.java
- Modify only for GREEN: order-service/src/main/java/com/prompthub/order/infra/redis/OrderExpirationCleanupListener.java

**Interfaces:**

- Produces: REDIS-IT-01부터 REDIS-IT-04

- [ ] **Step 1: commit 전 미삭제와 commit 후 삭제 테스트를 RED로 작성한다**
- [ ] **Step 2: rollback 미호출과 Redis 예외 격리 테스트를 작성한다**
- [ ] **Step 3: 성공 no-op cleanup 재시도 테스트를 작성한다**
- [ ] **Step 4: 실제 Redis Testcontainer에서 key 상태를 검증한다**
- [ ] **Step 5: 최소 listener 변경으로 GREEN을 만든다**
- [ ] **Step 6: 승인된 경우에만 AFTER_COMMIT 테스트와 구현을 commit한다**

### Task 8: PostgreSQL 동시성

**Files:**

- Modify: order-service/src/test/java/com/prompthub/order/application/service/order/OrderFailureCompensationConcurrencyTest.java
- Create: order-service/src/test/java/com/prompthub/order/infra/persistence/CartRootLockIntegrationTest.java

**Interfaces:**

- Produces: CONC-PG-01부터 CONC-PG-08

- [ ] **Step 1: 실패 우선과 승인 우선 interleaving을 latch로 고정한다**
- [ ] **Step 2: 실패/timeout 및 동일 eventId 경합을 추가한다**
- [ ] **Step 3: Cart 미존재 생성과 사용자 Cart 추가/삭제 경합을 추가한다**
- [ ] **Step 4: 주문 생성·승인·보상 pairwise lock 테스트를 추가한다**
- [ ] **Step 5: 각 Future에 bounded timeout을 적용한다**
- [ ] **Step 6: 동시성 테스트 전체를 최소 다섯 번 반복해 GREEN을 확인한다**
- [ ] **Step 7: 승인된 경우에만 동시성 테스트와 필요한 최소 구현을 commit한다**

### Task 9: 전체 회귀와 문서 추적

**Files:**

- Modify: order-service/docs/superpowers/plans/2026-07-18-payment-failure-compensation-test-plan.md

**Interfaces:**

- Consumes: 모든 테스트 ID와 Gradle 검증 결과
- Produces: 완료 조건 추적표와 잔여 위험

- [ ] **Step 1: 테스트 ID와 완료 조건 매핑을 실제 클래스에 대조한다**
- [ ] **Step 2: order-service 전체 테스트를 실행한다**
- [ ] **Step 3: order-service 전체 build를 실행한다**
- [ ] **Step 4: git diff --check와 변경 경로 검사를 실행한다**
- [ ] **Step 5: 미구현 시나리오와 승인 필요한 범위를 문서에 기록한다**
- [ ] **Step 6: 승인된 경우에만 최종 테스트·문서 변경을 commit한다**

## 9. 완료 조건 추적표

| 원 요구사항 | 대응 테스트 |
|---|---|
| 네 상품 실패 시 네 개 복구 | COMP-PG-01, KAFKA-IT-01 |
| 바로 구매 단건 복구 | COMP-PG-02 |
| 실패와 timeout이 같은 서비스 사용 | COMP-U-02, COMP-PG-08, EXP-U-01 |
| 주문·상품·Cart·processed-event 원자성 | TX-PG-01부터 TX-PG-04 |
| 기존 Cart 상품 중복 방지 | COMP-PG-03, DB-MIG-02 |
| 중복 이벤트와 Worker 재실행 멱등성 | COMP-U-03, COMP-PG-06, KAFKA-IT-02, EXP-U-02 |
| 보상 후 늦은 승인 우선 | APP-PG-01, CONC-PG-01, CONC-PG-02 |
| 성공 주문의 늦은 실패 no-op | COMP-PG-07, APP-PG-04, KAFKA-IT-03 |
| Redis commit 후 정리 및 실패 격리 | REDIS-IT-01부터 REDIS-IT-04 |
| PostgreSQL 동시성 통과 | CONC-PG-01부터 CONC-PG-08 |
| 전체 test/build 통과 | 10장 검증 명령 |
| 변경 파일이 order-service 내부로 제한 | 10장 변경 범위 검사 |

## 10. 실행 명령과 검증 기준

저장소 루트 /private/tmp/beadv6_6_3JMT_BE-400에서 실행한다.

~~~bash
# 보상 핵심
./gradlew :order-service:test --tests '*OrderFailureCompensation*'

# 승인 및 늦은 이벤트
./gradlew :order-service:test \
  --tests '*PaymentApproved*' \
  --tests '*PaymentEventTransactionIntegrationTest*'

# Kafka
./gradlew :order-service:test \
  --tests '*PaymentEventConsumerIntegrationTest*'

# Redis
./gradlew :order-service:test \
  --tests '*AfterCommit*' \
  --tests '*ExpirationRedisPolicy*'

# 동시성
./gradlew :order-service:test --tests '*Concurrency*'

# 전체 테스트와 빌드
./gradlew :order-service:test
./gradlew :order-service:build
~~~

동시성 테스트는 최소 다섯 번 반복한다.

~~~bash
for i in 1 2 3 4 5; do
  ./gradlew :order-service:test \
    --tests '*Concurrency*' \
    --rerun-tasks || exit 1
done
~~~

변경 범위는 실제 통합 기준 브랜치 origin/feat/#392-seller-admin-settlement와의 merge-base로 검사한다.

~~~bash
BASE_REF=$(git merge-base HEAD origin/feat/#392-seller-admin-settlement)
git diff --name-only "$BASE_REF"

git diff --name-only "$BASE_REF" \
  | awk '!/^order-service\// { print }'
~~~

두 번째 명령의 출력은 비어 있어야 한다. 구현 변경이 아직 commit되지 않은 동안에는 HEAD 범위가 아니라 working tree를 포함하는 git status --short도 함께 확인한다.

## 11. 테스트 안정성 규칙

- 트랜잭션과 lock 테스트에 H2를 사용하지 않는다.
- AFTER_COMMIT 및 동시성 테스트에 테스트 메서드 전체를 감싸는 @Transactional을 사용하지 않는다.
- 비동기 검증에 고정 Thread.sleep을 사용하지 않는다.
- rollback 검증은 새 persistence context에서 수행한다.
- 동시성 테스트는 확률적 반복보다 latch로 핵심 interleaving을 만든다.
- Embedded Kafka 테스트마다 고유 eventId를 사용하고 중복 시나리오에서만 같은 ID를 재사용한다.
- Kafka topic과 Redis key를 테스트 사이에 정리하거나 테스트별 namespace를 사용한다.
- Cart 검증은 collection size뿐 아니라 productId별 row count를 확인한다.
- mock 호출 검증만으로 트랜잭션, unique 제약, PESSIMISTIC_WRITE를 검증했다고 판단하지 않는다.
- DLT 테스트는 record 도착 외에도 원본 topic, key, eventId, exception header를 확인한다.
- Testcontainers 테스트는 Docker 미가용을 성공으로 간주해 skip하지 않는다. 실행하지 못하면 검증 미완료로 보고한다.

## 12. 우선순위

### P0 — merge 차단

- 보상 트랜잭션 원자성
- 네 상품 및 바로 구매 복구
- Cart 중복 방지와 unique 제약
- 보상 후 늦은 승인 최종 수렴
- 성공 주문의 늦은 실패 no-op
- 실패/승인 동시성

### P1 — 기능 완료 필수

- timeout 공통 서비스 위임
- Kafka 멱등성, ACK, retry, DLT
- Redis AFTER_COMMIT 및 실패 격리
- no-Cart 생성 경쟁
- 결제 대기 중 재추가 상품의 승인 시 제거

### P2 — 방어 및 유지보수성

- 혼합 주문상품 상태
- 같은 eventId의 다른 consumer group
- 승인 이후 사용자의 새 Cart 추가 보존
- migration 이전 중복 데이터 정책

## 13. 확정 정책과 추가 승인 지점

### 13.1 확정 정책

| 항목 | 확정값 |
|---|---|
| Flyway 적용 전 기존 중복 row | V5 unique 제약 추가를 명확히 실패시키고 운영 데이터 정리는 별도 작업으로 수행 |
| Kafka 재시도 총 listener 호출 | 최초 한 번 + 추가 세 번 = 총 네 번 |
| no-op 실패 이벤트 처리 이력 | 최초 수신 eventId라면 FAILED/COMPLETED여도 processed-event 저장 |
| Redis cleanup 예외 | listener 내부에서 로깅 후 격리 |
| 실패 시각 우선순위 | payload.failedAt 우선, 없으면 envelope occurredAt |
| 중복 승인 cleanup | DB 상태는 no-op, stale key self-healing을 위해 cleanup 이벤트 재발행 |

### 13.2 추가 승인 필요

- absent Cart 생성 경합에서 사용자 HTTP 요청까지 unique violation을 숨기기 위한 DB upsert 또는 제한 재시도
- 기존 운영 중복 Cart/CartProduct를 자동 삭제하거나 병합하는 migration
- 혼합 상태 주문상품에서 PENDING만 FAILED로 바꾸기 위한 도메인 상태 전이 변경
- Redis Testcontainers 추가에 따른 신규 테스트 의존성과 CI Docker 실행 시간 증가

## 14. 최종 Definition of Done

- [ ] COMP-PG-01부터 COMP-PG-09까지 통과한다.
- [ ] 원자성 fault injection 테스트가 모든 부분 commit을 차단한다.
- [ ] Cart unique 제약이 PostgreSQL에서 실제로 동작한다.
- [ ] 실패 이벤트와 timeout이 동일한 보상 서비스로 수렴한다.
- [ ] 늦은 승인과 늦은 실패 우선순위가 단일·동시성 테스트에서 모두 고정된다.
- [ ] Redis 정리가 commit 이후에만 실행되고 실패가 DB 결과에 영향을 주지 않는다.
- [ ] Kafka manual ACK, 총 네 번 호출, payment-events.DLT 정책이 유지된다.
- [ ] Worker의 기존 Redis retry와 order:expiration:dlq 정책이 유지된다.
- [ ] 동시성 테스트를 다섯 번 반복해도 timeout, deadlock, 중복 row가 없다.
- [ ] ./gradlew :order-service:test가 통과한다.
- [ ] ./gradlew :order-service:build가 통과한다.
- [ ] order-service/** 밖의 변경 파일이 없다.
- [ ] 테스트 시나리오 ID와 완료 조건 추적표가 실제 구현 후에도 일치한다.

## 15. 계획 자체 검토 체크리스트

- [x] 제공된 상태 전이, Cart, 처리 이력, Redis 계약을 테스트 ID에 매핑했다.
- [x] 실제 base package와 현재 테스트 파일명을 사용했다.
- [x] PostgreSQL, Redis, Kafka 경계별 책임을 분리했다.
- [x] RED → GREEN → REFACTOR 순서를 Task별로 고정했다.
- [x] 현재 확정 정책과 추가 승인 필요 범위를 분리했다.
- [x] 구현자가 임의로 채워야 하는 미확정 문구를 사용하지 않았다.
