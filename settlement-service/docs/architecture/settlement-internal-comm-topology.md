# 정산 도메인 내부통신 토폴로지 (현재 상태)

정산 도메인이 세 모듈로 분산된 뒤의 **이벤트(Kafka)와 내부통신(gRPC)** 실제 구현 상태를 정리한다.
이 문서는 "지금 코드가 어떻게 통신하는가"의 단일 현황판이다. 설계 배경·결정은 각 trade-off 문서를,
계약 상세(proto 전문·필드표)는 `integration-catalog.md` 를 본다.

대상 세 도메인:

- **정산 본체** — `settlement-service` (정산 계산·배치·발행)
- **셀러 정산** — `user-service` 의 `sellersettlement` 패키지 (판매자용 정산 조회·지급요청)
- **어드민 정산** — `admin-service` 의 `settlement` 패키지 (운영자용 정산 조회·상태변경)

> 공통 이벤트 래퍼 `EventMessage<T>`(`common-module`): `eventId·eventType·occurredAt·aggregateType·aggregateId·payload`.
> 규칙은 `common-kafka-event-message.md`.

---

## 1. 한눈에 보기

```
                         settlement-events (Kafka)
                         EventMessage<SettlementCreatedPayload>
                         eventType=SETTLEMENT_CREATED
  ┌───────────────────┐  ─────────────────────────────▶  ┌────────────────────────────┐
  │ settlement-service│  Transactional Outbox              │ user-service               │
  │  (정산 본체)       │  배치 Step flush (#301)             │  sellersettlement (셀러 정산)│
  └───────┬───────────┘                                    └───────────┬────────────────┘
          │ gRPC GetSettleableLines (#260)                             │ gRPC GetSellerStats
          │  → order-service (서버 미구현·요청대기)                      │  → product-service (서버 리네임 대기)
          ▼                                                            ▼
   order-service                                               product-service
   (OrderQueryService 신설 요청)                      (ProductQueryService: CountBySeller→GetSellerStats 리네임 대기)

  ┌────────────────────────────┐
  │ admin-service (어드민 정산)  │  ──── DB 직접(JPA read-side) ────▶  정산 테이블
  └────────────────────────────┘       이벤트·gRPC 미경유 (admin-data-access.md)
```

핵심: **정산 본체 → 셀러 정산** 은 Kafka 이벤트, **정산 본체·셀러 정산 → order·product** 는 gRPC,
**어드민 정산** 은 통신 없이 DB 직접 접근이다.

---

## 2. 현황 매트릭스

