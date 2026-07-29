# 환불 API 구현 계획

## 목표

`POST /api/v1/payments/{paymentId}/refund` 엔드포인트 구현.
요청 접수(202) → AFTER_COMMIT PG 환불 호출 → 성공 시 REFUNDED + Kafka, 실패 시 PAID 복원.
Scheduled Retry(10분 주기)로 REFUNDING 장애 건 자동 복구.

---

## 현재 코드베이스 상태

### 이미 존재하는 파일 (수정 대상)

| 파일 | 현재 상태 | 필요 변경 |
|---|---|---|
| `domain/model/Payment.java` | confirm 흐름 전이 메서드만 있음 | 환불 전이 메서드 3개 추가 |
| `domain/model/Refund.java` | `create()` 팩토리만 있음 | `complete()`, `fail()` 추가 |
| `application/gateway/external/PaymentGateway.java` | `confirm()` 하나뿐 | `refund()` 메서드 추가 |
| `application/gateway/persistence/PaymentRepository.java` | 기본 쿼리만 | `findByStatusAndUpdatedAtBefore()` 추가 |
| `application/gateway/persistence/RefundRepository.java` | `save`, `findById` | `findByPaymentId()` 추가 |
| `application/exception/PaymentErrorCode.java` | PAY001~PAY_FAILED | `PAY004`, `PAY005`, `PAY006` 추가 |
| `infrastructure/external/toss/TossPaymentGateway.java` | `confirm()` 구현만 있음 | `refund()` 구현 추가 |
| `infrastructure/messaging/KafkaPaymentEventPublisher.java` | `payment.approved` 발행만 | `onPaymentRefunded(PaymentRefundedEvent)` 이벤트 리스너 추가 |
| `infrastructure/messaging/config/KafkaConfig.java` | `payment.approved` 토픽만 | `payment.refunded` NewTopic 빈 추가 |
| `infrastructure/messaging/config/PaymentTopic.java` | `PAYMENT_APPROVED` 상수만 | `PAYMENT_REFUNDED` 추가 |
| `infrastructure/persistence/PaymentJpaRepository.java` | 기본 쿼리만 | `findByStatusAndUpdatedAtBefore()` 추가 |
| `infrastructure/persistence/PaymentRepositoryAdapter.java` | 기본 쿼리 위임 | 위 쿼리 위임 추가 |
| `infrastructure/persistence/RefundJpaRepository.java` | Spring Data 기본 메서드 | `findByPaymentId()` 추가 |
| `infrastructure/persistence/RefundRepositoryAdapter.java` | `save`, `findById` | `findByPaymentId()` 추가 |
| `presentation/PaymentController.java` | confirm 엔드포인트만 | 환불 엔드포인트 추가 |

### 신규 생성 대상

| 파일 | 역할 |
|---|---|
| `domain/event/PaymentRefundRequestedEvent.java` | 환불 접수 → AFTER_COMMIT PG 호출 트리거 |
| `domain/event/PaymentRefundedEvent.java` | Kafka 발행 트리거용 도메인 이벤트 (RefundEventHandler → PaymentRefundedEvent → KafkaPaymentEventPublisher 연쇄) |
| `application/dto/command/RefundPaymentCommand.java` | 환불 유즈케이스 입력 |
| `application/usecase/RefundPaymentUseCase.java` | 환불 Input Boundary |
| `application/usecase/RefundPaymentInteractor.java` | 환불 유즈케이스 구현체 |
| `application/gateway/external/TossRefundResult.java` | PG 환불 결과 VO |
| `infrastructure/external/toss/dto/TossRefundRequest.java` | Toss 취소 API 요청 DTO |
| `infrastructure/external/toss/dto/TossRefundResponse.java` | Toss 취소 API 응답 DTO |
| `infrastructure/messaging/RefundEventHandler.java` | AFTER_COMMIT PG 환불 처리 |
| `infrastructure/messaging/dto/PaymentRefundedMessage.java` | Kafka 발행 메시지 |
| `infrastructure/scheduling/SchedulingConfig.java` | `@EnableScheduling` 설정 클래스 |
| `infrastructure/scheduling/PaymentRefundRetryScheduler.java` | 10분 주기 REFUNDING 장애 복구 |
| `src/test/.../application/usecase/RefundPaymentInteractorTest.java` | 단위 테스트 |
| `src/test/.../RefundPaymentIntegrationTest.java` | 통합 테스트 |

