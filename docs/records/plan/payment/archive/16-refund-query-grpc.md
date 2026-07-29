# 환불 조회 gRPC 서버 구현 계획

order-service가 Kafka `payment-events`(`PAYMENT_REFUNDED`/`PAYMENT_REFUND_FAILED`) 이벤트를 못 받았을 때 payment-service에 직접 물어 환불 상태를 복구할 수 있도록, payment-service에 신규 gRPC 서버(`PaymentQueryService.GetRefund`)를 만든다.

---

## 배경 및 목표

- 현재 payment-service는 gRPC를 **클라이언트**로만 쓴다(`infrastructure/external/grpc/OrderGrpcClientAdapter` — order-service 호출용, 서버는 미구현). 자체 gRPC 서버는 전혀 없다.
- order-service의 `PaymentEventConsumer`는 3회 재시도 후 DLT로 빠지는데, 그 이후 환불 상태를 다시 확보할 방법이 order-service 쪽에 없다. 이번 작업은 그 폴백 경로 — order-service가 클라이언트, payment-service가 서버 — 를 gRPC 단건 조회로 신설한다.
- 문서·구현 모두 전무한 완전 신규 설계 대상임을 조사로 확인함(`.claude/docs/events.md`, `../docs/architecture/overview.md`, `event-flow.md` 어디에도 언급 없음).
- **order-service 쪽 gRPC 클라이언트 구현은 이번 작업 범위 밖**이다(다른 서비스 소스 코드 수정 금지 원칙). payment-service는 계약을 소유(서버)하므로 proto 계약 정의 + 서버 구현까지가 범위다.

---

## 확정 사항 (브레인스토밍 결론)

1. **조회 키**: `paymentId` + `orderProductId`. 부분환불은 orderProductId 단위로 처리되고(`uk_refund_payment_order_product` 유니크 인덱스로 이 조합이 최대 1건임을 스키마가 보장), 이 두 값으로 특정 환불 건을 정확히 짚어 조회한다. **userId는 조회 키에서 제외** — 소유자 검증 없음.
2. **응답 payload**: Kafka `PaymentRefundedMessage`/`PaymentRefundFailedMessage` 필드를 그대로 미러링하고 `refundStatus` 판별자 하나로 통합한다. userId는 응답 필드로는 유지(조회된 Payment에서 그대로 채움, 검증엔 안 씀).
3. **환불 실패(FAILED) 사유/시각 데이터 갭**: `Refund` 엔티티엔 애초에 `failedAt`/실패사유 컬럼이 없어 DB에 영속화되지 않는다(`ProcessRefundService`가 PG 실패 시 `refund.fail()`만 호출 — 실패 사유는 로그에만 남고 Kafka 이벤트에만 일시적으로 실림). 이를 메꾸는 스키마 변경은 **이번 작업 범위에 포함하지 않는다** — gRPC 응답 계약에서 실패 사유/시각 필드 자체를 제외한다. FAILED 조회 시 `refund_status=FAILED`와 기본 필드만 반환.
4. **패키지 위치**: `infrastructure.grpc` (payment-service 자체 Kafka 컨슈머 입력 어댑터 관례 — 예외적으로 `application.usecase`에 의존 — 와 일관. user-service가 gRPC 서버를 `presentation.grpc`에 두는 선례가 있지만 따르지 않기로 함).
5. **proto 계약 위치**: 모노레포 `../grpc/payment/` 신설. `grpc-contract-ownership.md` 컨벤션상 서버(payment)가 계약을 소유하므로 루트 공유 디렉터리에 둔다. `payment-service/`·`../docs/` 경계 밖이지만 사용자 승인 완료.
6. **네이밍**: 파일·서비스명은 도메인(payment) 기준(`payment_query.proto`, `PaymentQueryService`) — 서버 모듈명과 도메인이 같아 `java_package`에서 도메인 접미사를 생략한다. rpc·message명은 무엇을 조회하는지(refund) 기준(`GetRefund`/`GetRefundRequest`/`GetRefundResponse`).
7. **gRPC 서버 스타터**: `org.springframework.boot:spring-boot-starter-grpc-server` — Spring Boot 4.1 **네이티브** gRPC 지원(group이 `org.springframework.boot`, `@GrpcService`는 `org.springframework.grpc.server.service.GrpcService`). `net.devh:grpc-spring-boot-starter` 같은 서드파티 아님. user-service가 동일 스타터로 이미 서버를 운영 중임을 확인함.
8. **보안**: 별도 인터셉터 없이 전면 허용. user-service `GrpcSecurityConfig`의 permitAll 기본값과 동일 — 내부망 신뢰 전제, 기존 payment→order 클라이언트 호출도 인증 헤더가 없다.

---

## 1. proto 계약

`../grpc/payment/payment_query.proto` 신설:

