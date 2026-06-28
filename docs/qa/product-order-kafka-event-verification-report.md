# Product Service / Order Service Kafka Event 검증 보고서

## 1. 검증 범위

이 보고서는 Product Service 이벤트 규약에 맞게 Order Service가 작성되어 있는지 확인한 결과를 정리한다.

검증은 `order-service` 코드와 테스트에서 수행했다. 사용자가 지정한 권한 범위에 따라 `product-service` 코드는 수정하지 않았다.

검증 기준은 다음 이벤트 흐름이다.

- Product Service -> Order Service: `product-events`
  - `PRODUCT_STOPPED`
  - `PRODUCT_DELETED`
  - `PRODUCT_PRICE_CHANGED`
- Order Service -> Product Service: `order-events`
  - `ORDER_PAID`
  - `ORDER_REFUND`

## 2. 검증 근거

실행 명령:

```bash
cd order-service
./gradlew clean build
```

실행 결과:

```text
BUILD SUCCESSFUL
```

주요 테스트 결과 파일:

| 테스트 | 결과 | 확인 내용 |
| --- | --- | --- |
| `ProductEventConsumerIntegrationTest` | 4 tests, 0 failures, 0 errors | `PRODUCT_STOPPED`, `PRODUCT_DELETED`, `PRODUCT_PRICE_CHANGED` embedded Kafka 수신 및 중복 가격 변경 이벤트 수신 |
| `OutboxRelayIntegrationTest` | 2 tests, 0 failures, 0 errors | `ORDER_PAID`, `ORDER_REFUND` outbox 이벤트의 Kafka 발행 및 payload 구조 |
| `OrderPaymentEventServiceTest.HandlePaymentApproved` | 8 tests, 0 failures, 0 errors | 결제 승인 처리, 중복 승인 방지, `ORDER_PAID` outbox 저장 |
| `OrderPaymentEventServiceTest.HandlePaymentRefunded` | 3 tests, 0 failures, 0 errors | 환불 처리, 중복 환불 방지, `ORDER_REFUND` outbox 저장 |
| `OrderProductEventServiceTest` | 1 test, 0 failures, 0 errors | 상품 이벤트 처리 시 기존 `OrderProduct` 스냅샷 불변 |
| `OrderPaymentPersistenceImplTest` | 2 tests, 0 failures, 0 errors | `paymentId` 기준 결제내역 존재 조회 |

## 3. 검증된 부분

### Product Service -> Order Service

`product-events` 수신은 embedded Kafka 테스트로 검증했다.

검증된 항목:

- Order Service가 `product-events` topic의 메시지를 수신한다.
- `PRODUCT_STOPPED` 이벤트가 `handleProductStopped`로 분기된다.
- `PRODUCT_DELETED` 이벤트가 `handleProductDeleted`로 분기된다.
- `PRODUCT_PRICE_CHANGED` 이벤트가 `handleProductPriceChanged`로 분기된다.
- Product Service가 발행하는 root-level payload 구조인 `eventType`, `productId`, `occurredAt`, `previousPrice`, `changedPrice`를 Order Service DTO가 보존한다.
- 동일한 `PRODUCT_PRICE_CHANGED` payload를 2회 수신해도 예외 없이 2회 처리 위임된다.
- 상품 이벤트 처리 서비스는 기존 `OrderProduct`의 `productTitle`, `productType`, `productModel`, `productAmount` 스냅샷을 변경하지 않는다.
- 알 수 없는 `eventType`은 서비스 호출 없이 ack 처리된다.
- JSON 파싱 실패는 ack 없이 예외를 전파한다.

### Order Service -> Product Service

`order-events` 발행은 outbox relay와 embedded Kafka consumer를 통해 검증했다.

검증된 항목:

- `ORDER_PAID` outbox event의 `topic`은 `order-events`다.
- `ORDER_PAID` 발행 성공 후 `OutboxEvent.status`가 `PUBLISHED`로 변경된다.
- `ORDER_PAID` Kafka message key는 `orderId`다.
- `ORDER_PAID` payload에는 `eventId`, `eventType`, `version`, `occurredAt`, `aggregateId`, `payload.orderId`, `payload.products[]`가 포함된다.
- `ORDER_PAID`의 products 배열에는 `productId`가 포함된다.
- `ORDER_REFUND` outbox event의 `topic`은 `order-events`다.
- `ORDER_REFUND` 발행 성공 후 `OutboxEvent.status`가 `PUBLISHED`로 변경된다.
- `ORDER_REFUND` Kafka message key는 `orderId`다.
- `ORDER_REFUND` payload에는 `eventType`, `aggregateId`, `payload.orderId`, `payload.paymentId`, `payload.totalRefundAmount`, `payload.products[].refundAmount`가 포함된다.
- `OutboxRelay`는 발행 실패 시 `retryCount`를 증가시키고, 최대 재시도 도달 시 `FAILED`로 변경하는 테스트가 기존에 통과했다.

### Payment Event 멱등 처리

검증된 항목:

- `payment.approved` 처리 시 Order와 OrderProduct가 `PAID`로 변경된다.
- `payment.approved` 처리 시 `OrderPayment`가 저장되고 `ORDER_PAID` outbox가 저장된다.
- 이미 `PAID`인 주문에 동일 `paymentId` 결제내역이 있으면 중복 승인 이벤트로 보고 저장과 outbox 발행을 생략한다.
- 이미 `PAID`인 주문이어도 같은 `paymentId` 결제내역이 없으면 중복으로 보지 않고 `ORDER_ALREADY_PROCESSED` 예외를 발생시킨다.
- `payment.refunded` 처리 시 Order와 OrderProduct가 `REFUNDED`로 변경되고 `ORDER_REFUND` outbox가 저장된다.
- 이미 `REFUNDED`인 주문에 환불 이벤트가 다시 들어오면 `ORDER_REFUND` outbox를 중복 저장하지 않는다.
- `OrderPaymentRepository`와 JPA persistence가 `paymentId` 기준 존재 여부 조회를 지원한다.

## 4. 검증되지 않은 부분

다음 항목은 이번 테스트로 검증하지 않았다.

- 실제 실행 중인 Product Service가 `ORDER_PAID` 또는 `ORDER_REFUND`를 수신하는지 여부
  - 이유: 사용자가 `product-service` 변경 금지를 명시했고, 이번 검증은 `order-service` 테스트 범위에서 수행했다.
- Product Service의 `salesCount` 증가/감소 실제 반영 여부
  - 이유: Product Service consumer와 DB 상태 검증은 Product Service 런타임 및 저장소 검증이 필요하다.
- 실제 Kafka broker의 consumer group lag
  - 이유: embedded Kafka 테스트는 메시지 처리 성공과 ack 경로를 검증하지만, 운영 broker의 lag CLI 확인은 수행하지 않았다.
- 실제 로컬/운영 Kafka topic 존재 여부
  - 이유: embedded Kafka가 테스트 topic을 생성해 사용했으며, `localhost:9092` Kafka CLI 점검은 수행하지 않았다.
- Product Service가 실제로 발행한 바이너리/런타임 메시지를 Order Service가 수신하는 end-to-end 실행
  - 이유: Product Service를 실행하거나 상태 변경 API를 호출하지 않았다.
- 실패 메시지가 실제 DLT topic으로 이동하는 end-to-end 경로
  - 이유: 단위 테스트로 실패 처리 정책은 확인했지만, DLT topic 소비까지는 검증하지 않았다.
- `payment.canceled`, `payment.failed`, 취소 실패, 환불 실패 이벤트의 전체 정책
  - 이유: 이번 요청의 핵심 범위는 Product/Order 간 Kafka 이벤트 정합성과 `ORDER_PAID`/`ORDER_REFUND`였다.

## 5. 판정

| 항목 | 판정 | 근거 |
| --- | --- | --- |
| Product 이벤트 payload 규약과 Order Service DTO 일치 | 검증됨 | `ProductEventConsumerTest`, `ProductEventConsumerIntegrationTest` |
| Product 이벤트 수신 분기 | 검증됨 | `PRODUCT_STOPPED`, `PRODUCT_DELETED`, `PRODUCT_PRICE_CHANGED` embedded Kafka 테스트 |
| Product 이벤트 중복 수신 안전성 | 부분 검증 | 동일 `PRODUCT_PRICE_CHANGED` 2회 수신 및 처리 위임 확인. 별도 DB mutation이 없다는 점은 서비스 단위에서 확인 |
| 기존 OrderProduct 스냅샷 불변 | 검증됨 | `OrderProductEventServiceTest` |
| ORDER_PAID outbox 저장 및 발행 payload | 검증됨 | `OutboxEventAppenderTest`, `OutboxRelayIntegrationTest` |
| ORDER_REFUND outbox 저장 및 발행 payload | 검증됨 | `OutboxEventAppenderTest`, `OutboxRelayIntegrationTest` |
| Outbox 발행 성공 상태 변경 | 검증됨 | `OutboxRelayIntegrationTest` |
| Outbox 발행 실패 retry/FAILED 처리 | 검증됨 | `OutboxRelayTest` |
| paymentId 기준 승인 멱등 처리 | 검증됨 | `OrderPaymentEventServiceTest`, `OrderPaymentPersistenceImplTest` |
| 실제 Product Service consumer 처리 | 미검증 | Product Service 실행/수정 없이 Order Service 계약 테스트로만 확인 |
| 실제 운영 Kafka lag | 미검증 | embedded Kafka 테스트만 수행 |

## 6. 결론

Order Service는 Product Service가 발행하는 `product-events`의 현재 payload 구조에 맞게 수신하도록 검증됐다.
특히 `eventType`을 Order Service DTO에 보존하고, Product 이벤트 수신 시 기존 주문 스냅샷을 변경하지 않는 정책도 테스트로 고정했다.

Order Service가 Product Service로 전달할 `ORDER_PAID`, `ORDER_REFUND` 이벤트는 outbox 저장, Kafka 발행, 주요 payload 필드 구조까지 검증됐다.
다만 실제 Product Service 프로세스가 해당 메시지를 받아 `salesCount`를 변경하는지까지는 이번 범위에서 검증하지 않았다.

현재 기준에서 “Order Service가 Product Service 이벤트 규약에 맞게 작성되어 있는가”는 Order Service 코드와 embedded Kafka 테스트 기준으로 검증됐다.
“두 서비스가 실제 런타임에서 end-to-end로 연동되는가”는 별도의 로컬 인프라 실행과 Product Service runtime 검증이 필요하다.
