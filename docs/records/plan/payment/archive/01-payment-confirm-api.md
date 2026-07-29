# 결제 승인 API 구현 계획

`POST /api/v1/payments/confirm` — Toss Payments 연동 결제 승인

---

## 확정 사항

- **Order 서비스 동기 호출 없음**: 요청 Body의 `amount`를 그대로 신뢰한다. `OrderQueryGateway`는 구현하지 않는다.
- **`isTest` 결정 방식**: 환경 프로파일 설정값(`${payment.toss.test-mode:false}`)으로 결정한다. 요청 Body나 헤더로 받지 않는다.

---

## 전체 처리 흐름

```
POST /api/v1/payments/confirm
  [X-User-Id, X-User-Role 헤더 — Gateway 주입]

  PaymentController
    └─▶ ConfirmPaymentCommand 변환
    └─▶ ConfirmPaymentUseCase.confirm(command)

        ConfirmPaymentInteractor  [@Transactional 경계]
          1. idempotencyKey = "pay-{orderId}" 중복 확인
             → 중복이면 PAY002 409 반환
          2. Payment.create(…) — READY 상태 → 저장
          3. payment.markRequested() — REQUESTED 전환 → 저장
          4. TossPaymentGateway.confirm(paymentKey, orderId, amount) 호출
             → 성공: payment.approve(approvedAmount, paymentMethod, approvedAt) — PAID 전환 → 저장
                     applicationEventPublisher.publishEvent(new PaymentApprovedEvent(payment))
             → 실패: payment.fail(failureCode, failureReason, failedAt) — FAILED 전환 → 저장
                     PAY_FAILED 400 반환
          5. 트랜잭션 COMMIT
          6. KafkaPaymentEventPublisher.onPaymentApproved() [AFTER_COMMIT 단계]
             → PaymentApprovedMessage 생성 → KafkaTemplate.send()

    └─▶ PaymentResult(paymentId) 반환
    └─▶ ConfirmPaymentResponse 변환
    └─▶ ApiResponse.success(response) 래핑
    └─▶ HTTP 200
```

---

## 신규 파일 목록

### domain

| 파일 | 설명 |
|---|---|
| `domain/model/Payment.java` | **수정**: 상태 전환 메서드 3개 추가 |

#### Payment.java 추가 메서드

```java
// READY → REQUESTED
public void markRequested(OffsetDateTime requestedAt) { ... }

// REQUESTED → PAID
public void approve(int approvedAmount, String paymentMethod, String responsePayload, OffsetDateTime approvedAt) { ... }

// REQUESTED → FAILED
public void fail(String failureCode, String failureReason, String requestPayload, String responsePayload, OffsetDateTime failedAt) { ... }
```

- 상태 불변 조건 위반 시 `IllegalStateException` (도메인 로직, 외부 의존 없음)

---

### application

| 파일 | 설명 |
|---|---|
| `application/dto/command/ConfirmPaymentCommand.java` | 신규 record |
| `application/dto/result/PaymentResult.java` | 신규 record |
| `application/gateway/external/PaymentGateway.java` | 신규 인터페이스 |
| `application/gateway/messaging/PaymentEventPublisher.java` | 신규 인터페이스 |
| `application/usecase/ConfirmPaymentUseCase.java` | 신규 인터페이스 |
| `application/usecase/ConfirmPaymentInteractor.java` | 신규 구현체 |

#### ConfirmPaymentCommand.java

```java
// application/dto/command
public record ConfirmPaymentCommand(
    String paymentKey,
    UUID orderId,
    int amount,
    UUID userId
) {}
```

#### PaymentResult.java

```java
// application/dto/result
public record PaymentResult(UUID paymentId) {}
```

#### PaymentGateway.java (application/gateway/external)

```java
public interface PaymentGateway {
    TossConfirmResult confirm(String paymentKey, UUID orderId, int amount);
}
```

> `TossConfirmResult`는 `application/gateway/external` 패키지의 내부 record로 정의한다.  
> Toss 전용 DTO는 `infrastructure.external.toss.dto`에 두되, application 레이어가 직접 참조하면 안 되므로 별도 result 타입이 필요하다.

```java
// application/gateway/external/TossConfirmResult.java
public record TossConfirmResult(
    String paymentMethod,
    int approvedAmount,
    String responsePayload,
    OffsetDateTime approvedAt
) {}
```

#### PaymentEventPublisher.java (application/gateway/messaging)

```java
public interface PaymentEventPublisher {
    void publishApproved(Payment payment);
}
```

#### ConfirmPaymentUseCase.java (application/usecase)