---

## 트랜잭션 · 이벤트 흐름

```
HTTP POST /api/v1/payments/{paymentId}/refund
  → PaymentController
  → RefundPaymentInteractor  ← @Transactional
      1. paymentRepository.findById(paymentId)  → 없으면 PAY005 예외
      2. payment.userId != command.userId()      → PAY006 예외  (본인 확인)
      3. payment.status != PAID                 → PAY004 예외
      4. payment.startRefunding()               → PAID → REFUNDING
      5. Refund refund = Refund.create(paymentId, userId, totalAmount, null, null)
      6. paymentRepository.save(payment)
      7. refundRepository.save(refund)
      8. applicationEventPublisher.publishEvent(PaymentRefundRequestedEvent(paymentId, refundId))
      → COMMIT
  → HTTP 202

  ─ AFTER_COMMIT ──────────────────────────────────────────────────────
  RefundEventHandler.onRefundRequested(PaymentRefundRequestedEvent)
    @Transactional(REQUIRES_NEW):
      payment = paymentRepository.findById(event.paymentId)
      refund  = refundRepository.findByPaymentId(event.paymentId)
      try:
        result  = paymentGateway.refund(payment.pgTxId, payment.id, payment.totalAmount)
        payment.completeRefund(result.refundedAt())
        refund.complete(result.refundedAt())
        paymentRepository.save(payment)
        refundRepository.save(refund)
        applicationEventPublisher.publishEvent(PaymentRefundedEvent(payment, refund))
      catch (PaymentGatewayException):
        payment.restoreToRefundFailed()  → REFUNDING → PAID
        refund.fail()
        paymentRepository.save(payment)
        refundRepository.save(refund)

  ─ AFTER_COMMIT (PaymentRefundedEvent) ──────────────────────────────
  KafkaPaymentEventPublisher.onPaymentRefunded(PaymentRefundedEvent)
    → Kafka 발행: payment.refunded

  ─ Scheduled Retry (10분 주기) ───────────────────────────────────────
  PaymentRefundRetryScheduler.retryStaleRefunding()
    REFUNDING 상태 + updatedAt < 30분 전인 건 조회
    각 건에 대해 paymentGateway.refund(...) 재호출
    성공: completeRefund + Kafka 발행 / 실패: restoreToRefundFailed
```

**핵심 설계 결정**

- `PaymentRefundRequestedEvent` 존재 이유: `RefundPaymentInteractor`(1트랜잭션)와 PG 호출(AFTER_COMMIT)을 분리하는 경계선. 202를 먼저 반환하고 트랜잭션 커밋 이후에 PG 환불을 비동기로 처리해야 하므로 이 내부 이벤트를 통해 `@TransactionalEventListener(AFTER_COMMIT)`을 트리거한다. 이 이벤트가 없으면 PG 호출과 202 반환을 동기로 묶어야 해서 설계 목적(비동기 처리)을 달성할 수 없다.

- `RefundEventHandler` vs `KafkaPaymentEventPublisher` 역할 분리: PG 호출 + DB 업데이트는 `RefundEventHandler`, Kafka 발행은 `KafkaPaymentEventPublisher`가 전담. 관심사 분리.

- 예외 삼킴: 202 이미 반환됐으므로 PG 실패는 상태 복원 후 로그 기록. 클라이언트는 상태 폴링으로 확인.

- **권한 검증 책임 분리**: API Gateway가 JWT 검증 및 역할(BUYER/SELLER/ADMIN) 필터링을 담당하므로 payment-service에서 `X-User-Role` 헤더를 추가로 검사하지 않는다. payment-service가 책임지는 유일한 권한 검증은 **본인 결제 확인**(userId 일치)이다.

---

## 레이어별 구현 계획

### 1단계 — domain 레이어

#### `Payment.java` 수정

기존 `fail()` 아래에 메서드 3개 추가.

