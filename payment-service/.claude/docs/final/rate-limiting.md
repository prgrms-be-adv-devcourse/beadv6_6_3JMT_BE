# 작업 계획: confirm API 동시 요청 유량 제어

> **2026-07-05 조정 (00-execution-order.md)**: 실행 순서 **작업 5** — flow-redesign(작업 2) 이후 착수. ① 에러 코드는 `PAY008`이 circuit-breaker(`PG_UNAVAILABLE`)에 배정되어 **`PAY009`로 변경**. ② 컨트롤러 스니펫을 flow-redesign 이후의 confirm 계약(`{paymentKey, orderId}`, amount 없음) 기준으로 리베이스.

## 결정 배경 (그릴링 세션 결과)

| 항목 | 결정 |
|---|---|
| 목적 | 학습 목적 — "대량 요청이 몰릴 때 N개까지만 처리" 패턴 구현 |
| 제어 대상 | `POST /api/v1/payments/confirm` 진입점 |
| 거부 방식 | **즉시 거부 (429)** — Toss SDK 타임아웃 이전에 결과를 내려야 하므로 대기열 불가 |
| 구현 방식 | JVM 내부 `Semaphore` — Redis 없이 단일 인스턴스에서 동시 처리 수 제어 |
| 임계값 | `application.yaml` 외부화 (`payment.rate-limit.max-concurrent`) |
| gRPC 폴백 thundering herd | **이번 범위 제외** — `OrderGrpcClientAdapter`가 아직 미구현. order-payment-flow-redesign 완료 후 후속 작업 |

### 즉시 거부를 선택한 이유

대기열(Queue) 방식은 클라이언트가 HTTP 연결을 유지한 채 대기하는 구조다. Toss SDK는 결제창에서 confirm 응답을 기다리는 내부 타임아웃이 있어, 대기열에서 순서를 기다리다 SDK 타임아웃이 먼저 터지면 사용자는 이유 없이 결제 실패를 경험한다. 429로 즉시 거부하면 클라이언트가 "지금 혼잡함"을 명확히 알고 재시도를 선택할 수 있다.

---

## 변경 파일 목록

| # | 파일 | 변경 유형 |
|---|---|---|
| 1 | `application.yaml` | 설정 추가 |
| 2 | `application/exception/PaymentErrorCode.java` | 에러 코드 추가 |
| 3 | `presentation/PaymentRateLimiter.java` | 신규 컴포넌트 |
| 4 | `presentation/PaymentController.java` | confirm 메서드에 rate limit 적용 |
| 5 | 통합 테스트 | 동시 요청 검증 |

---

## 구현 상세

### 1. `application.yaml` — 설정 추가

기존 `payment.toss` 블록 아래에 추가한다.

```yaml
payment:
  toss:
    secret-key: ${TOSS_SECRET_KEY:test-dummy-key}
    base-url: https://api.tosspayments.com/v1
    test-mode: ${TOSS_TEST_MODE:false}
  rate-limit:
    max-concurrent: 300
```

---

### 2. `PaymentErrorCode.java` — `TOO_MANY_REQUESTS` 추가

```java
TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "PAY009", "처리 가능한 요청 수를 초과했습니다. 잠시 후 다시 시도해주세요.")
```

> `PAY008`은 circuit-breaker(작업 4)의 `PG_UNAVAILABLE`이 사용한다.

기존 열거값 마지막에 추가한다. `PaymentExceptionHandler`는 `BusinessException`을 `e.getErrorCode().getStatus()`로 HTTP 상태를 결정하므로 별도 핸들러 추가 없이 자동으로 429를 반환한다.

---

### 3. `presentation/PaymentRateLimiter.java` — 신규

```java
package com.prompthub.paymentservice.presentation;

import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PaymentRateLimiter {

    private final Semaphore semaphore;

    public PaymentRateLimiter(
        @Value("${payment.rate-limit.max-concurrent}") int maxConcurrent
    ) {
        this.semaphore = new Semaphore(maxConcurrent);
    }

    public boolean tryAcquire() {
        return semaphore.tryAcquire();
    }

    public void release() {
        semaphore.release();
    }
}
```

- `Semaphore(N)` — 기본값 비공정(non-fair). 처리량 우선. 공정성이 필요하면 `new Semaphore(N, true)`.
- `tryAcquire()` — 블로킹 없이 즉시 반환. 슬롯이 없으면 `false`.
- `presentation` 패키지에 위치 — HTTP 입력 계층의 관심사이므로 Controller와 같은 레이어.

---

### 4. `PaymentController.java` — confirm 메서드 변경

