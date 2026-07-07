# 작업 계획: 서킷 브레이커 (Toss PG 장애 대응)

> **2026-07-05 조정 (00-execution-order.md D3·D5)**: 실행 순서 **작업 4** — partial-refund-api(작업 3) 완료 후 착수. 환불 파트는 원안(구모델: `RefundEventHandler` + Payment REFUNDING 기준)이 아니라 **신 환불 모델**(order-events 환불 컨슈머 + `Refund.REQUESTED` stale 스케줄러) 기준으로 아래와 같이 재작성됨. confirm 파트는 flow-redesign(작업 2) 이후의 confirm 코드에 적용하며 구조는 원안 그대로 유효. `PG_UNAVAILABLE` = `PAY008` 확정 (rate-limiting의 `TOO_MANY_REQUESTS`는 `PAY009`).

## 결정 배경

| 항목 | 결정 |
|---|---|
| 적용 대상 | Toss Payments API 호출 전체 (`confirm`, `refund`) |
| 서킷 구성 | 오퍼레이션별 독립 서킷 (`toss-confirm` / `toss-refund`) |
| 실패 카운트 기준 | `PG_ERROR` (Toss 5xx)만 카운트 — `PAYMENT_FAILED` (4xx)는 비즈니스 오류이므로 제외 |
| OPEN 시 confirm | 즉시 `PG_UNAVAILABLE`(503) 반환 — "PG사 서비스가 일시적으로 이용 불가합니다" |
| OPEN 시 refund (환불 컨슈머) | 트랜잭션 롤백으로 **Refund REQUESTED 유지** (Payment는 PAID 유지 — 신모델은 REFUNDING 미경유), 재시도 스케줄러가 복구 후 픽업. `payment.refund-failed` 미발행(일시 장애는 실패 확정이 아님 — OrderProduct REFUND_REQUESTED 유지) |
| OPEN 시 refund (Scheduler) | 해당 건 로그 후 skip, 다음 건 계속 (Refund REQUESTED 유지 → 다음 실행에서 재시도) |
| 인스턴스 | 단일 → 인메모리 Resilience4j (Redis 불필요) |
| 라이브러리 | `spring-cloud-starter-circuitbreaker-resilience4j` (Spring Cloud BOM 기존 사용) |
| 아키텍처 경계 | `CallNotPermittedException`은 infrastructure 내부에서 `PaymentGatewayUnavailableException`으로 변환 — application 레이어에 Resilience4j 의존성 미노출 |

---

## 임계값

| 항목 | `toss-confirm` | `toss-refund` |
|---|---|---|
| 슬라이딩 윈도우 | COUNT_BASED, 10건 | COUNT_BASED, 5건 |
| 실패율 임계값 | 50% | 50% |
| OPEN 대기 시간 | 30초 | 30초 |
| HALF-OPEN 허용 호출 | 3건 | 2건 |

> `confirm`은 신규 결제 창구이므로 샘플이 빠르게 쌓인다. `refund`는 빈도가 낮으므로 윈도우를 작게 잡아 감지를 빠르게 한다.

---

## 신규 파일 3개

### 1. `application/gateway/external/PaymentGatewayUnavailableException.java`

아키텍처 경계를 지키기 위해 `CallNotPermittedException`을 application 레이어까지 노출하지 않는다.
`TossPaymentGateway`가 Resilience4j 예외를 이 타입으로 변환하며, application 레이어는 이 타입만 처리한다.

```java
package com.prompthub.paymentservice.application.gateway.external;

public class PaymentGatewayUnavailableException extends RuntimeException {
    public PaymentGatewayUnavailableException() {
        super("PG 서킷 OPEN");
    }
}
```

---

### 2. `infrastructure/external/toss/TossCircuitBreakerConfig.java`