```java
// PAID → REFUNDING
public void startRefunding() {
    if (this.status != PaymentStatus.PAID) {
        throw new IllegalStateException("PAID 상태에서만 REFUNDING으로 전환할 수 있습니다.");
    }
    this.status = PaymentStatus.REFUNDING;
}

// REFUNDING → REFUNDED
public void completeRefund(OffsetDateTime refundedAt) {
    if (this.status != PaymentStatus.REFUNDING) {
        throw new IllegalStateException("REFUNDING 상태에서만 REFUNDED로 전환할 수 있습니다.");
    }
    this.status = PaymentStatus.REFUNDED;
    this.refundedAt = refundedAt;
}

// REFUNDING → PAID (PG 환불 실패 복원)
public void restoreToRefundFailed() {
    if (this.status != PaymentStatus.REFUNDING) {
        throw new IllegalStateException("REFUNDING 상태에서만 PAID로 복원할 수 있습니다.");
    }
    this.status = PaymentStatus.PAID;
}
```

#### `Refund.java` 수정

```java
// REQUESTED → COMPLETED
public void complete(OffsetDateTime completedAt) {
    if (this.status != RefundStatus.REQUESTED) {
        throw new IllegalStateException("REQUESTED 상태에서만 COMPLETED로 전환할 수 있습니다.");
    }
    this.status = RefundStatus.COMPLETED;
    this.completedAt = completedAt;
}

// REQUESTED → FAILED
public void fail() {
    if (this.status != RefundStatus.REQUESTED) {
        throw new IllegalStateException("REQUESTED 상태에서만 FAILED로 전환할 수 있습니다.");
    }
    this.status = RefundStatus.FAILED;
}
```

#### `PaymentRefundRequestedEvent.java` 신규 생성

```java
// domain/event/
public record PaymentRefundRequestedEvent(UUID paymentId, UUID refundId) {}
```

엔티티를 직접 담지 않고 ID만 넘김 — AFTER_COMMIT 시점에 영속성 컨텍스트가 닫혀 있으므로 DB에서 재조회하는 것이 안전.

#### `PaymentRefundedEvent.java` 신규 생성

```java
// domain/event/
public record PaymentRefundedEvent(Payment payment, Refund refund) {}
```

Kafka 발행에서 Payment/Refund 필드 접근이 필요하므로 엔티티 전달 (REQUIRES_NEW 트랜잭션 내에서 발행되므로 안전).

---

### 2단계 — application 레이어

#### `PaymentErrorCode.java` 수정

```java
PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "PAY005", "결제 건을 찾을 수 없습니다."),
REFUND_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "PAY004", "환불 가능한 상태가 아닙니다."),
UNAUTHORIZED_REFUND(HttpStatus.FORBIDDEN, "PAY006", "본인 결제 건만 환불할 수 있습니다.");
```

> `A004`는 common-module에 미정의 상태이며, ADMIN 역할 차단은 API Gateway가 담당한다. payment-service에서는 본인 확인 실패(userId 불일치)에 `PAY006`을 사용한다.

#### `RefundPaymentCommand.java` 신규

```java
// application/dto/command/
public record RefundPaymentCommand(UUID paymentId, UUID userId) {}
```

Gateway가 역할 검증을 담당하므로 `role` 필드 불필요. `userId`만으로 본인 확인.

#### `RefundPaymentUseCase.java` 신규

```java
// application/usecase/
public interface RefundPaymentUseCase {
    void refund(RefundPaymentCommand command);
}
```

반환값 없음(202 응답은 data: null).

#### `TossRefundResult.java` 신규

```java
// application/gateway/external/
public record TossRefundResult(OffsetDateTime refundedAt) {}
```

#### `PaymentGateway.java` 수정

```java
TossRefundResult refund(String pgTxId, UUID paymentId, int amount);
```

#### `PaymentRepository.java` 수정

```java
List<Payment> findByStatusAndUpdatedAtBefore(PaymentStatus status, OffsetDateTime threshold);
```

Scheduled Retry에서 REFUNDING 장애 건 조회에 사용.

#### `RefundRepository.java` 수정

```java
Optional<Refund> findByPaymentId(UUID paymentId);
```

#### `RefundPaymentInteractor.java` 신규

