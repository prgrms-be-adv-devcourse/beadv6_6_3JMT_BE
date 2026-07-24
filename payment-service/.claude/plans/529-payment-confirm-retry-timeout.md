# 결제 승인 Retry/Timeout 보강 구현 계획

`TossPaymentGateway.confirm()`에 Resilience4j Retry를 추가해 일시적 연결 장애/PG 5xx를 흡수하고, 그 과정에서 CircuitBreaker가 필요 이상으로 쉽게 OPEN되지 않게 만든다. 사용자가 체감하는 결제 실패를 줄이는 것이 목적이다.

---

## 배경 및 목표

이슈 #356(CircuitBreaker), #490(Bulkhead), #491(RateLimiter, PR #519)을 거치며 confirm() 호출은 이미 `CircuitBreaker → RateLimiter → Bulkhead` 3중 보호를 갖췄다(`origin/develop` 기준). 그러나 재시도(Retry) 로직은 없다 — Toss 서버 순간 오류(5xx)나 연결 레벨 실패(연결 거부·리셋)가 나면 곧바로 사용자에게 실패를 반환하고, 그 실패는 `TossFailurePredicate`를 거쳐 CircuitBreaker 슬라이딩 윈도우에도 그대로 기록된다. 순간적인 blip 하나하나가 전부 "확정 실패"로 사용자에게 전달되고, 동시에 CB 실패율 계산에도 누적돼 실제로는 지나가는 흔들림일 뿐인데 CB가 필요 이상으로 쉽게 OPEN될 수 있다.

이번 작업 목표는 두 가지다.

1. 사용자가 체감하는 실패를 줄인다 — 순간적인 연결 실패/5xx는 재시도로 흡수해 성공 응답을 돌려준다.
2. CircuitBreaker가 그 과정에서 더 쉽게 열리지 않게 한다 — 재시도 전체를 CB 관점에서 결과 1건으로만 기록되게 만든다.

## 범위

**confirm()만 대상으로 한다. refund()는 제외한다.** refund는 `infrastructure.messaging.consumer.OrderEventConsumer`(`@KafkaListener`, concurrency 기본값 1)가 유일한 진입점이라 HTTP 요청-응답처럼 사용자가 실시간으로 실패를 체감하는 경로가 아니고, 재시도 도입 시 컨슈머 재처리(`DefaultErrorHandler` + `FixedBackOff`)와 겹치는 부분을 별도로 정리해야 해서 이번 범위와 섞지 않는다.

readTimeout(60초) 값 자체의 변경도 범위에서 제외한다 — 아래 확정 사항 1번 참고.

## 확정 사항

