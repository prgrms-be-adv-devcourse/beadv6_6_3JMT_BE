# Order Service 다건 부분 환불 테스트 계획

## 범위

- `POST /api/v2/orders/{orderId}/refund` 요청 검증과 응답 상태
- `order_refund : order_refund_product = 1:N` 저장 원자성
- 상품별 `REFUND_REQUESTED`, `PAID`, `REFUNDED` 상태 전이와 콘텐츠 잠금
- Payment 성공·실패 이벤트의 멱등 처리와 Outbox 일관성
- Payment 환불 상태 gRPC 장애 격리와 재조정 정책
- PostgreSQL 비관적 잠금, `SKIP LOCKED`, lease 복구

## 자동화된 검증

| 계층 | 주요 시나리오 | 테스트 |
|---|---|---|
| Domain | 다건 요청 금액 합산, 중복 ID 거부, terminal overwrite 거부, UNKNOWN/manual review | `OrderRefundTest`, `OrderProductTest`, `OrderTest` |
| Application | 신규 요청, 동일 요청 재사용, 부분 겹침, 관계 불일치, 성공·실패 전체 batch 반영 | `CreateOrderRefundCommandHandlerTest`, `OrderRefundPolicyTest`, `OrderRefundCompletionServiceTest`, `OrderRefundFailureServiceTest` |
| HTTP | BUYER trusted header, snake_case body, 202/200, 400/401/403/404/409 | `OrderRefundControllerTest`, `OrderWebConfigTest` |
| Kafka | `PAYMENT_PARTIAL_REFUNDED`, `PAYMENT_PARTIAL_REFUND_FAILED`, legacy `PAYMENT_REFUNDED` 분리 | `PaymentEventRouterTest`, consumer integration tests |
| gRPC | PROCESSING/COMPLETED/FAILED/NOT_FOUND, deadline, unavailable, circuit-open, bulkhead-full, ISO timestamp | `PaymentRefundGrpcClientAdapterTest`, config/resilience tests |
| Reconciliation | 2/5/10/20분, UNKNOWN 30분/1시간/3시간, manual review, terminal no-op | `RefundReconciliationPolicyTest`, `RefundReconciliationResultServiceTest`, scheduler/worker tests |
| PostgreSQL | Flyway V2 적용, 동일·겹침 요청 직렬화, disjoint claim, 1분 lease 복구 | `OrderRefundMigrationTest`, `OrderRefundConcurrencyIntegrationTest`, `RefundReconciliationClaimConcurrencyTest` |
| Observability | 접수·완료·실패·UNKNOWN·gRPC 결과·수동 확인·재조정 지연 metric | `MicrometerRefundMetricsAdapterTest`, application/worker tests |

## 실행 명령

```bash
./gradlew :order-service:test
./gradlew :order-service:build
```

PostgreSQL 전용 테스트는 Docker가 필요하다. Docker가 없으면 Testcontainers 테스트는 skip되며, H2/단위 테스트 성공과 PostgreSQL 동시성 검증 성공을 동일하게 보고하지 않는다.

```bash
./gradlew :order-service:test \
  --tests "com.prompthub.order.application.service.refund.OrderRefundConcurrencyIntegrationTest" \
  --tests "com.prompthub.order.infra.persistence.RefundReconciliationClaimConcurrencyTest"
```

## 수동·연동 확인

- Gateway가 `/api/v2/orders/**`를 Order Service로 전달하고 BUYER header를 주입하는지 확인한다.
- 외부 `/api/v2/payments/{paymentId}/refund` 경로가 차단되는지 확인한다.
- Payment가 `refundRequestId`를 PG 멱등성 키로 사용하고 batch 성공·실패 이벤트를 발행하는지 확인한다.
- Payment가 `PaymentRefundQueryService.GetRefundStatus` 서버를 실제로 제공하는지 확인한다.
- Settlement listener와 공통 `EventMessage` envelope의 `ORDER_REFUNDED` 호환성을 확인한다.

## 합격 기준

- 요청 대상 전체가 하나의 트랜잭션으로 성공하거나 전체 롤백된다.
- 동일 요청은 환불·상품 행·Outbox를 중복 생성하지 않는다.
- 성공은 해당 batch 상품만 환불하며 실패는 모든 대상 상품을 `PAID`로 복구한다.
- gRPC 호출 중 DB claim 트랜잭션을 유지하지 않는다.
- terminal 결과를 반대 결과로 덮어쓰지 않는다.
- Docker 사용 환경에서 PostgreSQL 동시성 테스트가 모두 통과한다.