```java
@Service
@Transactional
public class RefundPaymentInteractor implements RefundPaymentUseCase {

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Override
    public void refund(RefundPaymentCommand command) {
        Payment payment = paymentRepository.findById(command.paymentId())
            .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND));

        if (!payment.getUserId().equals(command.userId())) {
            throw new BusinessException(PaymentErrorCode.UNAUTHORIZED_REFUND);
        }
        if (payment.getStatus() != PaymentStatus.PAID) {
            throw new BusinessException(PaymentErrorCode.REFUND_NOT_ALLOWED);
        }

        payment.startRefunding();
        Refund refund = Refund.create(
            payment.getId(), command.userId(), payment.getTotalAmount(), null, null
        );
        paymentRepository.save(payment);
        refundRepository.save(refund);

        applicationEventPublisher.publishEvent(
            new PaymentRefundRequestedEvent(payment.getId(), refund.getId())
        );
    }
}
```

환불 사유(`reason`)는 현재 입력받지 않으므로 `null` 고정.

---

### 3단계 — infrastructure 레이어

#### `TossRefundRequest.java` 신규

```java
// infrastructure/external/toss/dto/
public record TossRefundRequest(String cancelReason, Integer cancelAmount) {}
```

전체 환불 시 `cancelAmount`는 `null`로 전달(Toss API 스펙: 생략 시 전체 취소).

#### `TossRefundResponse.java` 신규

Toss API 취소 응답에서 `cancels` 배열의 `canceledAt` 필드만 추출.

```java
// infrastructure/external/toss/dto/
public record TossRefundResponse(
    String status,
    List<TossCancel> cancels
) {
    public record TossCancel(OffsetDateTime canceledAt) {}
}
```

#### `TossPaymentGateway.java` 수정

```java
@Override
public TossRefundResult refund(String pgTxId, UUID paymentId, int amount) {
    TossRefundRequest request = new TossRefundRequest("구매자 환불 요청", null);
    TossRefundResponse response = restClient.post()
        .uri("/payments/{paymentKey}/cancels", pgTxId)
        .header("Idempotency-Key", "refund-" + paymentId)
        .contentType(MediaType.APPLICATION_JSON)
        .body(request)
        .retrieve()
        .onStatus(HttpStatusCode::is4xxClientError, (req, resp) -> {
            TossErrorResponse error = parseError(resp);
            throw new PaymentGatewayException(PaymentErrorCode.PG_ERROR, error.code(), error.message(), null, null);
        })
        .onStatus(HttpStatusCode::is5xxServerError, (req, resp) -> {
            TossErrorResponse error = parseError(resp);
            throw new PaymentGatewayException(PaymentErrorCode.PG_ERROR, error.code(), error.message(), null, null);
        })
        .body(TossRefundResponse.class);

    OffsetDateTime refundedAt = response.cancels().get(response.cancels().size() - 1).canceledAt();
    return new TossRefundResult(refundedAt);
}
```

#### `PaymentTopic.java` 수정

```java
public static final String PAYMENT_REFUNDED = "payment.refunded";
```

#### `KafkaConfig.java` 수정

기존 `paymentApprovedTopic()` 빈과 동일한 패턴으로 추가.

```java
@Bean
public NewTopic paymentRefundedTopic() {
    return TopicBuilder.name(PaymentTopic.PAYMENT_REFUNDED)
        .partitions(1)
        .replicas(1)
        .build();
}
```

#### `PaymentRefundedMessage.java` 신규

```java
// infrastructure/messaging/dto/
public record PaymentRefundedMessage(
    String eventType,
    UUID paymentId,
    UUID orderId,
    UUID userId,
    int amount,
    String refundedAt
) {}
```

events.md 스키마 준수: `eventType = "payment.refunded"`.

#### `RefundEventHandler.java` 신규

