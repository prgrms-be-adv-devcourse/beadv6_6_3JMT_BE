# 환불 API 코드 리뷰 반영 구현 결과 (PR #67 — junhee-ko)

브랜치: `fix/#215-refund-code-review` (base: `origin/chore/#214-payment-confirm-api-review`)

---

## 구현 요약

| 작업 | 내용 | 커밋 |
|------|------|------|
| 작업 2+3 | PG_ERROR → PG_INVALID_REQUEST 리네임, PG_SERVER_ERROR 추가, refund() 방어 코드 | `52258f5` |
| 작업 1 | 비관적 락 (findByIdForUpdate) | `66f037f` |
| 작업 5+6 | 스케줄러 null 처리 + 통계 로깅 | `193b304` |
| 작업 7 | 도메인 상태 전이 로깅 | `e8127b6` |
| 테스트 수정 | RefundPaymentServiceTest mock 스텁 갱신 | `4bf7232` |

---

## 변경 파일 상세

### `application/exception/PaymentErrorCode.java`

```java
// Before
PG_ERROR(HttpStatus.BAD_GATEWAY, "PAY003", "PG사 처리 중 오류가 발생했습니다."),

// After
PG_INVALID_REQUEST(HttpStatus.BAD_GATEWAY, "PAY003", "잘못된 API 요청으로 인한 PG사 오류입니다."),
PG_SERVER_ERROR(HttpStatus.BAD_GATEWAY, "PAY_PG_5XX", "PG사 서버 오류가 발생했습니다."),
```

에러 코드 의미:
- `PG_INVALID_REQUEST` — 우리 서버가 Toss에 잘못된 요청 전송 (우리 귀책)
- `PG_SERVER_ERROR` — Toss 서버 장애 또는 예상 외 응답 (Toss 귀책)
- `PAYMENT_FAILED` — 사용자 결제 실패 (사용자 귀책)

---

### `infrastructure/external/toss/TossPaymentGateway.java`

**`confirm()` 변경:**
- 4xx TOSS_SERVER_ERROR_CODES 분기: `PG_ERROR` → `PG_INVALID_REQUEST`
- 5xx: `PG_ERROR` → `PG_SERVER_ERROR`
- null 응답 체크: `PG_ERROR` → `PG_INVALID_REQUEST`

**`refund()` 변경:**
- 4xx: 기존 단순 `PG_ERROR` → TOSS_SERVER_ERROR_CODES 분기 적용 (`PG_INVALID_REQUEST` / `PAYMENT_FAILED`)
- 5xx: `PG_ERROR` → `PG_SERVER_ERROR`
- null 응답 방어 코드 추가 (`PG_SERVER_ERROR`)
- `cancels()` null/empty 방어 코드 추가 (`PG_SERVER_ERROR`)

---

### `domain/repository/PaymentRepository.java`

```java
Optional<Payment> findByIdForUpdate(UUID id);  // 추가
```

---

### `infrastructure/persistence/PaymentJpaRepository.java`

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT p FROM Payment p WHERE p.id = :id")
Optional<Payment> findByIdForUpdate(@Param("id") UUID id);  // 추가
```

---

### `infrastructure/persistence/PaymentRepositoryAdapter.java`

```java
@Override
public Optional<Payment> findByIdForUpdate(UUID id) {
    return jpaRepository.findByIdForUpdate(id);
}
```

---

### `application/service/RefundPaymentService.java`

```java
// findById → findByIdForUpdate
Payment payment = paymentRepository.findByIdForUpdate(command.paymentId())
    .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND));
```

---

### `infrastructure/scheduling/PaymentRefundRetryScheduler.java`

**refund null 처리 (REFUNDING 고착 버그 수정):**
```java
if (refund == null) {
    log.error("환불 내역 없음 — payment가 REFUNDING 상태이나 Refund 레코드 없음. paymentId={}", payment.getId());
    payment.restoreToRefundFailed();
    paymentRepository.save(payment);
    skipCount++;
    continue;
}
```

**통계 로깅:**
```java
log.info("환불 재시도 스케줄러 시작 — 대상 건수={}", stalePayments.size());
int successCount = 0, failCount = 0, skipCount = 0;
// ... 루프 내에서 카운터 증가 ...
log.info("환불 재시도 스케줄러 완료 — 성공={}, 실패={}, 건너뜀={}", successCount, failCount, skipCount);
```

---

### `domain/model/Payment.java`, `domain/model/Refund.java`

`@Slf4j` 추가, 상태 전이 메서드에 `log.debug` 삽입:

```java
// Payment.java
log.debug("Payment 상태 전이 — id={}, {} → REFUNDING", id, status);
log.debug("Payment 상태 전이 — id={}, {} → REFUNDED", id, status);
log.debug("Payment 상태 전이 — id={}, {} → PAID (환불 실패 복원)", id, status);

// Refund.java
log.debug("Refund 상태 전이 — id={}, {} → COMPLETED", id, status);
log.debug("Refund 상태 전이 — id={}, {} → FAILED", id, status);
```

---

## 트러블슈팅

### 테스트 실패 — RefundPaymentServiceTest 4건

**원인:** 작업 1에서 `RefundPaymentService`가 `findById` → `findByIdForUpdate`로 교체됐으나, 기존 테스트의 Mockito 스텁은 여전히 `findById`를 스텁하고 있었음.

**증상:**
- `UnnecessaryStubbingException` (엄격 모드 Mockito — 사용되지 않은 스텁 감지)
- `AssertionFailedError` (Optional.empty() 반환으로 PAYMENT_NOT_FOUND 예외 발생)

**해결:** `RefundPaymentServiceTest` 내 `when(paymentRepository.findById(...))` → `when(paymentRepository.findByIdForUpdate(...))` 일괄 교체.

**교훈:** 비관적 락을 위해 별도 메서드를 추출하면 해당 메서드를 사용하는 테스트의 stub도 함께 갱신해야 한다.

---

## 테스트 결과

```
./gradlew :payment-service:test
BUILD SUCCESSFUL
35 tests completed, 0 failed
```

---

## 미구현 (별도 이슈)

| # | 내용 | 이유 |
|---|------|------|
| #9 | 환불 사유 Toss API 전달 | API 설계 변경 수반 (Request Body 신규) |
| 통합 테스트 추가 | 동시 환불 2개 → 1개만 성공 | 비관적 락 E2E 검증 (별도 작업) |