```java
public interface ConfirmPaymentUseCase {
    PaymentResult confirm(ConfirmPaymentCommand command);
}
```

#### ConfirmPaymentInteractor.java (application/usecase)

```java
@Service
@Transactional
public class ConfirmPaymentInteractor implements ConfirmPaymentUseCase {

    private final PaymentRepository paymentRepository;
    private final PaymentGateway paymentGateway;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final boolean testMode; // @Value("${payment.toss.test-mode:false}") 주입

    // ...생성자 주입...

    @Override
    public PaymentResult confirm(ConfirmPaymentCommand command) {
        // 1. 멱등성 키 중복 확인
        String idempotencyKey = "pay-" + command.orderId();
        paymentRepository.findByIdempotencyKey(idempotencyKey)
            .ifPresent(p -> { throw new BusinessException(PaymentErrorCode.DUPLICATE_PAYMENT); });

        // 2. Payment 생성 (READY)
        Payment payment = Payment.create(
            command.orderId(), command.userId(),
            command.paymentKey(), "TOSS_PAYMENTS", "CARD", testMode,
            command.amount(), 0
        );
        paymentRepository.save(payment);

        // 3. REQUESTED 전환 → Toss 호출
        payment.markRequested(OffsetDateTime.now());
        paymentRepository.save(payment);

        try {
            TossConfirmResult result = paymentGateway.confirm(
                command.paymentKey(), command.orderId(), command.amount()
            );
            // 4a. PAID 전환
            payment.approve(result.approvedAmount(), result.paymentMethod(),
                            result.responsePayload(), result.approvedAt());
            paymentRepository.save(payment);

            // Spring 내부 이벤트 발행 → AFTER_COMMIT 단계에서 Kafka로 전달
            applicationEventPublisher.publishEvent(new PaymentApprovedEvent(payment));

            return new PaymentResult(payment.getId());

        } catch (PaymentGatewayException e) {
            // 4b. FAILED 전환
            payment.fail(e.getFailureCode(), e.getFailureReason(),
                         e.getRequestPayload(), e.getResponsePayload(), OffsetDateTime.now());
            paymentRepository.save(payment);
            throw new BusinessException(e.getErrorCode(), e.getFailureReason());
        }
    }
}
```

---

### interfaces

| 파일 | 설명 |
|---|---|
| `interfaces/web/dto/request/ConfirmPaymentRequest.java` | 신규 record |
| `interfaces/web/dto/response/ConfirmPaymentResponse.java` | 신규 record |
| `interfaces/web/PaymentController.java` | 신규 컨트롤러 |
| `interfaces/web/PaymentExceptionHandler.java` | 신규 ExceptionHandler |

#### ConfirmPaymentRequest.java

```java
public record ConfirmPaymentRequest(
    @NotBlank String paymentKey,
    @NotNull UUID orderId,
    @Positive int amount
) {}
```

#### ConfirmPaymentResponse.java

```java
public record ConfirmPaymentResponse(UUID paymentId) {}
```

#### PaymentController.java

```java
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final ConfirmPaymentUseCase confirmPaymentUseCase;

    @PostMapping("/confirm")
    public ResponseEntity<ApiResponse<ConfirmPaymentResponse>> confirm(
        @RequestHeader("X-User-Id") UUID userId,
        @Valid @RequestBody ConfirmPaymentRequest request
    ) {
        ConfirmPaymentCommand command = new ConfirmPaymentCommand(
            request.paymentKey(), request.orderId(), request.amount(), userId
        );
        PaymentResult result = confirmPaymentUseCase.confirm(command);
        return ResponseEntity.ok(ApiResponse.success(new ConfirmPaymentResponse(result.paymentId())));
    }
}
```

#### PaymentExceptionHandler.java

```java
@RestControllerAdvice
public class PaymentExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e) {
        return ResponseEntity.status(e.getErrorCode().getStatus())
            .body(ErrorResponse.of(e.getErrorCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
            .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
            .collect(Collectors.joining(", "));
        return ResponseEntity.badRequest()
            .body(ErrorResponse.of(PaymentErrorCode.INVALID_INPUT, message));
    }
}
```

---

### infrastructure