```java
package com.prompthub.paymentservice.infrastructure.external.toss;

import com.prompthub.paymentservice.application.exception.PaymentErrorCode;
import com.prompthub.paymentservice.application.gateway.external.PaymentGatewayException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TossCircuitBreakerConfig {

    private static final java.util.function.Predicate<Throwable> PG_5XX_ONLY =
        e -> e instanceof PaymentGatewayException pge
            && pge.getErrorCode() == PaymentErrorCode.PG_ERROR;

    @Bean("tossConfirmCircuit")
    public CircuitBreaker tossConfirmCircuit() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
            .recordFailurePredicate(PG_5XX_ONLY)
            .slidingWindowType(SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(10)
            .failureRateThreshold(50f)
            .waitDurationInOpenState(Duration.ofSeconds(30))
            .permittedNumberOfCallsInHalfOpenState(3)
            .build();
        return CircuitBreakerRegistry.of(config).circuitBreaker("toss-confirm");
    }

    @Bean("tossRefundCircuit")
    public CircuitBreaker tossRefundCircuit() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
            .recordFailurePredicate(PG_5XX_ONLY)
            .slidingWindowType(SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(5)
            .failureRateThreshold(50f)
            .waitDurationInOpenState(Duration.ofSeconds(30))
            .permittedNumberOfCallsInHalfOpenState(2)
            .build();
        return CircuitBreakerRegistry.of(config).circuitBreaker("toss-refund");
    }
}
```

---

### 3. `infrastructure/external/toss/TossCircuitBreakerTest.java` (테스트)

```java
package com.prompthub.paymentservice.infrastructure.external.toss;

import com.prompthub.paymentservice.application.exception.PaymentErrorCode;
import com.prompthub.paymentservice.application.gateway.external.PaymentGatewayException;
import com.prompthub.paymentservice.application.gateway.external.PaymentGatewayUnavailableException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreaker.State;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class TossCircuitBreakerTest {

    @Autowired
    @Qualifier("tossConfirmCircuit")
    CircuitBreaker tossConfirmCircuit;

    @Test
    void PG_5xx_오류가_임계값_초과하면_서킷이_열린다() {
        // PG_ERROR(5xx) 5건 기록 → 10건 윈도우에서 50% 도달
        for (int i = 0; i < 5; i++) {
            tossConfirmCircuit.onError(0, java.util.concurrent.TimeUnit.MILLISECONDS,
                new PaymentGatewayException(PaymentErrorCode.PG_ERROR, "SERVER_ERROR", "서버 오류", null, null));
        }
        for (int i = 0; i < 5; i++) {
            tossConfirmCircuit.onSuccess(0, java.util.concurrent.TimeUnit.MILLISECONDS);
        }

        assertThat(tossConfirmCircuit.getState()).isEqualTo(State.OPEN);
    }

    @Test
    void PG_4xx_오류는_서킷을_열지_않는다() {
        for (int i = 0; i < 10; i++) {
            tossConfirmCircuit.onError(0, java.util.concurrent.TimeUnit.MILLISECONDS,
                new PaymentGatewayException(PaymentErrorCode.PAYMENT_FAILED, "INVALID", "결제 실패", null, null));
        }

        assertThat(tossConfirmCircuit.getState()).isEqualTo(State.CLOSED);
    }
}
```

> 서킷 상태가 테스트 간 공유되지 않도록 `@DirtiesContext` 또는 `@BeforeEach tossConfirmCircuit.reset()`으로 리셋.

---

## 변경 파일 6개

### 4. `build.gradle`

**변경 포인트**: Resilience4j 의존성 추가.

```groovy
// 변경 전
// (없음)

// 변경 후 — dependencies 블록 내
implementation 'org.springframework.cloud:spring-cloud-starter-circuitbreaker-resilience4j'
```

Spring Cloud BOM(`2025.1.2`)이 이미 선언되어 있으므로 버전 명시 불필요.

---

### 5. `application/exception/PaymentErrorCode.java`

**변경 포인트**: `PG_UNAVAILABLE` 에러 코드 추가.

```java
// 변경 전
INSUFFICIENT_ROLE(HttpStatus.FORBIDDEN, "PAY007", "결제/환불 권한이 없습니다.");

// 변경 후
INSUFFICIENT_ROLE(HttpStatus.FORBIDDEN, "PAY007", "결제/환불 권한이 없습니다."),
PG_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "PAY008", "PG사 서비스가 일시적으로 이용 불가합니다.");
```