1. **readTimeout 60초는 그대로 둔다.** 토스 공식 문서(`docs.tosspayments.com/resources/glossary/timeout`)가 "결제 처리와 관련된 API의 Read Timeout은 60초로 설정하면 돼요"라고 명시한다. 이전 커밋(#356, "readTimeout을 Toss 공식 권장(60초)에 맞춰 10초에서 변경")의 근거를 이번에 실제 문서로 재검증했다. 60초를 줄이면 실제로 처리 중인 정상 승인을 우리 쪽이 먼저 포기하는 꼴이 되어 오히려 실패 체감이 늘어난다.

2. **순수 타임아웃(readTimeout 60초 소진, `SocketTimeoutException`)은 재시도 대상에서 제외한다.** 이미 60초를 기다린 뒤라 또 재시도하면 최악의 경우 사용자가 120초를 기다려야 하는데, 이는 "사용자 체감 실패 줄이기"라는 목표와 정면으로 상충한다. 재시도는 연결 레벨 실패(`ResourceAccessException`이면서 원인이 `SocketTimeoutException`이 아닌 경우 — 연결 거부·리셋·DNS 실패 등)와 `PaymentGatewayException(PG_SERVER_ERROR)`(5xx, 보통 즉시 응답)만 대상으로 한다. 이 둘은 보통 순간적인 blip이라 짧은 대기 후 재시도하면 성공할 가능성이 높고, 실패하더라도 지연이 크지 않다.

3. **confirm() 요청에 `Idempotency-Key` 헤더(`"confirm-" + paymentKey`)를 추가한다.** 타임아웃 계열 실패는 본질적으로 애매하다 — 우리 쪽 소켓은 실패로 판단했지만 Toss는 이미 승인을 완료했을 수 있다. 이 상태에서 헤더 없이 confirm()을 재시도하면 Toss가 `ALREADY_PROCESSED_PAYMENT`(4xx)를 반환하고, 현재 코드는 이를 `PG_INVALID_REQUEST`로 매핑해 결제를 FAILED 처리한다 — 실제로는 Toss가 승인을 완료해 돈이 나갔는데 우리 시스템은 실패로 기록하는 장부 불일치가 생긴다. 토스 멱등키 문서(`/reference/using-api/authorization`)는 "같은 요청이 두 번 일어나도 실제로 요청이 이루어지지 않고 첫 번째 요청 응답과 같은 응답을 보내"준다고 명시한다. 이번에 재시도 대상에서 순수 타임아웃은 제외했으므로(2번) 이 애매성이 재시도 경로에서 직접 발생하지는 않지만, `refund()`가 이미 같은 패턴(`"refund-" + refundId`)을 쓰고 있어 일관성 차원에서, 그리고 향후 어떤 경로로든 같은 paymentKey로 confirm이 중복 호출될 가능성에 대비해 헤더를 추가해 둔다.

4. **재시도 횟수는 최대 2회(원 시도 + 1회 재시도), 고정 500ms 대기로 한다.** 재시도 대상을 연결 레벨 실패/5xx로 좁혔으므로(2번) 대부분 순간적인 blip이라 1회 추가 시도로 충분히 회복된다. 재시도를 늘리면 진짜 장애(순간 blip이 아닌 지속 장애)일 때 지연만 늘리고 의미가 없으며, Bulkhead(동시 20건) 슬롯을 더 오래 점유해 재시도 자체가 상황을 악화시키는 안티패턴(retry storm) 위험이 커진다. 지수 백오프는 채택하지 않았다 — 재시도가 1회뿐이라 지수 곡선이 의미를 가지려면 최소 3회차부터 값이 갈라지는데 지금 구조에선 고정과 사실상 동일하다. 고정 500ms는 connectTimeout(5초) 대비 작아 전체 지연에 큰 영향을 주지 않으면서 PG 쪽 순간 재기동·헬스체크 전환 시간을 벌어주기엔 충분하다.

5. **데코레이터 순서는 `CircuitBreaker(Retry(RateLimiter(Bulkhead(호출))))`로 결합한다.** CircuitBreaker를 최외곽에 두는 것은 기존 결합(#356, #490)과 같은 이유다 — OPEN 상태면 안쪽 로직 자체가 호출되지 않아 자원을 아낀다. **Retry를 CircuitBreaker 바로 안쪽에 둔 것이 이번 작업의 핵심 결정이다** — 이러면 재시도 전체(원 시도 + 재시도)가 CircuitBreaker 관점에서는 "결과 1건"으로만 기록된다. 반대로 Retry를 CircuitBreaker 바깥에 두면(CB가 Retry 안쪽에 있으면) 재시도의 각 attempt가 CB 슬라이딩 윈도우에 개별로 기록돼 오히려 실패율 계산에 더 많이 잡히고 CB가 더 쉽게 열린다 — "CB가 쉽게 OPEN되지 않게"라는 목표와 정반대 결과가 나온다. RateLimiter/Bulkhead는 Retry 안쪽에 둔다 — 재시도도 실제로 Toss에 다시 나가는 HTTP 호출이므로 유량 제한과 동시성 제한을 attempt마다 다시 적용받아야 맞다. 재시도를 유량·동시성 체크 바깥에 두면 그 두 장치를 우회하는 셈이 된다.

6. **`RequestNotPermitted`(RateLimiter 거절)와 `BulkheadFullException`은 재시도 대상에서 제외한다.** 둘 다 Toss 장애가 아니라 우리 쪽이 스스로 건 상한에 걸린 것이라, 즉시 재시도해도 상황이 바뀌지 않고 오히려 이미 포화된 자원에 요청을 한 번 더 얹는 셈이라 무의미하다.

## 컴포넌트 구조

`infrastructure/external/toss` 패키지에 `TossRetryPredicate`를 신규 추가한다. `TossFailurePredicate`(CB의 "실패로 셀지" 판단)와는 기준이 달라 별도 클래스로 분리한다 — Retry는 "다시 시도해볼지" 판단이고, 순수 타임아웃처럼 CB엔 실패로 세지만 Retry는 하지 않는 케이스가 있다.

```java
public class TossRetryPredicate implements Predicate<Throwable> {

    private static final Set<PaymentErrorCode> RETRYABLE_CODES = Set.of(
        PaymentErrorCode.PG_SERVER_ERROR
    );

    @Override
    public boolean test(Throwable throwable) {
        if (throwable instanceof ResourceAccessException exception) {
            return !(exception.getCause() instanceof SocketTimeoutException);
        }
        return throwable instanceof PaymentGatewayException exception
            && RETRYABLE_CODES.contains(exception.getErrorCode());
    }
}
```

`TossRetryConfig`를 `TossCircuitBreakerConfig`/`TossBulkheadConfig`/`TossRateLimiterConfig`와 동일한 패턴으로 추가한다.

```java
@Configuration
public class TossRetryConfig {

    @Bean
    public RetryRegistry tossRetryRegistry(
        @Value("${resilience4j.retry.instances.toss-confirm-retry.max-attempts}") int maxAttempts,
        @Value("${resilience4j.retry.instances.toss-confirm-retry.wait-duration}") Duration waitDuration
    ) {
        RetryConfig retryConfig = RetryConfig.custom()
            .maxAttempts(maxAttempts)
            .waitDuration(waitDuration)
            .retryOnException(new TossRetryPredicate())
            .build();
        return RetryRegistry.of(retryConfig);
    }

    @Bean("tossConfirmRetry")
    public Retry tossConfirmRetry(RetryRegistry tossRetryRegistry) {
        return tossRetryRegistry.retry("tossConfirmRetry");
    }

    @Bean
    public MeterBinder tossRetryMetrics(RetryRegistry tossRetryRegistry) {
        return TaggedRetryMetrics.ofRetryRegistry(tossRetryRegistry);
    }
}
```

`TossPaymentGateway`는 생성자에 `Retry confirmRetry`를 추가로 주입받는다(`confirmRateLimiter` 다음 파라미터). `confirm()` 요청에 `Idempotency-Key` 헤더를 추가하고, `executeConfirm`의 데코레이터 순서를 변경한다.

```java
@Override
public ConfirmResult confirm(String paymentKey, UUID orderId, int amount) {
    return executeConfirm(() -> {
        TossConfirmRequest request = new TossConfirmRequest(paymentKey, orderId.toString(), amount);
        String requestJson = toJson(request);

        TossConfirmResponse response = restClient.post()
            .uri("/payments/confirm")
            .header("Idempotency-Key", "confirm-" + paymentKey)
            .contentType(MediaType.APPLICATION_JSON)
            .body(request)
            .retrieve()
            // ... 이하 기존과 동일
    });
}

private <T> T executeConfirm(Supplier<T> supplier) {
    try {
        Supplier<T> bulkheadDecorated = Bulkhead.decorateSupplier(confirmBulkhead, supplier);
        Supplier<T> rateLimiterDecorated = RateLimiter.decorateSupplier(confirmRateLimiter, bulkheadDecorated);
        Supplier<T> retryDecorated = Retry.decorateSupplier(confirmRetry, rateLimiterDecorated);
        return CircuitBreaker.decorateSupplier(confirmCircuitBreaker, retryDecorated).get();
    } catch (CallNotPermittedException exception) {
        // 기존과 동일 — PG_UNAVAILABLE
    } catch (RequestNotPermitted exception) {
        // 기존과 동일 — PG_RATE_LIMITED
    } catch (BulkheadFullException exception) {
        // 기존과 동일 — PG_BUSY
    }
}
```

## 설정값

`resilience4j.retry.instances.toss-confirm-retry`를 `application-local.yml`, `application-test.yml`, Config Server(`../config/src/main/resources/configs/payment-service.yml`) 세 곳 모두에 반영한다 — #356/#490/#491 때 확립한 컨벤션(환경별 파일에 각자 명시, 기본값 없이 필수 바인딩)을 그대로 따른다.

```yaml
resilience4j:
  retry:
    instances:
      toss-confirm-retry:
        max-attempts: 2
        wait-duration: 500ms
```

**의존성 추가** (`payment-service/build.gradle`):

```gradle
implementation 'io.github.resilience4j:resilience4j-retry:2.4.0'
```

기존 `resilience4j-circuitbreaker`/`bulkhead`와 동일 버전(2.4.0)으로 맞춘다. 루트 `build.gradle`에는 이 의존성이 없음을 확인했다.

## 테스트 계획

1. `TossPaymentGatewayRetryTest`(신규) — `TossPaymentGatewayCircuitBreakerTest`/`RateLimiterTest`와 동일 패턴(`com.sun.net.httpserver.HttpServer` stub).
   - 첫 호출은 연결 레벨 실패, 두 번째 호출은 성공 응답 → 최종적으로 `ConfirmResult` 정상 반환 확인.
   - 서버가 응답을 지연시켜 순수 타임아웃을 유발하는 케이스 → 재시도 없이(호출 횟수 1회) `PaymentGatewayException` 던지는지 확인.
   - Bulkhead/RateLimiter가 이미 포화된 상태를 만들어 `PG_BUSY`/`PG_RATE_LIMITED` 발생 시 재시도하지 않는지(호출 횟수로 검증).
   - 4xx(`PAYMENT_FAILED` 등) 응답에는 재시도하지 않는지 확인.
   - `Idempotency-Key` 헤더값이 `"confirm-" + paymentKey`인지 stub 서버에서 캡처해 검증.
2. `TossRetryConfig` bean 생성 테스트(`TossCircuitBreakerConfigTest` 패턴 참고) — `@Value` 바인딩으로 `Retry` 인스턴스가 올바른 설정(`maxAttempts`, `waitDuration`)으로 만들어지는지 확인.
3. CB가 재시도 소진 후 실패를 1건으로만 기록하는지 확인 — 작은 슬라이딩 윈도우의 테스트 전용 CircuitBreaker에 "재시도까지 모두 실패"하는 호출을 반복해, 실패로 기록된 콜 수가 (호출 횟수 × 재시도 횟수)가 아니라 (호출 횟수)와 일치하는지 검증.
4. 기존 `TossPaymentGatewayTest`/`BulkheadTest`/`CircuitBreakerTest`/`RateLimiterTest`는 생성자 시그니처 변경(`Retry` 파라미터 추가)에 맞춰 호출부 수정.
5. `ConfirmPaymentIntegrationTest`는 `@MockitoBean PaymentGateway`라 Retry 로직을 타지 않아 코드 변경 불필요. 단 `application-test.yml`에 값이 없으면 `TossRetryConfig`의 `@Value` 바인딩 실패로 컨텍스트 기동 자체가 실패하므로 값을 반드시 채운다.

## 이번 범위에서 제외한 것 (별도 이슈 후보)

- **refund() Retry**: 위 "범위" 절 참고.
- **Toss 결제 상태 조회(GET) API 연동**: 순수 타임아웃 시 실제 승인 여부를 조회해 확인하는 것이 더 근본적인 해결책이고 토스 문서도 이를 시사하지만, 이번 범위는 "재시도로 순간 blip 흡수"에 한정했다. 조회 API 신규 연동은 범위가 커 별도 이슈로 남긴다.
- **Config Server(`../config`) YAML 수정**: 원칙적으로 AI 작업 범위(`payment-service/`, `../docs/`)를 벗어나지만, 이번 작업에 한해 사용자가 명시적으로 승인했다(브레인스토밍 중 확인).