| 파일 | 설명 |
|---|---|
| `infrastructure/persistence/PaymentJpaRepository.java` | **수정**: `findByIdempotencyKey` 추가 |
| `infrastructure/persistence/PaymentRepositoryAdapter.java` | **수정**: 동일 메서드 위임 |
| `application/gateway/persistence/PaymentRepository.java` | **수정**: `findByIdempotencyKey` 인터페이스 추가 |
| `infrastructure/external/toss/TossPaymentGateway.java` | 신규 PaymentGateway 구현체 |
| `infrastructure/external/toss/dto/TossConfirmRequest.java` | 신규 Toss 요청 DTO |
| `infrastructure/external/toss/dto/TossConfirmResponse.java` | 신규 Toss 응답 역직렬화 DTO |
| `infrastructure/external/toss/PaymentGatewayException.java` | 신규 Toss 오류 래퍼 예외 |
| `infrastructure/messaging/KafkaPaymentEventPublisher.java` | 신규 Kafka 발행 핸들러 |
| `infrastructure/messaging/config/PaymentTopic.java` | **수정**: 토픽 상수 추가 |
| `infrastructure/messaging/config/KafkaConfig.java` | **수정**: NewTopic 빈 추가 |

#### PaymentJpaRepository.java 추가 쿼리

```java
Optional<Payment> findByIdempotencyKey(String idempotencyKey);
```

#### TossPaymentGateway.java 핵심 로직

- Toss Payments API 엔드포인트: `POST https://api.tosspayments.com/v1/payments/confirm`
- 인증: Basic Auth (`시크릿키:` Base64 인코딩)
- HTTP 클라이언트: `RestClient` (Spring 6.1+, Spring Boot 4.1에 포함)
- 4xx/5xx 응답 시 `PaymentGatewayException`으로 변환
  - Toss 응답 `code`, `message` → `failureCode`, `failureReason` 매핑
  - 5xx → PAY003 (PG사 처리 오류)
  - 4xx → PAY_FAILED (PG사 결제 실패)

#### TossConfirmRequest.java / TossConfirmResponse.java (패키지 외부 노출 금지)

```java
// infrastructure/external/toss/dto
record TossConfirmRequest(String paymentKey, String orderId, int amount) {}

record TossConfirmResponse(
    String paymentKey,
    String orderId,
    String method,         // → paymentMethod
    int totalAmount,       // → approvedAmount
    String approvedAt,     // ISO 8601 → OffsetDateTime 파싱
    String requestedAt,
    String status
) {}
```

#### KafkaPaymentEventPublisher.java

`PaymentEventPublisher` 인터페이스를 구현하지 않는다. `@TransactionalEventListener(AFTER_COMMIT)`으로 `PaymentApprovedEvent`를 수신해 Kafka에 발행한다.

```java
@Component
@RequiredArgsConstructor
public class KafkaPaymentEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentApproved(PaymentApprovedEvent event) {
        Payment payment = event.payment();
        PaymentApprovedMessage message = new PaymentApprovedMessage(
            "payment.approved",
            payment.getId(), payment.getOrderId(), payment.getUserId(),
            payment.getApprovedAmount(),
            payment.getApprovedAt() != null ? payment.getApprovedAt().toString() : null
        );
        kafkaTemplate.send(PaymentTopic.PAYMENT_APPROVED, payment.getOrderId().toString(), message);
    }
}
```

#### PaymentApprovedMessage.java (infrastructure/messaging/dto)

```java
public record PaymentApprovedMessage(
    String eventType,
    UUID paymentId,
    UUID orderId,
    UUID userId,
    int amount,
    String approvedAt  // OffsetDateTime이 아닌 ISO 8601 문자열
) {}
```

#### PaymentTopic.java 수정

```java
public static final String PAYMENT_APPROVED = "payment.approved";
```

#### KafkaConfig.java 수정

```java
@Bean
public NewTopic paymentApprovedTopic() {
    return TopicBuilder.name(PaymentTopic.PAYMENT_APPROVED)
        .partitions(1).replicas(1).build();
}
```

---

### 에러 코드

```
domain 또는 interfaces/web (어디에 두든 무방하나, api-error-handling.md에 따라 payment-service 내부 소유)
→ interfaces/web/PaymentErrorCode.java (또는 별도 패키지)
```

| 상수 | HTTP | code | 메시지 |
|---|---|---|---|
| `INVALID_INPUT` | 400 | `V001` | 입력값 오류 |
| `DUPLICATE_PAYMENT` | 409 | `PAY002` | 이미 결제된 주문입니다 |
| `PG_ERROR` | 502 | `PAY003` | PG사 처리 중 오류가 발생했습니다 |
| `PAYMENT_FAILED` | 400 | `PAY_FAILED` | PG사 결제가 실패했습니다 |

구현: `PaymentErrorCode` enum이 `com.prompthub.exception.ErrorCode` 인터페이스를 구현한다.

---

## 구현 순서 (TDD)

각 단계는 **실패 테스트 작성 → 구현 → 통과** 순으로 진행한다.