---

### 6. `infrastructure/external/toss/TossPaymentGateway.java`

**변경 포인트 1**: 생성자에 두 서킷 빈 주입 추가.

```java
// 변경 전
public TossPaymentGateway(
    @Value("${payment.toss.secret-key}") String secretKey,
    @Value("${payment.toss.base-url:https://api.tosspayments.com/v1}") String baseUrl,
    ObjectMapper objectMapper
) { ... }

// 변경 후
private final CircuitBreaker tossConfirmCircuit;
private final CircuitBreaker tossRefundCircuit;

public TossPaymentGateway(
    @Value("${payment.toss.secret-key}") String secretKey,
    @Value("${payment.toss.base-url:https://api.tosspayments.com/v1}") String baseUrl,
    ObjectMapper objectMapper,
    @Qualifier("tossConfirmCircuit") CircuitBreaker tossConfirmCircuit,
    @Qualifier("tossRefundCircuit") CircuitBreaker tossRefundCircuit
) {
    // ... 기존 restClient 초기화 ...
    this.tossConfirmCircuit = tossConfirmCircuit;
    this.tossRefundCircuit = tossRefundCircuit;
}
```

**변경 포인트 2**: `confirm()` — 서킷 래핑 + `CallNotPermittedException` 변환.

```java
// 변경 전
@Override
public TossConfirmResult confirm(String paymentKey, UUID orderId, int amount) {
    TossConfirmRequest request = ...;
    TossConfirmResponse response = restClient.post()... .body(TossConfirmResponse.class);
    return new TossConfirmResult(...);
}

// 변경 후
@Override
public TossConfirmResult confirm(String paymentKey, UUID orderId, int amount) {
    try {
        return tossConfirmCircuit.executeSupplier(() -> doConfirm(paymentKey, orderId, amount));
    } catch (io.github.resilience4j.circuitbreaker.CallNotPermittedException e) {
        throw new PaymentGatewayUnavailableException();
    }
}

private TossConfirmResult doConfirm(String paymentKey, UUID orderId, int amount) {
    // 기존 confirm() 본문 그대로 이동
}
```

**변경 포인트 3**: `refund()` — 동일 패턴 적용.

```java
// 변경 전
@Override
public TossRefundResult refund(String pgTxId, UUID paymentId, int amount) {
    TossRefundRequest request = ...;
    TossRefundResponse response = restClient.post()... .body(TossRefundResponse.class);
    return new TossRefundResult(...);
}

// 변경 후
@Override
public TossRefundResult refund(String pgTxId, UUID paymentId, int amount) {
    try {
        return tossRefundCircuit.executeSupplier(() -> doRefund(pgTxId, paymentId, amount));
    } catch (io.github.resilience4j.circuitbreaker.CallNotPermittedException e) {
        throw new PaymentGatewayUnavailableException();
    }
}

private TossRefundResult doRefund(String pgTxId, UUID paymentId, int amount) {
    // 기존 refund() 본문 그대로 이동
}
```

---

### 7. `application/service/ConfirmPaymentService.java`

**변경 포인트**: `paymentGateway.confirm()` 호출부에 `PaymentGatewayUnavailableException` catch 추가.

```java
// 변경 전
} catch (PaymentGatewayException e) {
    payment.fail(e.getFailureCode(), e.getFailureReason(),
        e.getRequestPayload(), e.getResponsePayload(), OffsetDateTime.now());
    paymentRepository.save(payment);
    throw new BusinessException(e.getErrorCode(), e.getFailureReason());
}

// 변경 후
} catch (PaymentGatewayException e) {
    payment.fail(e.getFailureCode(), e.getFailureReason(),
        e.getRequestPayload(), e.getResponsePayload(), OffsetDateTime.now());
    paymentRepository.save(payment);
    throw new BusinessException(e.getErrorCode(), e.getFailureReason());
} catch (PaymentGatewayUnavailableException e) {
    payment.fail("CIRCUIT_OPEN", "PG사 일시 점검 중", null, null, OffsetDateTime.now());
    paymentRepository.save(payment);
    throw new BusinessException(PaymentErrorCode.PG_UNAVAILABLE);
}
```

