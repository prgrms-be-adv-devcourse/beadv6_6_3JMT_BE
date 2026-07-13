# 구현 기록: 주문-결제 흐름 재설계 (payment-service)

> 설계 스펙: `11-order-payment-flow-redesign.md`. 본 문서는 실제 구현 결과·커밋 단위·트러블슈팅·스펙 대비 편차를 기록한다.
> 브랜치: `feat/#237-order-created-event-grpc-fallback` / 작성일: 2026-07-08

## 1. 커밋 단위 (의미 있는 단위로 분할)

| 커밋 | 내용 | 검증 |
|---|---|---|
| `docs:` 스펙 추가 | `11-...redesign.md` 스펙 문서 | — |
| `feat:` gRPC 코드젠 | `build.gradle` protobuf 미러링 + `order_internal.proto` | `compileJava` |
| `feat:` gRPC 클라이언트 | `OrderGateway`/`OrderPaymentInfo`, `OrderGrpcClientAdapter/Config`, 에러코드 2종, yaml | `compileJava` |
| `feat:` 주문 스냅샷 | `OrderSnapshot`/enum/repo/adapter, `RecordOrderSnapshotUseCase/Service` | 영속성 테스트 |
| `feat:` order-events 컨슈머 | `OrderEventConsumer`, `KafkaConfig` 컨슈머 팩토리+DLT, `OrderCreatedMessage` | 통합 테스트 3종 |
| `feat:` confirm 재설계 | amount 제거·스냅샷·본인검증·재결제·멱등 일원화·금액 단일화, 엔티티/repo/DTO/schema.sql, 기존 테스트 6종 수정 | 전체 테스트 |
| `feat:` payment.failed | `PaymentFailedEvent/Message`, TX3 발행, `onPaymentFailed` 리스너 | 통합 테스트 |
| `docs:` 문서 반영 | `.claude/docs`·`rules`·`../docs/architecture` + 본 문서 | — |

각 커밋은 컴파일·관련 테스트 그린 상태로 분리했다.

## 2. 스펙 대비 편차 (구현 중 결정)

| 항목 | 스펙 | 구현 | 사유 |
|---|---|---|---|
| 본인 검증 403 에러코드 | "기존 권한 에러코드 재사용" | **신규 `NOT_ORDER_OWNER`(PAY010)** 추가 | 기존 `UNAUTHORIZED_REFUND`(PAY006) 메시지가 "본인 결제 건만 환불…"이라 confirm 문맥에 부정확. 재사용 시 메시지 오해 소지 → 전용 코드가 정확. (협의 필요 시 되돌릴 수 있음) |
| gRPC 어댑터 `@Profile` | order-service는 `@Profile({dev,prod})` 게이팅 | **게이팅 없이 항상 등록** | payment 테스트는 grpc-inprocess 프로파일이 아니라 `@MockitoBean OrderGateway`로 대체. 게이팅하면 기본 프로파일 bootRun 시 `OrderGateway` 빈 누락으로 컨텍스트 실패. 항상 등록 + 테스트에서 mock이 어댑터를 대체하는 방식이 견고. |
| `@EnableKafka` | (미명시) | **명시하지 않고 자동설정 의존** | 아래 트러블슈팅 3 참조. |

## 3. 트러블슈팅 / 설계 판단

### 3.1 스냅샷 upsert의 트랜잭션 오염 방지 (REQUIRES_NEW)
confirm의 TX1 안에서 gRPC 폴백으로 스냅샷을 저장할 때, `order_id` 유니크 충돌(그 사이 `ORDER_CREATED` 이벤트가 먼저 저장)이 나면 `DataIntegrityViolationException`이 발생한다. 이 예외가 TX1을 `rollback-only`로 오염시키면 이후 재조회 쿼리가 `UnexpectedRollbackException`으로 실패한다.
→ `RecordOrderSnapshotService.record`를 `@Transactional(propagation = REQUIRES_NEW)`로 분리. 충돌은 내부 트랜잭션만 롤백하고, confirm은 정상 TX1에서 스냅샷을 재조회해 회복한다.