```java
// infrastructure/messaging/
@Slf4j
@Component
@RequiredArgsConstructor
public class RefundEventHandler {

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final PaymentGateway paymentGateway;
    private final ApplicationEventPublisher applicationEventPublisher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onRefundRequested(PaymentRefundRequestedEvent event) {
        Payment payment = paymentRepository.findById(event.paymentId()).orElseThrow();
        Refund refund = refundRepository.findByPaymentId(event.paymentId()).orElseThrow();
        try {
            TossRefundResult result = paymentGateway.refund(
                payment.getPgTxId(), payment.getId(), payment.getTotalAmount()
            );
            payment.completeRefund(result.refundedAt());
            refund.complete(result.refundedAt());
            paymentRepository.save(payment);
            refundRepository.save(refund);
            applicationEventPublisher.publishEvent(new PaymentRefundedEvent(payment, refund));
        } catch (PaymentGatewayException e) {
            log.error("PG 환불 실패 — paymentId={}, code={}, reason={}",
                payment.getId(), e.getFailureCode(), e.getFailureReason());
            payment.restoreToRefundFailed();
            refund.fail();
            paymentRepository.save(payment);
            refundRepository.save(refund);
        }
    }
}
```

#### `KafkaPaymentEventPublisher.java` 수정

기존 `onPaymentApproved` 메서드 유지, 아래 메서드 추가.

```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void onPaymentRefunded(PaymentRefundedEvent event) {
    Payment payment = event.payment();
    Refund refund = event.refund();
    PaymentRefundedMessage message = new PaymentRefundedMessage(
        "payment.refunded",
        payment.getId(), payment.getOrderId(), payment.getUserId(),
        refund.getRefundAmount(),
        payment.getRefundedAt() != null ? payment.getRefundedAt().toString() : null
    );
    kafkaTemplate.send(PaymentTopic.PAYMENT_REFUNDED, payment.getOrderId().toString(), message)
        .whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("환불 Kafka 메시지 발행 실패 — paymentId={}, cause={}",
                    payment.getId(), ex.getMessage());
            } else {
                log.info("환불 Kafka 메시지 발행 성공 — paymentId={}, partition={}, offset={}",
                    payment.getId(),
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset());
            }
        });
}
```

#### `SchedulingConfig.java` 신규

```java
// infrastructure/scheduling/
@Configuration
@EnableScheduling
public class SchedulingConfig {}
```

`@EnableScheduling`은 별도 설정 클래스에 분리한다. `PaymentServiceApplication`은 스프링부트 진입점이므로 스케줄링 설정 관심사를 섞지 않는다. `infrastructure/scheduling` 패키지가 스케줄러 관련 코드를 모두 담으므로 같은 패키지에 설정 클래스를 두는 것이 응집도가 높다.

#### `PaymentRefundRetryScheduler.java` 신규

```java
// infrastructure/scheduling/
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentRefundRetryScheduler {

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final PaymentGateway paymentGateway;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Scheduled(fixedDelay = 600_000)  // 10분 주기
    @Transactional
    public void retryStaleRefunding() {
        OffsetDateTime threshold = OffsetDateTime.now().minusMinutes(30);
        List<Payment> stalePayments = paymentRepository
            .findByStatusAndUpdatedAtBefore(PaymentStatus.REFUNDING, threshold);

        for (Payment payment : stalePayments) {
            Refund refund = refundRepository.findByPaymentId(payment.getId()).orElse(null);
            if (refund == null) continue;

            try {
                TossRefundResult result = paymentGateway.refund(
                    payment.getPgTxId(), payment.getId(), payment.getTotalAmount()
                );
                payment.completeRefund(result.refundedAt());
                refund.complete(result.refundedAt());
                paymentRepository.save(payment);
                refundRepository.save(refund);
                applicationEventPublisher.publishEvent(new PaymentRefundedEvent(payment, refund));
            } catch (PaymentGatewayException e) {
                log.error("스케줄러 환불 재시도 실패 — paymentId={}, code={}, reason={}",
                    payment.getId(), e.getFailureCode(), e.getFailureReason());
                payment.restoreToRefundFailed();
                refund.fail();
                paymentRepository.save(payment);
                refundRepository.save(refund);
            }
        }
    }
}
```

`Idempotency-Key: refund-{paymentId}` 동일 키를 사용하므로 Toss 측에서 이중 환불 방지(멱등키 유효 기간 15일).

#### `PaymentJpaRepository.java` 수정

```java
List<Payment> findByStatusAndUpdatedAtBefore(PaymentStatus status, OffsetDateTime threshold);
```

#### `PaymentRepositoryAdapter.java` 수정

