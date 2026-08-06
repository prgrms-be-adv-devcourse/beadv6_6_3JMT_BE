# 환불 API 코드 리뷰 반영 계획 (PR #67 — junhee-ko)

PR #67 (`feat: 환불 API 구현`) 에 대한 junhee-ko의 코드 리뷰 코멘트 13개를 분석하여 반영/미반영 결정 및 구현 계획을 정리한다.

---

## 리뷰 코멘트 반영 결정 매트릭스

| # | 파일 | 내용 | 결정 | 우선순위 |
|---|------|------|------|----------|
| 1 | `Payment.java:176` | `startRefunding()` 상태 로깅 | ✅ 반영 | LOW |
| 2 | `Payment.java:183` | `completeRefund()` 상태 로깅 | ✅ 반영 | LOW |
| 3 | `Payment.java:191` | `restoreToRefundFailed()` 상태 로깅 | ✅ 반영 | LOW |
| 4 | `Refund.java:96` | `complete()` 상태 로깅 | ✅ 반영 | LOW |
| 5 | `Refund.java:104` | `fail()` 상태 로깅 | ✅ 반영 | LOW |
| 6 | `RefundPaymentService.java:29` | 동시 요청 중복 환불 위험 | ✅ 반영 | **HIGH** |
| 7 | `TossPaymentGateway.java:108` | `response` null → NPE | ✅ 반영 | **HIGH** |
| 8 | `TossPaymentGateway.java:108` | `cancels()` empty → IndexOutOfBoundsException | ✅ 반영 | **HIGH** |
| 9 | `TossPaymentGateway.java:82` | 환불 사유를 Toss API에 전달 | ❌ 미반영 (추후 도입) | — |
| 10 | `TossPaymentGateway.java:101` | 4xx/5xx 동일 에러 코드(`PG_ERROR`) | ✅ 반영 | MEDIUM |
| 11 | `PaymentRefundRetryScheduler.java:41` | `refund == null` 시 Payment가 REFUNDING으로 고착 | ✅ 반영 | **HIGH** |
| 12 | `PaymentRefundRetryScheduler.java:31` | 다중 인스턴스에서 스케줄러 중복 실행 | ⚠️ 부분 반영 | MEDIUM |
| 13 | `PaymentRefundRetryScheduler.java:33` | 스케줄러 실행 통계 로깅 | ✅ 반영 | LOW |

---

## 미반영 / 부분 반영 근거

### #9 — 환불 사유 Toss API 전달 (미반영 — 추후 도입)

**이유:** 반영하려면 `POST /payments/{paymentId}/refund` 엔드포인트에 Request Body를 새로 추가해야 한다(현재 없음). API 설계 변경을 수반하므로 이번 코드 리뷰 반영 범위에서 제외하고 별도 이슈로 분리한다.

**현황:** `Refund` 엔티티에 `reason` 필드는 이미 존재하지만 현재 항상 `null`로 저장 중. 추후 도입 시 `presentation/dto/request/RefundRequest.java` 신규 생성 + command → gateway 전체 체인 수정 필요.

---

### #12 — 스케줄러 다중 인스턴스 중복 실행 (부분 반영)

**이유:** Toss 환불 API는 `Idempotency-Key: refund-{paymentId}`를 사용하여 멱등성이 이미 보장된다(유효기간 15일). 두 인스턴스가 동시에 동일 payment를 처리해도 Toss 레벨에서 이중 청구는 발생하지 않는다.

**부분 반영 내용:** `PaymentRepository`에 `findByIdForUpdate()` (비관적 락) 메서드를 추가해 스케줄러 내 개별 payment 처리 시 DB 레벨 락을 추가한다.

**미반영 내용:** ShedLock/Redis 분산 락 도입은 현재 단일 인스턴스 환경(개발 MVP)에서 복잡도 대비 효과가 낮다. 수평 확장 시 별도 태스크로 분리한다.

---

## 구현 상세 계획

### 작업 1 — 버그 수정: 비관적 락으로 중복 환불 방지 (HIGH)

**원인:** `RefundPaymentService.refund()`에서 두 스레드가 동시에 `status == PAID` 검증을 통과하면 중복 `Refund` 저장 및 이중 환불 트리거 가능.

**변경 파일:**
- `domain/repository/PaymentRepository.java` — `findByIdForUpdate(UUID id)` 메서드 추가 (`@Lock(PESSIMISTIC_WRITE)`)
- `infrastructure/persistence/RefundRepositoryAdapter.java` — 해당 메서드 위임 구현
- `application/service/RefundPaymentService.java` — `findById` → `findByIdForUpdate` 교체

```java
// PaymentRepository.java (추가)
Optional<Payment> findByIdForUpdate(UUID id);

// RefundRepositoryAdapter.java (추가)
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT p FROM Payment p WHERE p.id = :id")
Optional<Payment> findByIdForUpdate(@Param("id") UUID id);
```

**참고:** `@Lock`은 JPA 쿼리 메서드에 직접 붙이므로 `infrastructure.persistence` 레이어(JPA Repository)에만 존재한다. 도메인 `PaymentRepository` 인터페이스에는 일반 메서드 시그니처만 선언하고, `RefundRepositoryAdapter`가 위임 시 락을 적용한다.