### 3.2 JPA 슬라이스 테스트에서 Kafka 리스너 미기동 (`@EnableKafka` 생략)
`AbstractJpaTest`는 `KafkaAutoConfiguration`을 제외한다. 만약 `KafkaConfig`에 `@EnableKafka`를 명시하면 자동설정 제외와 무관하게 리스너 인프라(BPP)가 등록되어, `@KafkaListener`가 없는 브로커(localhost:9092)에 연결을 시도한다.
→ `@EnableKafka`를 **명시하지 않고** Spring Boot 자동설정(`KafkaAnnotationDrivenConfiguration`)에 위임. 통합 테스트(Kafka 자동설정 활성)에선 리스너가 기동하고, JPA 슬라이스(제외)에선 기동하지 않아 깔끔하다. 커스텀 컨슈머 팩토리 빈은 수동 `@Configuration`이라 양쪽에 생성되지만, 리스너 컨테이너 시작은 자동설정 유무로 갈린다. `DefaultErrorHandler`가 요구하는 `KafkaTemplate<String,Object>`는 JPA 테스트의 `@MockitoBean`(raw) mock으로 충족된다.

### 3.3 HttpStatus 422 enum 명칭 변경 (Spring 7)
통합 테스트에서 `assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)`가 실패. Spring Framework 7(Boot 4.1)에서 422가 `UNPROCESSABLE_CONTENT`로 명칭 변경되어 enum 상수 비교가 어긋났다(값은 동일 422).
→ `response.getStatusCode().value()).isEqualTo(422)`로 숫자 비교하여 명칭 의존 제거.

### 3.4 기존 통합 테스트의 스냅샷 선행 조건
confirm이 스냅샷을 요구하도록 바뀌어, 기존 `ConfirmPaymentIntegrationTest`·`RefundPaymentIntegrationTest`의 승인 호출이 그대로면 503으로 실패한다.
→ 각 테스트에서 confirm 전에 `OrderSnapshot`(buyerId=요청자, EVENT source)을 사전 저장하고, 요청 body에서 `amount`를 제거. 결제 조회는 `findByIdempotencyKey` 제거에 맞춰 응답의 `paymentId`(confirm) 또는 `orderId` 필터(refund)로 대체.

### 3.5 `pg_tx_id` 유니크 인덱스와 dev 데이터
`uk_payment_pg_tx_id`는 기존 `payment` 행에 `pg_tx_id` 중복이 없어야 생성된다(정상 흐름은 시도마다 고유 paymentKey라 중복 없음). 중복이 있으면 인덱스 생성이 실패하므로 dev DB 정리 또는 재기동 초기화가 필요. 테스트(Testcontainers)는 매번 초기 상태라 무관.

## 4. E2E 미완 (order-service 후속)
payment-service 코드는 계약(§2)에 맞춰 준비 완료. 아래가 order-service에 반영되기 전까지 실서비스 end-to-end는 미완이다.

- `ORDER_CREATED` 평면 메시지 발행(§2.1)
- gRPC 서버 `GetOrderPaymentInfo`(포트 9083, §2.2)
- payment 이벤트 구독 토픽 `payment.events`(dot) → `payment-events`(dash) 통일
- `payment.failed` 소비 → Order PENDING→FAILED, `handlePaymentApproved` FAILED→PAID 복귀 허용

## 5. 테스트 결과
`../gradlew :payment-service:test` 전체 그린. 신규: `OrderSnapshotJpaRepositoryTest`, `OrderEventConsumerIntegrationTest`(저장/중복/타입무시), `ConfirmPaymentIntegrationTest`(정상/payment.failed). 수정: `ConfirmPaymentServiceTest`, `PaymentTest`, `PaymentJpaRepositoryTest`, `RefundPaymentServiceTest`, `PaymentControllerTest`, `RefundPaymentIntegrationTest`.