```java
@Override
public List<Payment> findByStatusAndUpdatedAtBefore(PaymentStatus status, OffsetDateTime threshold) {
    return jpaRepository.findByStatusAndUpdatedAtBefore(status, threshold);
}
```

#### `RefundJpaRepository.java` 수정

```java
Optional<Refund> findByPaymentId(UUID paymentId);
```

#### `RefundRepositoryAdapter.java` 수정

```java
@Override
public Optional<Refund> findByPaymentId(UUID paymentId) {
    return jpaRepository.findByPaymentId(paymentId);
}
```

---

### 4단계 — presentation 레이어

#### `PaymentController.java` 수정

기존 `@RequiredArgsConstructor`에 `RefundPaymentUseCase` 주입 추가.

```java
@PostMapping("/{paymentId}/refund")
public ResponseEntity<ApiResult<Void>> refund(
    @RequestHeader("X-User-Id") UUID userId,
    @PathVariable UUID paymentId
) {
    refundPaymentUseCase.refund(new RefundPaymentCommand(paymentId, userId));
    return ResponseEntity
        .status(HttpStatus.ACCEPTED)
        .body(ApiResult.success(null));
}
```

- 요청 바디 없음 — path parameter와 헤더만 사용하므로 별도 request DTO 불필요.
- `X-User-Role` 헤더는 받지 않음 — ADMIN 역할 차단은 API Gateway 책임.

---

### 5단계 — 테스트

#### `RefundPaymentInteractorTest.java` — 단위 테스트 (Mockito)

기존 `ConfirmPaymentInteractorTest` 패턴 참조.

| 테스트 메서드명 | 검증 내용 |
|---|---|
| `결제_건_없으면_PAY005_예외` | findById → empty → BusinessException(PAY005) |
| `본인_아닌_결제_환불_시_PAY006_예외` | userId 불일치 → BusinessException(PAY006) |
| `PAID_아닌_상태_환불_시_PAY004_예외` | status = REFUNDING → BusinessException(PAY004) |
| `PAID_상태_환불_요청_시_REFUNDING_전환_이벤트_발행` | payment.status == REFUNDING, refund 저장, PaymentRefundRequestedEvent 발행 검증 |

#### `RefundPaymentIntegrationTest.java` — 통합 테스트 (Testcontainers)

기존 `ConfirmPaymentIntegrationTest` 패턴 참조 (PostgreSQL + Kafka 컨테이너).

| 테스트 메서드명 | 검증 내용 |
|---|---|
| `환불_정상_플로우_DB_REFUNDED_Kafka_메시지_수신` | PG Mock 성공 → payment.status=REFUNDED, payment.refundedAt 비null, Kafka `payment.refunded` 수신 |
| `PG_환불_실패_시_PAID_복원` | PG Mock 예외 → payment.status=PAID 복원 |
| `PAID_아닌_상태_400_반환` | status=REFUNDING인 Payment로 요청 → 400 + PAY004 |
| `타인_결제_환불_시_403_반환` | userId 불일치 → 403 + PAY006 |

---

### 6단계 — Scheduled Retry (장애 복구)

별도 구현이 아니라 이번 구현 범위에 포함.

`SchedulingConfig`, `PaymentRefundRetryScheduler` 신규 생성은 3단계에 포함.

- REFUNDING 임계값: **30분** 고정.
- `Idempotency-Key: refund-{paymentId}` 동일 키 재사용으로 이중 환불 방지.

---

## 파일 변경 요약