> Payment는 `FAILED` 상태로 저장된다. 서킷 OPEN은 일시적이므로 사용자가 PG 복구 후 재결제해야 한다.
> flow-redesign(작업 2)에 payment.failed 발행이 흡수되어 선행되므로(D5) 이 경로도 `PaymentFailedEvent`를 발행한다 — 별도 처리 불필요.
> 이 이벤트로 Order가 FAILED 전이되어도 D1 정책(FAILED → PAID 복귀 허용) 덕분에 재결제로 복귀 가능하다.
> 또한 flow-redesign의 중복 판정(FAILED 제외)으로 payment 측 재결제도 열려 있다.
> (위 변경 전/후 스니펫은 개념 기준 — 실제 적용 대상은 작업 2를 거친 confirm 코드)

---

### 8. 환불 실행 흐름 (신모델 — partial-refund-api 작업 3 이후 기준)

> 원안은 구모델의 `RefundEventHandler`(`Payment REFUNDING 유지 + setRollbackOnly`)를 수정하는 계획이었으나,
> 작업 3에서 환불 실행이 **order-events 환불 컨슈머(`ORDER_REFUND_REQUESTED` 처리)** 흐름으로 재편되므로
> 그 최종 코드의 PG 호출 지점에 적용한다.

**변경 포인트**: 환불 실행 트랜잭션의 catch 분기에 `PaymentGatewayUnavailableException` 추가.

- `PaymentGatewayException` (기존): Refund FAILED 확정 + `payment.refund-failed` 발행 (partial-refund 설계 그대로).
- `PaymentGatewayUnavailableException` (신규): **트랜잭션 롤백** — Refund는 REQUESTED, Payment는 PAID 유지.
  `payment.refund-failed`를 **발행하지 않는다** — 일시 장애는 실패 확정이 아니므로 OrderProduct는
  REFUND_REQUESTED 상태로 스케줄러 재시도 결과를 기다린다.
- Kafka 컨슈머는 해당 메시지를 ack한다(재소비 아님 — 재시도 책임은 스케줄러로 일원화).

```java
} catch (PaymentGatewayUnavailableException e) {
    log.warn("Toss 환불 서킷 OPEN — paymentId={}, refundId={}, Refund REQUESTED 유지하여 스케줄러 재시도 대기",
        payment.getId(), refund.getId());
    status.setRollbackOnly();
}
```

> 스케줄러(신모델: `Refund.status = REQUESTED AND stale 30분`)가 해당 Refund를 픽업해 재시도한다.
> 환불 요청 API는 이미 202를 반환했으므로 사용자 흐름에는 영향 없다(REFUND_REQUESTED = "환불 처리 중" 표시 지속).

---

### 9. `infrastructure/scheduling/PaymentRefundRetryScheduler.java` (신모델 기준)

> 작업 3 이후 스케줄러의 조회 기준은 `Refund.status = REQUESTED AND updated_at < NOW() - 30분`이다
> (구모델의 `Payment.REFUNDING` 조회 아님).

**변경 포인트**: `PaymentGatewayException` catch 뒤에 `PaymentGatewayUnavailableException` catch 추가.

```java
} catch (PaymentGatewayException e) {
    // (partial-refund 설계 그대로) Refund FAILED 확정 + afterCommit()에서 payment.refund-failed 발행
} catch (PaymentGatewayUnavailableException e) {
    log.warn("Toss 환불 서킷 OPEN — refundId={}, 이번 스케줄러 실행에서 건너뜀", refund.getId());
    // 상태 변경 없음 — Refund REQUESTED 유지, 다음 스케줄러 실행 시 재시도
} catch (InvalidRefundStateException e) {
    ...
}
```

> Toss 멱등성 키(`refund-{paymentId}-{orderProductId}`)가 결정적이므로 반복 재시도는 안전하다.

---

