# Retry.ofDefaults()가 BulkheadFullException/RequestNotPermitted까지 재시도해 기존 테스트가 깨짐

**날짜**: 2026-07-23
**상태**: Resolved
**분류**: Resilience4j / Testing

## 환경

Spring Boot 4.1, Resilience4j 2.4.0 (circuitbreaker/bulkhead/ratelimiter/retry), JUnit5 + AssertJ.

## 증상

`TossPaymentGateway`의 `executeConfirm`에 `Retry` 데코레이터를 추가(`CircuitBreaker(Retry(RateLimiter(Bulkhead(호출))))`)한 뒤, 기존 `TossPaymentGatewayBulkheadTest`의 `new TossPaymentGateway(...)` 호출부에 계획 문서 지시대로 `Retry.ofDefaults("test-confirm-retry")`를 추가하자 아래 테스트가 실패했다.

```
TossPaymentGatewayBulkheadTest > confirm_동시호출이_상한을_초과하면_초과분은_즉시_PG_BUSY를_던진다() FAILED
    org.opentest4j.AssertionFailedError:
    expected: 2
     but was: 3
```

동시 호출 3건 중 Bulkhead 상한(2)을 초과한 1건이 즉시 `PG_BUSY`로 실패해야 하는데, 실제로는 그 1건도 나중에 성공해 `successCount`가 2가 아니라 3이 나왔다.

## 핵심

Resilience4j `Retry.ofDefaults()`는 별도 `retryOnException`/`retryExceptions`를 지정하지 않으면 **모든 `Throwable`을 재시도 대상으로 삼는다.** 즉 `BulkheadFullException`, `RequestNotPermitted`도 예외가 아니라 그냥 재시도 대상이 된다.

`Retry`가 `RateLimiter(Bulkhead(...))`를 감싸는 데코레이터 순서상, Bulkhead가 즉시 거절(`BulkheadFullException`)해도 `Retry`가 이를 그대로 삼켜 기본 대기시간(500ms) 후 재시도한다. 테스트에서는 그 사이 앞선 2개 호출이 `releaseLatch` 해제로 완료되어 permit이 반납되고, 재시도 시점에 3번째 호출이 permit을 획득해 결국 성공해버렸다.

이 서비스의 설계 의도(`TossRetryPredicate`)는 "Bulkhead/RateLimiter 거절은 재시도 대상에서 제외"였지만, 그 predicate는 `TossRetryConfig`가 만드는 운영용 `tossConfirmRetry` 빈에만 적용된다. 테스트 코드에서 `Retry.ofDefaults(...)`로 직접 만든 `Retry` 인스턴스는 이 predicate를 전혀 타지 않으므로 설계 의도와 다르게 동작한 것 — 프레임워크 버그가 아니라 "테스트가 프로덕션 설정을 그대로 반영하지 않아 생긴 괴리"였다.

## 조사 과정

### 1. AssertionFailedError만으로는 원인 불명

`successCount`가 예상보다 1 많다는 사실만으로는 어디서 여분의 성공이 나오는지 알 수 없었다. Bulkhead 설정 자체(`maxConcurrentCalls(2)`)는 변경하지 않았으므로 Bulkhead 로직 문제는 아니라고 판단.

### 2. 새로 추가한 유일한 변수 = Retry

이번 Task에서 유일하게 추가된 데코레이터가 Retry였으므로, Retry가 실패를 삼키고 재시도했을 가능성을 의심. `Retry.ofDefaults()`의 기본 `RetryConfig`가 어떤 예외를 재시도 대상으로 삼는지 확인이 필요했다.

### 3. resilience4j-retry 소스 확인

`RetryConfig` 기본값은 `retryExceptions`가 비어 있으면 "모든 예외 재시도"로 동작함을 확인(화이트리스트가 비어있으면 전체 허용, `ignoreExceptions`로만 예외 제외 가능한 구조). `TossRetryPredicate`를 쓰지 않는 한 Bulkhead/RateLimiter 거절도 재시도됨이 확정.

### 4. 같은 문제가 RateLimiter 테스트에도 잠재

동일한 이유로 신규 `TossPaymentGatewayRetryTest`의 `RateLimiter_거절은_재시도하지_않는다()` 테스트도 `Retry.ofDefaults()`를 썼다면 동일하게 깨졌을 것 — 이 테스트는 애초에 `retryOf(...)` 헬퍼(= `TossRetryPredicate` 적용)를 쓰도록 작성해 두어서 문제가 없었다. 이 대조를 통해 "predicate 미적용"이 원인이라는 가설을 확증했다.

## 해결

계획 문서가 지시한 `Retry.ofDefaults("test-confirm-retry")` 대신, `TossPaymentGatewayBulkheadTest` / `TossPaymentGatewayCircuitBreakerTest` / `TossPaymentGatewayRateLimiterTest` 3개 파일 모두 아래처럼 프로덕션과 동일한 predicate를 명시한 `Retry`로 교체했다.

```java
Retry.of("test-confirm-retry", RetryConfig.custom()
    .maxAttempts(2)
    .waitDuration(Duration.ofMillis(500))
    .retryOnException(new TossRetryPredicate())
    .build())
```

`TossPaymentGatewayTest`(refund 전용 테스트, confirm() 미사용)는 Retry 설정이 결과에 영향을 주지 않으므로 `Retry.ofDefaults(...)`를 그대로 유지했다.

신규 `TossPaymentGatewayRetryTest`의 `RateLimiter_거절은_재시도하지_않는다()`도 별도로 `RateLimiterConfig.limitForPeriod(0)`이 `IllegalArgumentException("LimitForPeriod should be greater than 0")`을 던져 `limitForPeriod(1)` + `acquirePermission()` 선점 방식으로 교체했다(이 부분은 단순 API 제약 확인으로 별도 문서화 대상은 아님).

## 검증

```bash
JAVA_HOME=~/.asdf/installs/java/temurin-21.0.5+11.0.LTS ../gradlew :payment-service:test --tests "com.prompthub.payment.infrastructure.external.toss.*"
JAVA_HOME=~/.asdf/installs/java/temurin-21.0.5+11.0.LTS ../gradlew :payment-service:test
```

둘 다 `BUILD SUCCESSFUL`. `TossPaymentGatewayBulkheadTest`의 `successCount`가 다시 2로 돌아오고, 신규 `TossPaymentGatewayRetryTest` 6개 테스트 전부 통과.

## 교훈 / 재발 방지

- Resilience4j `Retry`를 다른 실패성 예외(Bulkhead/RateLimiter 거절 등)와 함께 데코레이터로 조합할 때는, **테스트에서도 반드시 프로덕션과 동일한 `retryOnException` predicate를 명시**해야 한다. `Retry.ofDefaults()`는 "설정 안 함 = 재시도 안 함"이 아니라 "설정 안 함 = 전부 재시도"라는 점을 항상 의심할 것.
- 여러 Resilience4j 데코레이터(Bulkhead/RateLimiter/CircuitBreaker/Retry)를 겹쳐 쓰는 게이트웨이에 새 데코레이터를 추가할 때는, 기존 테스트의 "예외 종류별 재시도 여부" 전제가 새 데코레이터의 기본 동작으로 인해 조용히 깨질 수 있으므로 새 데코레이터 추가 직후 전체 회귀 테스트를 반드시 실행해 확인한다.