```
수정 (15개)
├── domain/model/Payment.java
├── domain/model/Refund.java
├── application/gateway/external/PaymentGateway.java
├── application/gateway/persistence/PaymentRepository.java
├── application/gateway/persistence/RefundRepository.java
├── application/exception/PaymentErrorCode.java
├── infrastructure/external/toss/TossPaymentGateway.java
├── infrastructure/messaging/KafkaPaymentEventPublisher.java
├── infrastructure/messaging/config/KafkaConfig.java
├── infrastructure/messaging/config/PaymentTopic.java
├── infrastructure/persistence/PaymentJpaRepository.java
├── infrastructure/persistence/PaymentRepositoryAdapter.java
├── infrastructure/persistence/RefundJpaRepository.java
├── infrastructure/persistence/RefundRepositoryAdapter.java
└── presentation/PaymentController.java

신규 (14개)
├── domain/event/PaymentRefundRequestedEvent.java
├── domain/event/PaymentRefundedEvent.java
├── application/dto/command/RefundPaymentCommand.java
├── application/usecase/RefundPaymentUseCase.java
├── application/usecase/RefundPaymentInteractor.java
├── application/gateway/external/TossRefundResult.java
├── infrastructure/external/toss/dto/TossRefundRequest.java
├── infrastructure/external/toss/dto/TossRefundResponse.java
├── infrastructure/messaging/RefundEventHandler.java
├── infrastructure/messaging/dto/PaymentRefundedMessage.java
├── infrastructure/scheduling/SchedulingConfig.java
├── infrastructure/scheduling/PaymentRefundRetryScheduler.java
└── test/.../application/usecase/RefundPaymentInteractorTest.java
    test/...RefundPaymentIntegrationTest.java
```

---

## 트레이드오프

이 계획에서 대안이 존재했던 설계 결정을 정리한다.

---

### 1. 비동기 응답(202) vs 동기 응답(200)

**현재 선택**: Payment를 REFUNDING으로 전환한 뒤 즉시 202를 반환하고, PG 환불은 AFTER_COMMIT에서 비동기로 처리한다.

**대안**: PG 환불이 완료된 후 REFUNDED 상태를 확인하고 200을 반환한다(confirm API와 동일한 패턴).

| | 202 비동기 (현재) | 200 동기 (대안) |
|---|---|---|
| 응답 속도 | PG 응답 대기 없이 즉시 반환 | PG 응답 시간(수백ms~수초) 포함 |
| 클라이언트 처리 | REFUNDING / REFUNDED / PAID(실패복원) 상태 폴링 필요 | 응답 시점에 결과 확정, 폴링 불필요 |
| DB 커넥션 | PAID → REFUNDING 저장만 유지 | PG 호출 동안 커넥션 점유 |
| 실패 가시성 | PG 실패가 HTTP 응답에 드러나지 않음 | PG 실패 즉시 에러 응답 가능 |

> **현재 선택 이유**: 환불은 결제 승인보다 PG 응답이 느린 경우가 많고, api-design.md에서 명시적으로 202 비동기 처리를 지정하고 있다. 클라이언트가 상태 폴링을 통해 결과를 확인하는 방식은 이미 스펙에 반영되어 있다.

---

### 2. PG 환불 실패 시 PAID 복원 vs REFUND_FAILED 별도 상태

**현재 선택**: PG 환불 실패 시 Payment 상태를 `REFUNDING → PAID`로 복원한다. `PaymentStatus`에 별도 실패 상태를 추가하지 않는다.

**대안**: `REFUND_FAILED` 상태를 새로 추가하여 환불 시도가 있었음을 명시적으로 표현한다.

| | PAID 복원 (현재) | REFUND_FAILED 상태 추가 (대안) |
|---|---|---|
| 상태 단순성 | PaymentStatus 변경 없음 | enum 값 추가, 전이 메서드 추가 |
| 재환불 가능 | PAID 상태이므로 클라이언트가 즉시 재시도 가능 | REFUND_FAILED에서 재시도 가능 여부 별도 정의 필요 |
| 이력 추적 | 환불 시도 실패 이력이 Payment 상태에 남지 않음 | 실패 이력이 상태로 명시됨 |
| Refund 엔티티 | `refund.status = FAILED`로 실패 이력 확인 가능 | 중복 기록 |

> **현재 선택 이유**: 환불 실패 이력은 `Refund` 엔티티의 `status = FAILED`로 추적할 수 있다. PAID 복원은 클라이언트가 동일 paymentId로 즉시 재환불 요청이 가능하고, `PaymentStatus`를 단순하게 유지한다.

---

### 3. Scheduled Retry vs Outbox 패턴

**현재 선택**: `@Scheduled(fixedDelay = 600_000)`으로 10분마다 `REFUNDING` 30분 초과 건을 조회해 PG 환불을 재시도한다.

**대안**: Transactional Outbox 패턴 — 환불 요청을 별도 outbox 테이블에 기록하고 별도 폴러가 PG를 호출한다. 또는 Kafka DLQ(Dead Letter Queue)로 실패 메시지를 재처리한다.