```proto
syntax = "proto3";

package prompthub.payment;

option java_multiple_files = true;
option java_package = "com.prompthub.payment.grpc";
option java_outer_classname = "PaymentQueryProto";

service PaymentQueryService {
  rpc GetRefund(GetRefundRequest) returns (GetRefundResponse);
}

message GetRefundRequest {
  string payment_id = 1;
  string order_product_id = 2;
}

message GetRefundResponse {
  string payment_id = 1;
  string order_id = 2;
  string user_id = 3;
  string order_product_id = 4;
  int32 amount = 5;
  string payment_status = 6;   // Payment.status (예: PARTIAL_REFUNDED, ALL_REFUNDED)
  string refund_status = 7;    // RefundStatus (COMPLETED, FAILED, REQUESTED)
  string refunded_at = 8;      // ISO 8601, refund_status=COMPLETED일 때만 값 존재
}
```

`../grpc/README.md`의 "현재 레이아웃" 표에도 행 추가:

```
grpc/payment/payment_query.proto  ← PaymentQueryService.GetRefund (소유: payment)
```

---

## 2. domain / repository 변경

`refund` 테이블은 `(payment_id, order_product_id)` 유니크 인덱스(`uk_refund_payment_order_product`)가 이미 있어 이 조합은 최대 1건이다 — "최신 건" 개념이 필요 없다.

```java
// domain/repository/RefundRepository.java
Optional<Refund> findByPaymentIdAndOrderProductId(UUID paymentId, UUID orderProductId);
```

- `infrastructure/persistence/RefundJpaRepository.java`: `Optional<Refund> findByPaymentIdAndOrderProductId(UUID paymentId, UUID orderProductId);` (Spring Data 메서드 이름 파생)
- `infrastructure/persistence/RefundRepositoryAdapter.java`: 위임 구현 추가.
- `domain/repository/PaymentRepository.java`: 기존 `findById(UUID)` 재사용, 신규 메서드 불필요.

---

## 3. application 계층

- `application/dto/command/GetRefundCommand.java` (신규 record): `GetRefundCommand(UUID paymentId, UUID orderProductId)`.
- `application/dto/result/RefundQueryResult.java` (신규 record): `RefundQueryResult(UUID paymentId, UUID orderId, UUID userId, UUID orderProductId, int amount, String paymentStatus, String refundStatus, OffsetDateTime refundedAt)`. 기존 `application/gateway/external/RefundResult.java`(PG 게이트웨이 결과, refundedAt 하나뿐)와 이름이 겹치므로 구분해서 새로 만든다.
- `application/usecase/GetRefundUseCase.java` (신규 인터페이스): `RefundQueryResult getRefund(GetRefundCommand command)`.
- `application/service/GetRefundService.java` (신규 구현체):
  1. `paymentRepository.findById(command.paymentId())` — 없으면 `BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND)`.
  2. `refundRepository.findByPaymentIdAndOrderProductId(command.paymentId(), command.orderProductId())` — 없으면 `BusinessException(PaymentErrorCode.REFUND_NOT_FOUND)`(신규 코드).
  3. Payment/Refund를 `RefundQueryResult`로 매핑(userId는 Payment에서, amount/refundedAt은 Refund에서).
- `application/exception/PaymentErrorCode.java`에 추가:
  ```java
  REFUND_NOT_FOUND(HttpStatus.NOT_FOUND, "PAY011", "환불 내역을 찾을 수 없습니다."),
  ```

---

## 4. infrastructure.grpc (신규 패키지 — gRPC 서버 입력 어댑터)

`infrastructure/grpc/PaymentQueryGrpcService.java`:

- `@GrpcService` + `PaymentQueryServiceGrpc.PaymentQueryServiceImplBase` 상속.
- 생성자로 `GetRefundUseCase` 주입(Kafka 컨슈머와 동일하게 usecase 직접 의존이 아키텍처 예외로 허용됨).
- `getRefund(GetRefundRequest, StreamObserver<GetRefundResponse>)`: proto request → `GetRefundCommand` 변환 → usecase 호출 → 결과를 `GetRefundResponse`로 변환 후 `onNext`+`onCompleted`.
- `BusinessException` catch해 `ErrorCode.getStatus()`(HttpStatus) 기준으로 `io.grpc.Status` 매핑(NOT_FOUND→`Status.NOT_FOUND`, 그 외→`Status.INTERNAL`) 후 `onError`. `OrderGrpcClientAdapter`의 반대 방향(gRPC 오류 → BusinessException) 패턴을 그대로 뒤집은 것.

---

## 5. 설정 / build.gradle

- `payment-service/build.gradle`:
  - `implementation 'org.springframework.boot:spring-boot-starter-grpc-server'` 추가.
  - `sourceSets { main { proto { srcDir "${rootProject.projectDir}/../grpc/payment" } } }` 추가(경로는 모노레포 루트 기준으로 조정).
- `application-local.yml`: `spring.grpc.server.port: ${PAYMENT_GRPC_PORT:9084}` 추가. 포트 컨벤션(user 8081/9081, product 8082/9082, order 8083/9083)을 이어 payment는 8084/9084.

---

## 6. 문서 갱신 (`../docs/`, `.claude/docs/`)

