# 결제 승인 API 코드 리뷰 반영 — 구현 결과

계획 문서: `10-confirm-api-code-review.md`
브랜치: `chore/#214-payment-confirm-api-review`

---

## 커밋 히스토리

| 커밋 | 내용 |
|---|---|
| `985cd08` | fix: PAYMENT_FAILED 에러 코드 HTTP 상태 400 → 422 |
| `102b84d` | refactor: TossConfirmResult → ConfirmResult, TossRefundResult → RefundResult |
| `132229b` | feat: TossPaymentGateway 방어 로직 추가 |
| `3c19fef` | feat: KafkaPaymentEventPublisher 결제 승인 발행 결과 로깅 추가 |
| `9b06893` | refactor: ConfirmPaymentService 트랜잭션 분리 및 상수 추출 |
| `b737574` | test: ConfirmPaymentServiceTest TransactionTemplate 적용 |
| `976363c` | test: TransactionTemplate no-op 구현 및 PAYMENT_FAILED 상태 코드 422 반영 |
| `be19230` | docs: Toss 4xx 에러 코드 분류 작업 계획 추가 |
| `2ea1700` | feat: TossPaymentGateway 서버 오류성 4xx 에러 코드 PG_ERROR 분류 처리 |

---

## 변경 파일 목록

**src/main:**
- `application/exception/PaymentErrorCode.java` — PAYMENT_FAILED 422
- `infrastructure/external/toss/TossPaymentGateway.java` — `TOSS_SERVER_ERROR_CODES` Set 추가, confirm() 4xx 핸들러 분기 (서버 오류성 → PG_ERROR)
- `application/gateway/external/ConfirmResult.java` — 신규 (구 TossConfirmResult)
- `application/gateway/external/RefundResult.java` — 신규 (구 TossRefundResult)
- `application/gateway/external/TossConfirmResult.java` — 삭제
- `application/gateway/external/TossRefundResult.java` — 삭제
- `application/gateway/external/PaymentGateway.java` — 반환 타입 변경
- `application/service/ConfirmPaymentService.java` — TX 분리, 상수 추출, 로깅
- `infrastructure/external/toss/TossPaymentGateway.java` — timeout, null 체크, 로깅
- `infrastructure/messaging/KafkaPaymentEventPublisher.java` — whenComplete 추가
- `infrastructure/messaging/RefundEventHandler.java` — RefundResult 참조 변경
- `infrastructure/scheduling/PaymentRefundRetryScheduler.java` — RefundResult 참조 변경

**src/test:**
- `application/service/ConfirmPaymentServiceTest.java` — TX no-op, findById/saveAndFlush stub, `PG_ERROR` 경로 테스트 추가
- `presentation/PaymentControllerTest.java` — PAYMENT_FAILED 기대 상태 422, PG_ERROR → 502 테스트 추가
- `ConfirmPaymentIntegrationTest.java` — ConfirmResult import 변경
- `RefundPaymentIntegrationTest.java` — ConfirmResult/RefundResult import 변경

---

## 트러블슈팅

### T1. `ResourcelessTransactionManager` 컴파일 오류

**증상**: `ConfirmPaymentServiceTest` 컴파일 실패
```
error: cannot find symbol
import org.springframework.transaction.support.ResourcelessTransactionManager;
```

**원인**: `ResourcelessTransactionManager`는 Spring Framework 구버전 테스트 유틸리티였으나 Spring Boot 4.1 (Spring Framework 7.x)에서 제거됨.

**해결**: `PlatformTransactionManager` 인터페이스를 직접 익명 구현체로 작성.
```java
TransactionTemplate transactionTemplate = new TransactionTemplate(new PlatformTransactionManager() {
    @Override
    public TransactionStatus getTransaction(TransactionDefinition def) {
        return new SimpleTransactionStatus();
    }
    @Override
    public void commit(TransactionStatus status) {}
    @Override
    public void rollback(TransactionStatus status) {}
});
```
`SimpleTransactionStatus`는 Spring TX에 여전히 포함되어 있음. 콜백은 정상 실행되고 commit/rollback은 no-op.

---

### T2. `PaymentControllerTest.PG사_결제_실패_시_400_PAY_FAILED` 실패

**증상**: 29개 테스트 중 1개 실패
```
java.lang.AssertionError at PaymentControllerTest.java:118
```

**원인**: Task 1에서 `PAYMENT_FAILED`의 HTTP 상태를 400 → 422로 변경했지만, 컨트롤러 테스트에서 `status().isBadRequest()`(400)를 기대하고 있었음.

**해결**: 테스트 메서드명과 기대 상태 코드를 함께 수정.
```java
// 변경 전
void PG사_결제_실패_시_400_PAY_FAILED()
    .andExpect(status().isBadRequest())

// 변경 후
void PG사_결제_실패_시_422_PAY_FAILED()
    .andExpect(status().isUnprocessableEntity())
```

---

## 최종 테스트 결과

```
BUILD SUCCESSFUL
31 tests completed, 0 failed
```

---

## 계획 대비 변경사항

계획 문서(`10-confirm-api-code-review.md`)와 비교한 실제 구현 차이:

| 항목 | 계획 | 실제 |
|---|---|---|
| `ResourcelessTransactionManager` | 계획에 명시 | Spring Boot 4.1 미존재 → 익명 no-op 구현체로 대체 |
| `PaymentControllerTest` 영향 | "영향 없음"으로 명시 | PAYMENT_FAILED 상태 코드 변경으로 수정 필요 발생 |
| 참조 파일 수 | 5개 파일로 명시 | `RefundEventHandler`, `PaymentRefundRetryScheduler`, 통합 테스트 2개 추가 발견 |
| Toss 4xx 분류 (#8) | 계획에 미포함 | T1 대안으로 보류된 에러 코드 분기 로직 추가 구현 |