## 전체 동작 흐름 요약

### 결제 승인 (`confirm`) — 서킷 OPEN 시

```
HTTP POST /payments/confirm
  → ConfirmPaymentService.confirm()
  → paymentGateway.confirm()
      → TossPaymentGateway: tossConfirmCircuit OPEN → CallNotPermittedException
      → PaymentGatewayUnavailableException으로 변환
  → ConfirmPaymentService: payment.fail("CIRCUIT_OPEN", ...) → FAILED로 저장
  → BusinessException(PG_UNAVAILABLE) → HTTP 503
```

### 환불 처리 (`refund`) — 서킷 OPEN 시 (신모델)

```
order-service: 환불 요청 API → OrderProduct REFUND_REQUESTED → outbox ORDER_REFUND_REQUESTED → 202

payment-service: order-events 환불 컨슈머
  → Refund 생성(REQUESTED) → paymentGateway.refund()
      → TossPaymentGateway: tossRefundCircuit OPEN → PaymentGatewayUnavailableException
  → 트랜잭션 롤백 → Refund REQUESTED · Payment PAID 유지, refund-failed 미발행 (ack)

[30분 후]
PaymentRefundRetryScheduler (Refund.REQUESTED stale 조회)
  → paymentGateway.refund()
      → 서킷 CLOSED/HALF-OPEN이면 정상 재시도 → 결과 이벤트 발행 (refunded / refund-failed)
      → 여전히 OPEN이면 log + skip (REQUESTED 유지)
```

> **트레이드오프**: 롤백 시 Refund 행도 사라지므로 스케줄러 픽업 대상이 없어지는 구현이 되지 않도록,
> 컨슈머에서 "Refund 저장 커밋"과 "PG 호출 트랜잭션"의 경계를 분리해야 한다(Refund 생성 커밋 후 PG 호출).
> 구현 시 partial-refund의 컨슈머 트랜잭션 구조에 맞춰 확정한다.

### 서킷 상태 전이

```
CLOSED → (PG_ERROR 5xx 실패율 ≥ 50%) → OPEN
OPEN   → (30초 대기 후)              → HALF-OPEN
HALF-OPEN → (허용 호출 성공)         → CLOSED
HALF-OPEN → (허용 호출 실패)         → OPEN
```

---

## 테스트

| 테스트 | 검증 항목 |
|---|---|
| `TossCircuitBreakerTest` (단위) | PG_ERROR 5회 → 서킷 OPEN; PG_4xx 10회 → 서킷 CLOSED 유지 |
| `ConfirmPaymentIntegrationTest` (통합) | `tossConfirmCircuit.transitionToOpenState()` 강제 → POST /payments/confirm → HTTP 503, errorCode = `PAY008` |

> 통합 테스트에서 서킷을 임계값으로 열려면 Toss stub을 5xx로 5회 이상 응답하게 하거나,
> `CircuitBreaker.transitionToOpenState()`를 직접 호출하는 방식이 더 안정적이다.

---

## 의존성 규칙 준수 확인

| 레이어 | Resilience4j 의존 여부 |
|---|---|
| `domain` | 없음 |
| `application` | 없음 (`PaymentGatewayUnavailableException`은 application.gateway.external에 위치하며 framework 의존 없음) |
| `infrastructure` | `TossPaymentGateway`, `TossCircuitBreakerConfig`에서만 사용 |

---

## 작업 순서 권장

1. `build.gradle` 의존성 추가
2. `PaymentErrorCode.PG_UNAVAILABLE` 추가
3. `PaymentGatewayUnavailableException` 신규 생성
4. `TossCircuitBreakerConfig` 신규 생성
5. `TossPaymentGateway` 수정 (서킷 래핑 + 예외 변환)
6. `ConfirmPaymentService` 수정 (catch 추가)
7. order-events 환불 컨슈머 흐름 수정 (catch 추가 — 신모델 기준, 위 §8)
8. `PaymentRefundRetryScheduler` 수정 (catch 추가 — 신모델 기준, 위 §9)
9. 테스트 작성 및 실행