> 스니펫은 flow-redesign(작업 2) 이후의 confirm 계약 기준 — `ConfirmPaymentRequest`/`ConfirmPaymentCommand`에 `amount` 없음.

**변경 전**
```java
@PostMapping("/confirm")
public ResponseEntity<ApiResult<ConfirmPaymentResponse>> confirm(
    @RequestHeader("X-User-Id") UUID userId,
    @RequestHeader("X-User-Role") String userRoles,
    @Valid @RequestBody ConfirmPaymentRequest request
) {
    if (Arrays.stream(userRoles.split(",")).noneMatch("BUYER"::equals)) {
        throw new BusinessException(PaymentErrorCode.INSUFFICIENT_ROLE);
    }
    ConfirmPaymentCommand command = new ConfirmPaymentCommand(
        request.paymentKey(), request.orderId(), userId
    );
    PaymentResult result = confirmPaymentUseCase.confirm(command);
    return ResponseEntity.ok(ApiResult.success(new ConfirmPaymentResponse(result.paymentId())));
}
```

**변경 후**
```java
@PostMapping("/confirm")
public ResponseEntity<ApiResult<ConfirmPaymentResponse>> confirm(
    @RequestHeader("X-User-Id") UUID userId,
    @RequestHeader("X-User-Role") String userRoles,
    @Valid @RequestBody ConfirmPaymentRequest request
) {
    if (Arrays.stream(userRoles.split(",")).noneMatch("BUYER"::equals)) {
        throw new BusinessException(PaymentErrorCode.INSUFFICIENT_ROLE);
    }
    if (!rateLimiter.tryAcquire()) {
        throw new BusinessException(PaymentErrorCode.TOO_MANY_REQUESTS);
    }
    try {
        ConfirmPaymentCommand command = new ConfirmPaymentCommand(
            request.paymentKey(), request.orderId(), userId
        );
        PaymentResult result = confirmPaymentUseCase.confirm(command);
        return ResponseEntity.ok(ApiResult.success(new ConfirmPaymentResponse(result.paymentId())));
    } finally {
        rateLimiter.release();
    }
}
```

- **역할 검증 → rate limit 순서**: BUYER가 아닌 요청은 Semaphore 슬롯을 소비하지 않는다. 유효하지 않은 요청이 슬롯을 낭비하는 것을 방지.
- `finally`에서 `release()` — 예외 발생(`BusinessException`, `RuntimeException` 등) 여부와 무관하게 슬롯을 반드시 반환.

---

## 테스트 확인 포인트

`ConfirmPaymentIntegrationTest` 또는 `PaymentControllerTest`에 추가한다.

| 시나리오 | 검증 |
|---|---|
| max-concurrent 이하 동시 요청 | 모두 정상 처리 (200) |
| max-concurrent 초과 동시 요청 | 초과분 429 반환, 코드 `PAY009` |
| 처리 완료 후 다음 요청 | 슬롯 반환 확인 — 거부됐던 요청이 재시도 시 정상 처리 |
| 예외 발생 시 슬롯 반환 | PG 실패(`PaymentGatewayException`) 후에도 슬롯이 소모된 채 남지 않음 |

**동시 요청 재현 예시**:
```java
int limit = 3; // 테스트에서는 낮은 값으로 설정 (application.yaml 오버라이드)
int total = 5;
ExecutorService pool = Executors.newFixedThreadPool(total);
CountDownLatch ready = new CountDownLatch(total);
CountDownLatch start = new CountDownLatch(1);

// total개 스레드가 동시에 출발 → limit 초과분은 429
```

---

## 인지하고 가야 할 트레이드오프

| 항목 | 내용 |
|---|---|
| 단일 인스턴스 전제 | 인스턴스 2개 이상이면 각각 독립 Semaphore → 실제 동시 처리 수 = N × max-concurrent. 분산 제어가 필요하면 Redis 기반 전환 필요 |
| refund 엔드포인트 미적용 | 환불은 `@TransactionalEventListener` 비동기 처리 구조라 동시 요청 제어가 필요한 성격이 다름. 이번 범위 제외 |
| 임계값 근거 없음 | 300은 학습 목적의 임의값. 실 운영 시 Toss API 계약, DB 커넥션 풀 크기, 서버 스펙을 기준으로 부하 테스트 후 결정 필요 |

## 후속 과제

- **gRPC 폴백 thundering herd 대응**: `order-payment-flow-redesign.md` 구현 완료 후, `OrderGrpcClientAdapter`에 별도 동시 호출 수 제한 추가 (confirm 진입 제한과는 별도 제어 지점)
- **분산 환경 전환**: 인스턴스 다중화 시 Redis `SET NX` + TTL 기반 분산 카운터로 교체