| 도메인 | Kafka 발행 | Kafka 구독 | gRPC 서버 | gRPC 클라이언트 |
|---|---|---|---|---|
| **settlement-service** | ✅ Outbox 구현(#301) — `settlement-events` / `SETTLEMENT_CREATED` | ⚠️ 비활성(기본 OFF) — `order-events`, pull로 대체 중 | ❌ 없음 | ⚠️ order만 구현·상대 서버 대기 |
| **user `sellersettlement`** | ❌ 없음 | ✅ 구현(기본 OFF) — `settlement-events` | ❌ 없음(셀러조회 서버는 `seller` 패키지) | ⚠️ product `GetSellerStats`(서버 리네임 대기) |
| **admin `settlement`** | ❌ 없음 | ❌ 없음 | ❌ 없음 | ❌ 없음 — DB 직접 접근 |

범례: ✅ 구현·동작 가능(게이트 별개) · ⚠️ 비활성/부분(기본 OFF 또는 상대 부재) · ❌ 없음

---

## 3. 도메인별 상세

### 3-1. settlement-service (정산 본체)

**발행 (Kafka) — 구현됨**

| 항목 | 값 |
|---|---|
| 어댑터 | `infrastructure/messaging/kafka/producer/KafkaSettlementEventPublisher` (`SettlementEventPublisher` 포트 구현) |
| 적재 | `Settlement`·SourceLine 연결과 같은 트랜잭션에서 `settlement_outbox_event`에 완성 JSON 저장 |
| 토픽 | `settlement-events` (`settlement.kafka.producer.topic`) |
| 래퍼 | `EventMessage<SettlementCreatedPayload>` |
| eventType | `SETTLEMENT_CREATED` |
| aggregateType / key | `SETTLEMENT` / `settlementId` |
| 페이로드 | `SettlementCreatedPayload` (settlementId·sellerId·periodStart·periodEnd·productCount·totalAmount·settlementTotalAmount·feeTotalAmount·refundAmount·calculatedAt) |
| 발행 시점 | Job 시작 `retryPendingOutboxStep`(이전 PENDING), Job 마지막 `flushCurrentBatchOutboxStep`(현재 배치 PENDING) |
| 실패정책 | broker ack 동기 확인. 1~2회 `PENDING`, 3회 `FAILED`; `outboxRedriveJob(eventId)`로 지정 재처리 |
| 멱등/전달 | Outbox PK = JSON `eventId`, 저장 원문 재발행(at-least-once). user는 `settlementId` 유니크로 중복 흡수 |

**구독 (Kafka) — 비활성(기본 OFF), gRPC pull로 대체 중**

| 항목 | 값 |
|---|---|
| 컨슈머 | `infrastructure/messaging/kafka/consumer/order/OrderEventConsumer` |
| 토픽 | `order-events` (`ORDER_PAID` / `ORDER_REFUNDED`) |
| 게이트 | `settlement.kafka.listener.order.enabled` — **기본값 false** |
| 래퍼 | 공통 `EventMessage` 가 아니라 자체 `OrderEventEnvelope`(order 발행 포맷) |
| 처리 | `SettlementSourceUseCase.recordOrderPaid / recordOrderRefunded` → `settlement_source_line` 적재 |
| 상태 | #260 에서 **gRPC pull 로 대체**. order 서버 가동 전까지 유일한 폴백이라 코드·DTO 는 남겨두고 비활성 |

**gRPC 클라이언트 — order pull 구현됨(상대 서버 대기)**

| 항목 | 값 |
|---|---|
| Config | `infrastructure/client/order/config/OrderGrpcClientConfig` — `@ImportGrpcClients(target="order-service")` |
| 어댑터 | `infrastructure/client/order/OrderSettlementQueryClient` (`OrderSettlementQueryPort` 구현) |
| 호출 | `GetSettleableLines(period)` → `settlement_source_line` bulk 적재(멱등키 `orderProductId+eventType` 로컬 파생) |
| 트리거 | 정산 배치 첫 스텝 `loadSettlementSourceStep` (`LoadSettlementSourceTasklet`) |
| 실패정책 | `StatusRuntimeException` → `SettlementException(SETTLEMENT_SOURCE_QUERY_FAILED)` **throw**(배치 중단, 빈값 폴백 아님 — 조용한 0건 정산 방지) |
| 채널 | `grpc.client.order-service.address` |
| 상태 | **정산측 완비, order-service 에 `OrderQueryService` 서버 미구현 → 요청 대기** (계약: #260 이슈 코멘트) |

**gRPC 서버** — 없음. (`grpc.server.port` yml 선언은 있으나 등록된 비즈니스 서비스 없음.
`grpc/order/order_query.proto`(루트 공유) 는 서버 계약이 아니라 정산 클라이언트 스텁 생성용 — order 서버 미구현이라 유일본.)

> **미사용 채널:** yml 에 `grpc.client.user-service`·`grpc.client.product-service` 채널이 선언돼 있으나,
> 이를 호출하는 클라이언트 코드는 settlement-service 에 **없다**(선언만 남은 상태 — §4-2 참고).

### 3-2. user-service `sellersettlement` (셀러 정산)

**발행 (Kafka)** — 없음. (DLT 재발행용 `dltKafkaTemplate` 빈만 있고 비즈니스 발행은 없다.)

**구독 (Kafka) — 구현됨(기본 OFF)**

| 항목 | 값 |
|---|---|
| 컨슈머 | `sellersettlement/infrastructure/messaging/kafka/consumer/settlement/SettlementEventConsumer` |
| 토픽 | `settlement-events` (`user.kafka.consumer.settlement.topic`) — 정산 본체 발행 토픽과 일치 |
| 게이트 | `user.kafka.listener.settlement.enabled` — **기본값 false**(통합 검증 시 true) |
| 래퍼 | 공통 `EventMessage<SettlementCreatedPayload>` (수동 `readTree` 역직렬화) |
| 처리 | `eventType == SETTLEMENT_CREATED` → `SeedSellerSettlementUseCase.seed(payload)` → `seller_settlement` 시딩 |
| 페이로드 | 자체 `SettlementCreatedPayload`(정산 본체 발행 DTO 와 필드 미러) |

**gRPC 클라이언트 — 구현됨(상대 서버 리네임 대기)**

| 항목 | 값 |
|---|---|
| Config | `sellersettlement/infrastructure/grpc/ProductStatsGrpcClientConfig` — `@ImportGrpcClients(target="product-service")` |
| 어댑터 | `sellersettlement/infrastructure/grpc/ProductStatsGrpcClient` (`ProductStatsClient` 구현) |
| 호출 | `GetSellerStats(sellerId)` → 셀러 등록 상품 수(`product_count`) + 판매건수(`sales_count`) |
| 사용처 | 판매자 정산 요약(#267) `registeredPromptCount`·`totalSalesCount` |
| 실패정책 | `StatusRuntimeException` → `SellerProductStats.empty()`(0) **빈값 폴백**(표시용 참고 데이터라 요약 조회를 막지 않음) |
| 채널 | `grpc.client.product-service.address` |
| 상태 | **계약·클라이언트는 `GetSellerStats`. product 서버는 아직 `ProductQueryGrpcService.countBySeller` 라 서버 리네임 전까지 wire 불일치(UNIMPLEMENTED) — 서버 리네임 대기(조율됨).** 응답 `sales_count`(#262)도 서버 확장 대기 |

**gRPC 서버** — `sellersettlement` 패키지 자체엔 없음. (셀러 정보 조회 서버 `SettlementSellerQueryGrpcService`(GetSellers)는 user-service 의 별도 `seller` 패키지에 있고 live 다. §4 참고.)

### 3-3. admin-service `settlement` (어드민 정산)

**Kafka·gRPC 전부 없음 — DB 직접 접근.**

- `@KafkaListener`·`KafkaTemplate`·`@GrpcService`·`BlockingStub`·`@ImportGrpcClients`·proto 모두 없음.
- `SettlementApplicationService` 는 `SettlementRepository`·`SettlementQueryRepository`·`SettlementSourceRepository`(JPA)만 의존 — 외부 client/messaging import 없음.
- 어드민 정산은 정산 테이블을 **JPA read-side 로 직접 조회**한다. 정산 본체의 발행 이벤트를 구독하지도, gRPC 로 조회하지도 않는다. 데이터 동기화는 DB 스키마에 의존한다.
- 결정 배경: `../trade-offs/admin-data-access.md`. (어드민이 gRPC 가 아니라 DB 를 직접 보는 이유 — 스키마가 사실상 계약이다.)

---

## 4. 요청 대기 · 미완 항목

지금 "한쪽만 준비되어 실제로는 아직 못 붙는" 통신을 명시한다.

1. **settlement → order `GetSettleableLines`** (#260)
   - settlement 클라이언트·포트·배치 스텝 완비. **order-service 에 `OrderQueryService` 서버가 없어 요청 대기.**
   - order 팀 필요 작업: `order_product.paidAt` 추가 + `GetSettleableLines` gRPC 서버 신설(계약: #260 이슈 코멘트).

2. **user `sellersettlement` → product `GetSellerStats`**
   - 계약·클라이언트를 `GetSellerStats` 로 리네임했다(규칙 정합). **product 서버는 아직 `CountBySeller` 라, product 팀이 서버를 `GetSellerStats` 로 바꾸기 전까지 wire 불일치(UNIMPLEMENTED) — 서버 리네임 대기(조율됨).**
   - 응답 `sales_count`(#262 확장 필드)도 서버가 아직 안 채워 0 으로 내려온다 — 필드 확장도 함께 대기.

3. **settlement → user `GetSellers`** (판매자명 조회, 참고 데이터)
   - user-service `seller` 패키지에 서버(`SettlementSellerQueryGrpcService`)가 **live** 이나, settlement-service 에 이 스텁을 호출하는 **클라이언트가 아직 없다**(yml `grpc.client.user-service` 채널도 미사용). → 정산측 클라이언트 신설 시 붙는다.

4. **settlement yml 의 `grpc.client.product-service` 채널**
   - 선언만 있고 이를 쓰는 클라이언트 코드가 settlement-service 에 없다(미사용). 셀러/상품 참고 조회가 셀러 정산(user-service)으로 이관되며 settlement 본체에는 해당 클라이언트가 남지 않았다.

> **왜 §4-3·§4-4 처럼 settlement 본체에 seller/product 클라이언트가 없나:** 판매자명·상품수 같은
> 참고 조회는 **셀러 정산이 user-service 로 이관**되며 그쪽(`sellersettlement`)에서 수행된다. settlement
> 본체가 직접 조회하던 초기 설계(`integration-catalog.md` §2)는 이관으로 대체됐다. 본체가 order 원천을
> pull 하는 것(§3-1)만 settlement-service 에 남았다.

---

## 5. 관련 문서

- 계약 상세(proto 전문·필드표): `integration-catalog.md`
- 공통 이벤트 래퍼 규칙: `common-kafka-event-message.md`
- order 원천 pull 결정 배경: `../trade-offs/order-data-sourcing.md`
- 동기 전송(gRPC) 선택 근거: `../trade-offs/internal-sync-transport.md`
- 어드민 DB 직접 접근 배경: `../trade-offs/admin-data-access.md`
- 셀러 정산 user-service 이관 배경: `../trade-offs/seller-settlement-separation.md`