| | @Scheduled 재시도 (현재) | Outbox / DLQ (대안) |
|---|---|---|
| 구현 복잡도 | 단순 — 스케줄러 + JPA 쿼리 | outbox 테이블 또는 DLQ 토픽 추가 필요 |
| 재시도 정확성 | 폴링 주기(10분) + 임계값(30분) → 최대 40분 지연 | outbox 폴러 주기에 따라 수초~수분 |
| 이중 환불 방지 | Toss `Idempotency-Key: refund-{paymentId}` 멱등키로 방지 | 동일 멱등키 전략 필요 |
| 운영 복잡도 | 낮음 | Kafka DLQ 모니터링, outbox 테이블 관리 필요 |

> **현재 선택 이유**: 세미 MVP 단계에서 Outbox/DLQ 인프라를 추가하는 것은 과도하다. Toss 멱등키(15일 유효)가 이중 환불을 방지하므로 @Scheduled 재시도로 충분하다. 환불 실패 건이 증가하거나 SLA가 강화되면 Outbox 패턴으로 전환한다.

---

### 4. RefundEventHandler 패키지 위치 — `messaging` vs `external`

**현재 선택**: `RefundEventHandler`를 `infrastructure/messaging` 패키지에 배치한다.

**대안**: PG(Toss) 호출이 주 역할이므로 `infrastructure/external/toss`에 배치한다. 또는 `infrastructure/event` 같은 별도 패키지를 신설한다.

| | `infrastructure/messaging` (현재) | `infrastructure/external/toss` (대안) |
|---|---|---|
| 패키지 의미 | messaging = Kafka 관련 코드 → PG 호출 클래스가 섞임 | external/toss = Toss 전용 → AFTER_COMMIT 이벤트 수신 코드가 섞임 |
| KafkaPaymentEventPublisher와 거리 | 동일 패키지, 이벤트 처리 코드 한 곳에 | 분리 — Kafka 발행과 PG 호출이 패키지별로 명확 |
| 아키텍처 규칙 준수 | `infrastructure`는 `application.gateway.*` 참조 허용 — 위반 없음 | 동일 |

> **현재 선택 이유**: `RefundEventHandler`는 Spring 이벤트(`@TransactionalEventListener`)를 수신하는 리스너라는 점에서 이벤트 처리 코드와 같은 `messaging` 패키지에 두는 것이 응집도 측면에서 낫다. Toss 호출은 `PaymentGateway` 인터페이스를 통해 추상화되어 있으므로 `external/toss`와 직접적인 결합이 없다.

---

### 5. PaymentRefundRequestedEvent — ID만 전달 vs 엔티티 전달

**현재 선택**: `PaymentRefundRequestedEvent(UUID paymentId, UUID refundId)`로 ID만 담는다. `RefundEventHandler`에서 DB를 재조회한다.

**대안**: `PaymentRefundedEvent`처럼 엔티티(`Payment`, `Refund`)를 직접 담는다. DB 재조회를 생략한다.

| | ID만 전달 (현재) | 엔티티 전달 (대안) |
|---|---|---|
| DB 조회 | AFTER_COMMIT에서 재조회 1회 추가 | 재조회 없음 |
| 영속성 컨텍스트 | AFTER_COMMIT 시점에 1트랜잭션의 컨텍스트가 닫혀 있어 재조회가 안전 | LazyLoadingException 위험. 엔티티 상태가 flush 전 스냅샷일 수 있음 |
| REQUIRES_NEW와 궁합 | 새 트랜잭션에서 최신 상태를 조회하므로 정합성 보장 | 이전 트랜잭션의 엔티티를 새 트랜잭션에서 사용 — 영속성 컨텍스트 불일치 |

> **현재 선택 이유**: AFTER_COMMIT은 1트랜잭션의 영속성 컨텍스트가 이미 닫힌 시점이다. `RefundEventHandler`가 `@Transactional(REQUIRES_NEW)`로 새 트랜잭션을 열므로, 새 컨텍스트에서 DB를 재조회하는 것이 정합성 측면에서 안전하다. 재조회 비용(인덱스 PK 조회 1회)은 무시할 수 있다.