### Step 1. domain — Payment 상태 전환 메서드
- `Payment`에 `markRequested`, `approve`, `fail` 메서드 추가
- 단위 테스트: `PaymentTest.java`
  - 정상 상태 전환 검증
  - 잘못된 상태에서 전환 시 `IllegalStateException` 검증

### Step 2. application — PaymentRepository 인터페이스 확장
- `PaymentRepository`에 `findByIdempotencyKey` 추가
- `PaymentJpaRepository`, `PaymentRepositoryAdapter` 수정
- 영속성 테스트: `PaymentJpaRepositoryTest` — 기존 패턴 유지

### Step 3. application — 에러 코드 및 게이트웨이 인터페이스
- `PaymentErrorCode.java` 작성
- `PaymentGateway`, `PaymentEventPublisher` 인터페이스 작성
- `TossConfirmResult`, `PaymentApprovedEvent` record 작성

### Step 4. application — ConfirmPaymentInteractor (단위 테스트)
- `ConfirmPaymentInteractorTest.java`: Mockito로 게이트웨이 모킹
  - 멱등성 키 중복 시 PAY002 예외
  - Toss 성공 시 PAID 상태 + PaymentApprovedEvent 발행 검증
  - Toss 실패 시 FAILED 상태 + PAY_FAILED 예외

### Step 5. infrastructure — Toss 연동
- `TossPaymentGateway.java` 구현
- `application.yml`에 Toss 시크릿키 설정 (`payment.toss.secret-key`, `payment.toss.test-mode`)
- 단위 테스트: MockServer 또는 `MockRestServiceServer`로 Toss API 응답 시뮬레이션
  - 성공 응답 → `TossConfirmResult` 변환 검증
  - 4xx/5xx → `PaymentGatewayException` 변환 검증

### Step 6. infrastructure — Kafka 이벤트 발행
- `KafkaPaymentEventPublisher.java` 구현 (`@TransactionalEventListener(AFTER_COMMIT)` 리스너로 `PaymentApprovedEvent` 수신 → Kafka 발행)
- `PaymentTopic`, `KafkaConfig` 수정
- `PaymentApprovedMessage.approvedAt`은 `String` 타입으로 정의

### Step 7. interfaces — Controller + ExceptionHandler
- `PaymentController`, `PaymentExceptionHandler` 작성
- MockMvc 단위 테스트: `PaymentControllerTest.java`
  - `MockMvcBuilders.standaloneSetup()` + `@ExtendWith(MockitoExtension.class)` 사용
  - `@Valid` 검증 실패 → 400 V001
  - UseCase 성공 → 200 + paymentId
  - PAY002 예외 → 409
  - PAY_FAILED 예외 → 400

### Step 8. 통합 테스트
- `ConfirmPaymentIntegrationTest.java`
- Testcontainers `KafkaContainer`(`confluentinc/cp-kafka:7.6.1`) 사용
- HTTP 클라이언트: `RestTemplate`
- Kafka 소비: `consumer.assign()` + `consumer.seekToBeginning()` 사용
- `PaymentGateway`는 `@MockitoBean`으로 대체 (실제 Toss API 미호출)
- 정상 플로우 전체 검증: DB 상태 PAID + Kafka 메시지 수신

---

## 설정 추가 (application.yml)

```yaml
payment:
  toss:
    secret-key: ${TOSS_SECRET_KEY}
    base-url: https://api.tosspayments.com/v1
    test-mode: ${TOSS_TEST_MODE:false}
```

---

## 미구현 범위 (이번 계획 외)

- `X-Request-Id` 분산 추적 헤더 전파
- Toss Webhook 수신 (`/api/v1/payments/webhook`)
- 환불 API (`POST /api/v1/payments/{paymentId}/refund`)

---

## 트레이드오프

이 계획에서 대안이 존재했던 설계 결정을 정리한다.

---

### 1. @Transactional 경계 — Toss API 호출을 트랜잭션 안에 포함

**현재 선택**: Interactor 전체(`confirm()`)가 단일 `@Transactional` 경계 안에 있다. Toss API 호출도 트랜잭션 내에서 실행된다.

**대안**: READY 저장 후 트랜잭션 커밋 → Toss 호출 → 새 트랜잭션으로 PAID/FAILED 저장.

| | 현재 (단일 트랜잭션) | 대안 (트랜잭션 분리) |
|---|---|---|
| DB 커넥션 점유 | Toss 응답 대기(수백ms~수초) 동안 커넥션 유지 | 커넥션 빠르게 반납 |
| 원자성 | Payment 생성과 상태 업데이트가 하나의 트랜잭션 | Toss 성공 후 PAID 저장 실패 시 불일치 가능 |
| 장애 복구 | 롤백 시 Payment 레코드 자체가 사라짐 (재시도 가능) | 실패 시 READY 레코드가 남아 있어 Webhook으로 복구 가능 |

