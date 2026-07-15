# 결제 조회 gRPC 엔드포인트 구현 계획

order-service가 Kafka `payment-events`(`PAYMENT_APPROVED`/`PAYMENT_FAILED`) 이벤트를 못 받았을 때 payment-service에 직접 물어 결제 상태를 복구할 수 있도록, 기존 `PaymentQueryService`(9084, `GetRefund` 제공 중)에 결제 단건 조회 rpc `GetPayment`를 추가한다.

---

## 배경 및 목표

- payment-service는 이미 order-service향 gRPC 서버(`PaymentQueryService`, 9084)를 운영 중이다 — 환불 이벤트 유실 폴백용 `GetRefund`(#16, 커밋 `920aaf43`/`ec7d3ba7`)가 선례다.
- `GetRefund`는 환불(부분/전체)만 다룬다. 결제 승인(`PAYMENT_APPROVED`)·실패(`PAYMENT_FAILED`) 이벤트가 유실됐을 때는 대응하는 조회 경로가 없다(이슈 #344).
- 이번 작업은 같은 서버·같은 서비스 클래스에 `GetPayment` rpc 하나만 추가한다 — 새 서버/포트 불필요.
- **order-service 쪽 gRPC 클라이언트 구현은 이번 작업 범위 밖**이다(다른 서비스 소스 코드 수정 금지 원칙). payment-service는 계약을 소유(서버)하므로 proto 계약 정의 + 서버 구현까지가 범위다.

---

## 확정 사항 (브레인스토밍 결론)

1. **조회 키**: `orderId` 단일 키. order-service는 유실된 이벤트의 `paymentId`를 모르고 봉투의 `aggregateId`(=orderId)만 갖고 있으므로 orderId로 조회한다.
2. **동일 orderId에 여러 Payment 존재 시**: `ConfirmPaymentService`가 FAILED 상태에서 재결제를 허용하므로(`BLOCKING_STATUSES`에 FAILED 미포함) 한 orderId에 여러 Payment row가 쌓일 수 있다. 이 경우 **가장 최근 1건**(`created_at` 기준 최신)을 반환한다 — order-service가 알고 싶은 건 "지금 이 주문의 결제가 어떻게 됐는지"이므로 최신이 곧 현재 상태다.
3. **응답 설계**: 이벤트별(`GetApprovedPayment`/`GetFailedPayment`) 분리 대신 **통합 `GetPayment` 1개**로 간다. `Payment.status`를 Kafka `eventType`처럼 판별자로 써서 order-service가 분기하면 되므로, Payment 엔티티 필드와 거의 1:1 대응하는 단순 조회로 충분하다.
4. **금액 필드**: `amount` 하나만 두고 `approvedAmount` 기준(PAID일 때만 값 존재, 그 외 0 — 미사용 필드). `PAYMENT_APPROVED` 이벤트 payload의 `amount`와 동일 의미. `totalAmount`는 order-service가 이미 자기 주문 데이터로 알고 있으므로 넣지 않는다.
5. **nullable 필드 처리**: `GetRefundResponse`의 `refunded_at` 관례를 그대로 따른다 — proto3 `optional` 키워드를 새로 도입하지 않고, 값이 있을 때만 `set`, 없으면 필드 기본값(빈 문자열/0)으로 둔다. 소비자는 `status`로 어떤 필드가 유효한지 판단.
6. **패키지 위치**: 기존 `infrastructure.grpc.PaymentQueryGrpcService`에 메서드만 추가(신규 클래스 아님).
7. **에러 코드**: 신규 코드 불필요 — 기존 `PaymentErrorCode.PAYMENT_NOT_FOUND` 재사용(해당 orderId로 Payment가 하나도 없는 경우).

---

## 1. proto 계약 변경

`../grpc/payment/payment_query.proto`에 rpc·message 추가:

```proto
service PaymentQueryService {
  rpc GetRefund(GetRefundRequest) returns (GetRefundResponse);
  rpc GetPayment(GetPaymentRequest) returns (GetPaymentResponse);
}

message GetPaymentRequest {
  string order_id = 1;
}

message GetPaymentResponse {
  string payment_id = 1;
  string order_id = 2;
  string user_id = 3;
  string status = 4;       // Payment.status (PAID, FAILED, PARTIAL_REFUNDED 등)
  int32 amount = 5;        // approvedAmount 기준. PAID 아니면 0(미사용 필드)
  string approved_at = 6;  // ISO 8601, PAID일 때만 값 존재
  string failed_at = 7;    // ISO 8601, FAILED일 때만 값 존재
}
```

`../grpc/README.md` 레이아웃 표 갱신:

```
└── payment/payment_query.proto    ← PaymentQueryService.GetRefund/GetPayment (소유: payment)
```

---

## 2. domain / repository 변경

```java
// domain/repository/PaymentRepository.java
Optional<Payment> findLatestByOrderId(UUID orderId);
```

- `infrastructure/persistence/PaymentJpaRepository.java`: `Optional<Payment> findTopByOrderIdOrderByCreatedAtDesc(UUID orderId);` (Spring Data 파생 쿼리, `created_at` 내림차순 1건)
- `infrastructure/persistence/PaymentRepositoryAdapter.java`: `findLatestByOrderId` → 위 파생 쿼리 위임 구현 추가.

---

## 3. application 계층

- `application/dto/command/GetPaymentCommand.java` (신규 record): `GetPaymentCommand(UUID orderId)`.
- `application/dto/result/PaymentQueryResult.java` (신규 record): `PaymentQueryResult(UUID paymentId, UUID orderId, UUID userId, String status, Integer amount, OffsetDateTime approvedAt, OffsetDateTime failedAt)`.
- `application/usecase/GetPaymentUseCase.java` (신규 인터페이스): `PaymentQueryResult getPayment(GetPaymentCommand command)`.
- `application/service/GetPaymentService.java` (신규 구현체):
  1. `paymentRepository.findLatestByOrderId(command.orderId())` — 없으면 `BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND)`.
  2. Payment → `PaymentQueryResult` 매핑. `amount`는 `status == PAID`일 때만 `approvedAmount`, 그 외 `null`(gRPC 계층에서 0으로 변환). `approvedAt`/`failedAt`도 해당 상태일 때만 채움(엔티티에 이미 존재하는 값이므로 상태 분기 없이 엔티티 필드 그대로 옮겨도 결과는 같다 — 엔티티가 상태 전이 시점에만 그 필드를 채우기 때문).

---

## 4. infrastructure.grpc (기존 클래스에 메서드 추가)

`infrastructure/grpc/PaymentQueryGrpcService.java`:

- 생성자에 `GetPaymentUseCase` 추가 주입.
- `getPayment(GetPaymentRequest, StreamObserver<GetPaymentResponse>)` 신규 오버라이드:
  - proto request → `GetPaymentCommand` 변환 → usecase 호출 → 결과를 `GetPaymentResponse`로 변환(`approvedAt`/`failedAt`은 null이 아닐 때만 `set`) 후 `onNext`+`onCompleted`.
  - `BusinessException` catch 시 기존 `getRefund`와 동일한 매핑(`ErrorCode.getStatus()`가 4xx면 `Status.NOT_FOUND`, 아니면 `Status.INTERNAL`) 재사용.

---

## 5. 문서 갱신 (`../docs/`, `.claude/docs/`)

- `../docs/architecture/overview.md`: 내부 동기 통신(gRPC) 표의 `order → payment | 9084 | 환불 이벤트 폴백 조회` 행을 "환불/결제 승인·실패 이벤트 폴백 조회"로 갱신(같은 행, 용도 설명만 확장).
- `payment-service/.claude/docs/events.md`: `PAYMENT_APPROVED`/`PAYMENT_FAILED` 섹션에 "Kafka 유실 시 `PaymentQueryService.GetPayment` gRPC(포트 9084)로 폴백 조회 가능. 조회 키는 orderId(동일 orderId 여러 건이면 최신 1건)" 한 줄씩 추가.

---

## 신규/수정 대상

| 파일 | 내용 |
|---|---|
| `../grpc/payment/payment_query.proto` | `GetPayment` rpc·message 추가 |
| `../grpc/README.md` | 레이아웃 표 갱신 |
| `domain/repository/PaymentRepository.java` | `findLatestByOrderId` 추가 |
| `infrastructure/persistence/PaymentJpaRepository.java` / `PaymentRepositoryAdapter.java` | 위 메서드 구현 |
| `application/dto/command/GetPaymentCommand.java` (신규) | orderId |
| `application/dto/result/PaymentQueryResult.java` (신규) | 응답 매핑용 |
| `application/usecase/GetPaymentUseCase.java` (신규) | Input Boundary |
| `application/service/GetPaymentService.java` (신규) | 조회 로직 구현체 |
| `infrastructure/grpc/PaymentQueryGrpcService.java` | `getPayment` 메서드 추가 |
| `../docs/architecture/overview.md` | gRPC 통신 표 용도 설명 갱신 |
| `.claude/docs/events.md` | 폴백 조회 채널 한 줄씩 추가 |

---

## 테스트 케이스 (신규)

### `GetPaymentServiceTest` (단위, Mockito)

| 테스트 메서드명 | 검증 내용 |
|---|---|
| `결제_건_없으면_PAYMENT_NOT_FOUND_예외` | `paymentRepository.findLatestByOrderId` → empty → `PaymentErrorCode.PAYMENT_NOT_FOUND` |
| `PAID_상태_조회_시_필드_매핑_확인` | Payment(PAID) → `PaymentQueryResult`에 amount/approvedAt 채워짐, failedAt null |
| `FAILED_상태_조회_시_필드_매핑_확인` | Payment(FAILED) → amount null, failedAt 채워짐 |

### `PaymentQueryGrpcServiceTest` (기존 파일에 케이스 추가, `grpc-inprocess`)

| 테스트 메서드명 | 검증 내용 |
|---|---|
| `정상_조회_시_GetPaymentResponse_반환` | usecase Mockito 목 → 응답 필드 확인 |
| `PAYMENT_NOT_FOUND_예외_시_NOT_FOUND_status` | `BusinessException(PAYMENT_NOT_FOUND)` → `Status.NOT_FOUND` |

### `PaymentJpaRepositoryTest` (기존 파일에 케이스 추가)

| 테스트 메서드명 | 검증 내용 |
|---|---|
| `findLatestByOrderId_여러건_중_최신_반환` | 동일 orderId로 FAILED 1건(먼저 저장) + PAID 1건(나중 저장) 저장 후, `findLatestByOrderId`가 PAID(최신) 반환 검증 |
| `findLatestByOrderId_없으면_empty` | 다른 orderId로 조회 시 empty |

---

## 검증

```bash
../gradlew :payment-service:build
../gradlew :payment-service:test --tests "com.prompthub.paymentservice.application.service.GetPaymentServiceTest"
../gradlew :payment-service:test --tests "com.prompthub.paymentservice.infrastructure.grpc.PaymentQueryGrpcServiceTest"
../gradlew :payment-service:test --tests "com.prompthub.paymentservice.infrastructure.persistence.PaymentJpaRepositoryTest"
```

- `bootRun` 후 `grpcurl -plaintext localhost:9084 prompthub.payment.PaymentQueryService/GetPayment` 등으로 수동 확인 가능(선택).

---

## 트레이드오프

- **소유자 검증 없음(userId 미검증)**: `GetRefund`와 동일한 내부망 신뢰 전제(선례를 따름) — 별도로 재확인하지 않고 이번에도 그대로 적용.
- **"최신 1건" 반환이 정답이 아닌 경우**: 만약 order-service가 특정 재결제 시도(과거 FAILED 건 포함) 이력을 모두 봐야 하는 시나리오가 나오면 이 설계로는 커버되지 않는다 — 현재는 "현재 상태 확인" 용도로만 스코프를 좁혔고, 이력 조회가 필요해지면 별도 작업(예: `repeated` 응답)으로 분리한다.