> 실제로는 `JpaPaymentRepository`(Spring Data JPA 인터페이스)에 `@Lock` + `@Query`를 붙이고, `RefundRepositoryAdapter`는 이를 호출하는 구조가 더 자연스럽다.

---

### 작업 2 — 버그 수정: TossPaymentGateway 방어 코드 추가 (HIGH)

**변경 파일:** `infrastructure/external/toss/TossPaymentGateway.java`

**수정 범위:** `refund()` 메서드 내 `response.cancels().get(...)` 호출부 (현재 null/empty 체크 없음)

```java
// 현재 (refund() 메서드 말미)
TossRefundResponse.TossCancel lastCancel = response.cancels().get(response.cancels().size() - 1);
return new RefundResult(lastCancel.canceledAt());

// 변경 후
if (response == null) {
    throw new PaymentGatewayException(PaymentErrorCode.PG_SERVER_ERROR, "NULL_RESPONSE", "PG사 환불 응답이 없습니다.", null, null);
}
List<TossRefundResponse.TossCancel> cancels = response.cancels();
if (cancels == null || cancels.isEmpty()) {
    throw new PaymentGatewayException(PaymentErrorCode.PG_SERVER_ERROR, "NO_CANCEL_DATA", "Toss 환불 응답에 취소 내역이 없습니다.", null, null);
}
TossRefundResponse.TossCancel lastCancel = cancels.get(cancels.size() - 1);
return new RefundResult(lastCancel.canceledAt());
```

> **에러 코드 선택 근거:** null 응답·취소 내역 없음은 Toss 서버의 예상 외 동작 → `PG_SERVER_ERROR`. 우리 코드 버그가 아님.

---

### 작업 3 — 설계 개선: 에러 코드 구분 (MEDIUM)

**변경 파일:**
- `application/exception/PaymentErrorCode.java` — `PG_ERROR` → `PG_INVALID_REQUEST` 리네임 + `PG_SERVER_ERROR` 추가
- `infrastructure/external/toss/TossPaymentGateway.java` — 기존 `PG_ERROR` 사용처 교체 + 5xx 핸들러에서 `PG_SERVER_ERROR` 사용

```java
// PaymentErrorCode.java — 기존 PG_ERROR 리네임 + 신규 추가
// Before: PG_ERROR(HttpStatus.BAD_GATEWAY, "PAY003", "PG사 처리 중 오류가 발생했습니다."),
PG_INVALID_REQUEST(HttpStatus.BAD_GATEWAY, "PAY003", "잘못된 API 요청으로 인한 PG사 오류입니다."),
PG_SERVER_ERROR(HttpStatus.BAD_GATEWAY, "PAY_PG_5XX", "PG사 서버 오류가 발생했습니다."),
```

**에러 코드 의미 정의:**

| 에러 코드 | HTTP | 의미 | 원인 귀책 |
|---|---|---|---|
| `PG_INVALID_REQUEST` (PAY003) | 502 | Toss에 잘못된 요청 전송 | 우리 서버 (통합 버그) |
| `PG_SERVER_ERROR` (PAY_PG_5XX) | 502 | Toss 서버 장애 | Toss 서버 |
| `PAYMENT_FAILED` (PAY_FAILED) | 422 | 사용자 결제 실패 | 사용자 |

**TossPaymentGateway.java 변경 대상:**

```java
// confirm() — 4xx TOSS_SERVER_ERROR_CODES 분기: PG_ERROR → PG_INVALID_REQUEST
PaymentErrorCode errorCode = TOSS_SERVER_ERROR_CODES.contains(error.code())
    ? PaymentErrorCode.PG_INVALID_REQUEST   // 변경
    : PaymentErrorCode.PAYMENT_FAILED;

// confirm() — null response 체크 (#214에서 추가된 코드): PG_ERROR → PG_INVALID_REQUEST
// (null 응답은 우리 요청 문제일 수도 있으나, confirm()에서는 #214 코드 일관성 유지 차원에서 변경)
throw new PaymentGatewayException(PaymentErrorCode.PG_INVALID_REQUEST, "NULL_RESPONSE", ...);

// confirm() — 5xx 핸들러: PG_ERROR → PG_SERVER_ERROR
throw new PaymentGatewayException(PaymentErrorCode.PG_SERVER_ERROR, ...);

// refund() — 4xx TOSS_SERVER_ERROR_CODES 분기 (신규): PG_INVALID_REQUEST
PaymentErrorCode errorCode = TOSS_SERVER_ERROR_CODES.contains(error.code())
    ? PaymentErrorCode.PG_INVALID_REQUEST
    : PaymentErrorCode.PAYMENT_FAILED;

// refund() — null/empty 방어 코드 (작업 2): PG_SERVER_ERROR (이미 작업 2에 반영)
// refund() — 5xx 핸들러: PG_ERROR → PG_SERVER_ERROR
throw new PaymentGatewayException(PaymentErrorCode.PG_SERVER_ERROR, ...);
```