- `../docs/architecture/overview.md`:
  - 모듈 포트 표: `payment-service` 행의 gRPC 포트를 `- (order 9083 클라이언트)` → `9084 (서버, order-service향 환불 조회) / order 9083 클라이언트`로 갱신.
  - 내부 동기 통신(gRPC) 표에 `order → payment | 9084 | 환불 이벤트 폴백 조회(Kafka 유실 시) | infrastructure/grpc/PaymentQueryGrpcService.java` 행 추가.
- `payment-service/.claude/docs/events.md`: 환불 이벤트 섹션에 "Kafka 이벤트 유실 시 order-service가 `PaymentQueryService.GetRefund` gRPC로 폴백 조회 가능" 한 줄 추가.

---

## 신규/수정 대상

| 파일 | 내용 |
|---|---|
| `../grpc/payment/payment_query.proto` (신규) | 계약 원본 |
| `../grpc/README.md` | 레이아웃 표에 payment 행 추가 |
| `payment-service/build.gradle` | grpc-server starter, srcDir 추가 |
| `domain/repository/RefundRepository.java` | `findByPaymentIdAndOrderProductId` 추가 |
| `infrastructure/persistence/RefundJpaRepository.java` / `RefundRepositoryAdapter.java` | 위 메서드 구현 |
| `application/exception/PaymentErrorCode.java` | `REFUND_NOT_FOUND`(PAY011) 추가 |
| `application/dto/command/GetRefundCommand.java` (신규) | paymentId, orderProductId |
| `application/dto/result/RefundQueryResult.java` (신규) | 응답 매핑용 |
| `application/usecase/GetRefundUseCase.java` (신규) | Input Boundary |
| `application/service/GetRefundService.java` (신규) | 조회 로직 구현체 |
| `infrastructure/grpc/PaymentQueryGrpcService.java` (신규) | gRPC 서버 구현체 |
| `application-local.yml` | `spring.grpc.server.port` 추가 |
| `../docs/architecture/overview.md` | 포트 표, gRPC 통신 표 갱신 |
| `.claude/docs/events.md` | 폴백 조회 채널 한 줄 추가 |

---

## 테스트 케이스 (신규)

### `GetRefundServiceTest` (단위, Mockito)

| 테스트 메서드명 | 검증 내용 |
|---|---|
| `결제_건_없으면_PAYMENT_NOT_FOUND_예외` | `paymentRepository.findById` → empty → `PaymentErrorCode.PAYMENT_NOT_FOUND` |
| `환불_건_없으면_REFUND_NOT_FOUND_예외` | Payment는 있으나 Refund 없음 → `PaymentErrorCode.REFUND_NOT_FOUND` |
| `정상_조회_시_필드_매핑_확인` | Payment/Refund → `RefundQueryResult` 필드 매핑 검증 |

### `PaymentQueryGrpcServiceTest` (`grpc-inprocess`, `OrderGrpcClientAdapterTest`와 동일 패턴)

| 테스트 메서드명 | 검증 내용 |
|---|---|
| `정상_조회_시_GetRefundResponse_반환` | usecase Mockito 목 → 응답 필드 확인 |
| `PAYMENT_NOT_FOUND_예외_시_NOT_FOUND_status` | `BusinessException(PAYMENT_NOT_FOUND)` → `Status.NOT_FOUND` |
| `REFUND_NOT_FOUND_예외_시_NOT_FOUND_status` | `BusinessException(REFUND_NOT_FOUND)` → `Status.NOT_FOUND` |

### `RefundJpaRepositoryTest` (기존 파일에 케이스 추가)

| 테스트 메서드명 | 검증 내용 |
|---|---|
| `findByPaymentIdAndOrderProductId_정상_조회` | 저장한 Refund가 paymentId+orderProductId로 조회됨 |
| `findByPaymentIdAndOrderProductId_없으면_empty` | 다른 조합으로 조회 시 empty |

---

## 검증

```bash
../gradlew :payment-service:build
../gradlew :payment-service:test --tests "com.prompthub.paymentservice.application.service.GetRefundServiceTest"
../gradlew :payment-service:test --tests "com.prompthub.paymentservice.infrastructure.grpc.PaymentQueryGrpcServiceTest"
```

- `bootRun` 후 `grpcurl -plaintext localhost:9084 prompthub.payment.PaymentQueryService/GetRefund` 등으로 수동 확인 가능(선택).

---

## 트레이드오프

- **소유자 검증 없음(userId 미검증)**: 내부망 신뢰 전제(사용자 결정). order-service가 이미 자신의 order/orderProduct 데이터로 소유권을 검증했다고 간주 — payment-service는 그 결과를 다시 검증하지 않는다. 외부에 노출되는 API가 아니므로 리스크는 낮다고 판단.
- **FAILED 사유/시각 데이터 갭 미해결**: `Refund`에 `failedAt`/실패사유 컬럼을 추가하는 스키마 변경은 하지 않기로 함(사용자 결정) — 향후 필요해지면 별도 작업으로 분리.