> **현재 선택 이유**: 세미 MVP 단계에서 커넥션 풀 압박보다 구현 단순성과 원자성을 우선한다. 트래픽이 늘면 트랜잭션 분리로 전환할 수 있다.

---

### 2. Kafka 이벤트 발행 방식 — ApplicationEventPublisher + @TransactionalEventListener(AFTER_COMMIT)

**현재 구현**: Interactor가 `ApplicationEventPublisher.publishEvent(new PaymentApprovedEvent(payment))`로 Spring 내부 이벤트를 발행 → `KafkaPaymentEventPublisher`의 `@TransactionalEventListener(AFTER_COMMIT)` 리스너가 Kafka에 전달한다.

| | ApplicationEvent 중계 (현재) | PaymentEventPublisher 직접 호출 (대안) |
|---|---|---|
| 발행 시점 | 트랜잭션 커밋 후(AFTER_COMMIT) — at-least-once 의미론 보장 | 트랜잭션 커밋 전 (DB 커밋 전에 Kafka 메시지 발행 가능) |
| 에러 가시성 | 발행 오류가 HTTP 응답에 노출되지 않음 (로그만) | 발행 오류가 트랜잭션 롤백을 유발, HTTP 500 반환 |
| 의존 방향 | Interactor가 infrastructure에 의존하지 않음 | Interactor가 gateway 인터페이스에만 의존 — 위반 없음 |

> **트레이드오프**: AFTER_COMMIT 단계 오류는 HTTP 응답에 노출되지 않으므로 Kafka 발행 실패 시 유실 가능성이 있다.  
> 이벤트 유실 없는 보장이 필요해지면 Transactional Outbox 패턴으로 전환한다.

---

### 3. 멱등성 처리 — 중복 요청 시 409 반환

**현재 선택**: `idempotency_key` 중복 시 `PAY002 409` 예외를 던진다.

**대안**: 이미 `PAID` 상태인 Payment가 존재하면 기존 `paymentId`를 그대로 200으로 반환한다(api-design.md 주석의 "기존 Payment를 그대로 반환" 문구).

| | 409 반환 (현재) | 200 + 기존 결과 반환 (대안) |
|---|---|---|
| 프론트 처리 | 클라이언트가 409를 별도 분기 처리 필요 | 단순 — 성공으로 처리 가능 |
| 안전성 | 재결제 시도를 명시적으로 차단 | 이미 PAID인 경우만 200, FAILED/READY는 별도 로직 필요 |
| 구현 복잡도 | 단순 | 상태별 분기 필요 |

> **현재 선택 이유**: 구현 단순성과 명시적 오류 전달을 우선한다. 필요 시 클라이언트 협의 후 200 반환으로 전환 가능하다.

---

### 4. Payment 저장 횟수 — READY/REQUESTED/PAID 3회 저장

**현재 선택**: `Payment.create()`(READY) → `markRequested()`(REQUESTED) → `approve()`(PAID)로 상태마다 저장한다 (총 3회 INSERT/UPDATE).

**대안A**: READY 저장을 생략하고 REQUESTED부터 시작 (2회).  
**대안B**: REQUESTED와 Toss 호출을 묶어 한 번에 처리 후 PAID로 바로 저장 (2회).

| | 3회 저장 (현재) | 2회 저장 (대안) |
|---|---|---|
| 장애 추적 | Toss 호출 직전 상태가 DB에 남아 디버깅 용이 | 중간 상태 유실 |
| 성능 | DB 쓰기 1회 추가 | 미미한 성능 이점 |

> **현재 선택 이유**: `requested_at` 컬럼이 스키마에 명시되어 있고, 결제 이슈 발생 시 Toss 호출 전후를 DB에서 추적할 수 있어야 한다.

---

### 5. PaymentApprovedEvent — 위치 결정

**원래 계획**: Spring `ApplicationEventPublisher` 중계를 위해 `PaymentApprovedEvent` record가 필요하나, `domain/event` vs `application/usecase` 위치 미결정.

**실제 구현**: `domain/event/PaymentApprovedEvent.java`로 생성. `Payment` 객체를 보유하는 순수 Java record이며 외부 기술 의존이 없어 `domain` 레이어에 배치하는 것이 적합하다.

```java
// domain/event/PaymentApprovedEvent.java
public record PaymentApprovedEvent(Payment payment) {}
```