**영향 범위:** `PaymentErrorCode.PG_ERROR` 참조 전체 → `PG_INVALID_REQUEST`로 교체. `confirm()` 4xx 분류 로직은 `#214`에서 이미 구현됨 — 에러 코드 상수명만 교체한다.

---

### 작업 5 — 버그 수정: 스케줄러 refund null 처리 (HIGH)

**변경 파일:** `infrastructure/scheduling/PaymentRefundRetryScheduler.java`

**현재 코드 (line 41~44):**
```java
if (refund == null) {
    continue;  // Payment가 REFUNDING으로 계속 고착됨
}
```

**수정 후:**
```java
if (refund == null) {
    log.error("환불 내역 없음 — payment는 REFUNDING 상태이나 Refund 레코드가 없음. paymentId={}", payment.getId());
    payment.restoreToRefundFailed();  // REFUNDING → PAID 복원
    paymentRepository.save(payment);
    continue;
}
```

---

### 작업 6 — 관측 가능성: 스케줄러 실행 통계 로깅 (LOW)

**변경 파일:** `infrastructure/scheduling/PaymentRefundRetryScheduler.java`

```java
@Scheduled(fixedDelay = 600_000)
@Transactional
public void retryStaleRefunding() {
    OffsetDateTime threshold = OffsetDateTime.now().minusMinutes(30);
    List<Payment> stalePayments = paymentRepository
        .findByStatusAndUpdatedAtBefore(PaymentStatus.REFUNDING, threshold);

    log.info("환불 재시도 스케줄러 시작 — 대상 건수={}", stalePayments.size());  // 추가
    int successCount = 0, failCount = 0, skipCount = 0;  // 추가

    for (Payment payment : stalePayments) {
        // ... 기존 로직 ...
        // 성공 시 successCount++, 실패 시 failCount++, skip 시 skipCount++
    }

    log.info("환불 재시도 스케줄러 완료 — 성공={}, 실패={}, 건너뜀={}", successCount, failCount, skipCount);  // 추가
}
```

---

### 작업 7 — 관측 가능성: 도메인 모델 상태 전이 로깅 (LOW)

**변경 파일:** `domain/model/Payment.java`, `domain/model/Refund.java`

`@Slf4j`를 추가하고 각 상태 전이 메서드에 로그를 추가한다.

> **아키텍처 규칙 검토:** `domain.model`은 "외부 기술 의존 없음"이 원칙이나, 현재 이미 JPA 어노테이션을 사용하는 실용적 타협이 적용되어 있다. SLF4J는 로깅 추상 레이어로 동일한 맥락에서 허용한다.

```java
// Payment.java
@Slf4j
public class Payment {
    public void startRefunding() {
        if (this.status != PaymentStatus.PAID) {
            throw new IllegalStateException("PAID 상태에서만 REFUNDING으로 전환할 수 있습니다.");
        }
        log.debug("Payment 상태 전이 — id={}, {} → REFUNDING", this.id, this.status);
        this.status = PaymentStatus.REFUNDING;
    }
    // completeRefund(), restoreToRefundFailed() 동일 패턴 적용
}

// Refund.java
@Slf4j
public class Refund {
    public void complete(OffsetDateTime completedAt) {
        if (this.status != RefundStatus.REQUESTED) {
            throw new InvalidRefundStateException("REQUESTED 상태에서만 COMPLETED로 전환할 수 있습니다.");
        }
        log.debug("Refund 상태 전이 — id={}, {} → COMPLETED", this.id, this.status);
        this.status = RefundStatus.COMPLETED;
        this.completedAt = completedAt;
    }
    // fail() 동일 패턴 적용
}
```

---

## 테스트 추가 계획

| 대상 | 추가 테스트 케이스 |
|------|------------------|
| `RefundPaymentServiceTest` | 동시 요청 시나리오 (비관적 락 검증은 통합 테스트로) |
| `RefundPaymentIntegrationTest` | 동시 환불 요청 2개 → 1개만 성공 검증 |
| `TossPaymentGatewayTest` | `response == null` → 예외, `cancels()` 비어 있음 → 예외 |
| `PaymentRefundRetrySchedulerTest` | `refund == null` → Payment PAID 복원 검증 |

---

## 작업 순서 (권장)

```
[HIGH] 작업 2 (TossGateway 방어 코드)
[HIGH] 작업 1 (비관적 락)
[HIGH] 작업 5 (스케줄러 null 처리)
  ↓
[MEDIUM] 작업 3 (에러 코드 구분)
  ↓
[LOW] 작업 6 (스케줄러 로깅)
[LOW] 작업 7 (도메인 상태 로깅)
```

> 작업 4 (환불 사유 전달)는 API 설계 변경 수반으로 이번 범위에서 제외. 추후 별도 이슈로 처리.

---

## 관련 문서

- PR #67: https://github.com/prgrms-be-adv-devcourse/beadv6_6_3JMT_BE/pull/67
- API 설계: `.claude/docs/api-design.md`
- 아키텍처 규칙: `.claude/rules/architecture.md`
