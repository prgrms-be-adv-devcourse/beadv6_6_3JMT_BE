# 결제 승인 Retry/Timeout 보강 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `TossPaymentGateway.confirm()`에 Resilience4j Retry를 추가해 연결 레벨 실패/PG 5xx를 흡수하되, CircuitBreaker가 재시도 전체를 결과 1건으로만 기록하게 만들어 쉽게 OPEN되지 않게 한다.

**Architecture:** 데코레이터 순서 `CircuitBreaker(Retry(RateLimiter(Bulkhead(호출))))`. Retry 대상 판별은 `TossFailurePredicate`(CB용)와 분리한 신규 `TossRetryPredicate`가 맡는다 — 순수 타임아웃/Bulkhead·RateLimiter 거절은 재시도 제외.

**Tech Stack:** Spring Boot 4.1, Java 21, Resilience4j 2.4.0(circuitbreaker/bulkhead/ratelimiter 기존 + retry 신규), JUnit5 + AssertJ, `com.sun.net.httpserver.HttpServer` 기반 경량 HTTP stub(기존 Toss 게이트웨이 테스트 전부가 쓰는 패턴).

## Global Constraints

- 커밋 메시지: `type: 한국어 설명` (scope 없음), 본문 있으면 불릿, 마지막에 `Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>`. `#이슈번호`는 본문에 쓰지 않는다.
- `resilience4j-retry` 버전은 기존 `resilience4j-circuitbreaker`/`bulkhead`/`ratelimiter`와 동일한 `2.4.0`으로 고정한다.
- readTimeout(60초)은 변경하지 않는다(토스 공식 권장값, 이번 작업 범위 밖).
- 순수 타임아웃(`SocketTimeoutException`)은 재시도 대상에서 제외한다 — 연결 레벨 실패(`ResourceAccessException`, cause가 `SocketTimeoutException` 아닌 경우)와 `PaymentGatewayException(PG_SERVER_ERROR)`만 재시도 대상.
- `BulkheadFullException`, `RequestNotPermitted`도 재시도 대상에서 제외한다.
- 재시도는 최대 2회(원 시도 + 1회), 고정 500ms 대기.
- 단언은 AssertJ(`assertThat`)만 쓴다. 테스트 메서드명은 한국어.
- `payment-service` 디렉터리에서 Gradle 실행, `JAVA_HOME`은 asdf shim이 아닌 실제 JDK 21 경로(`~/.asdf/installs/java/temurin-21.0.5+11.0.LTS`)로 지정한다.
- 이 작업 범위는 `confirm()`뿐이다. `refund()`는 건드리지 않는다.
- Config Server(`../config/src/main/resources/configs/payment-service.yml`) 수정은 이번 작업에 한해 사용자가 명시적으로 승인했다.

참고 문서: `.claude/plans/529-payment-confirm-retry-timeout.md` (설계 문서, 이 계획의 근거).

---

### Task 1: 브랜치 최신화 + `TossRetryPredicate`

**Files:**
- Modify: (git 작업, 파일 변경 아님) 현재 브랜치를 `origin/develop`으로 fast-forward
- Create: `src/main/java/com/prompthub/payment/infrastructure/external/toss/TossRetryPredicate.java`
- Test: `src/test/java/com/prompthub/payment/infrastructure/external/toss/TossRetryPredicateTest.java`

**Interfaces:**
- Consumes: 없음(순수 신규 클래스, 외부 의존은 JDK/Spring 표준 타입뿐)
- Produces: `TossRetryPredicate implements Predicate<Throwable>` — `public boolean test(Throwable throwable)`. Task 2의 `TossRetryConfig`, Task 3의 `TossPaymentGateway` 재작업이 이 클래스를 그대로 재사용한다.

- [x] **Step 1: 브랜치를 origin/develop 최신 상태로 맞춘다**

로컬 브랜치 `feat/#529-payment-confirm-retry-timeout`이 `origin/develop`보다 뒤처져 있다(#519 RateLimiter 병합 미반영). 아래 명령으로 fast-forward한다.

```bash
git fetch origin develop
git status   # "Your branch is behind 'origin/develop' ... can be fast-forwarded" 확인
git merge --ff-only origin/develop
```

Expected: fast-forward 성공, `TossPaymentGateway.java`에 `RateLimiter confirmRateLimiter` 생성자 파라미터가 이미 존재하는 상태가 됨.

- [x] **Step 2: 실패하는 테스트 작성**

```java
package com.prompthub.payment.infrastructure.external.toss;

import com.prompthub.payment.application.exception.PaymentErrorCode;
import com.prompthub.payment.application.gateway.external.PaymentGatewayException;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import static org.assertj.core.api.Assertions.assertThat;

class TossRetryPredicateTest {

    private final TossRetryPredicate predicate = new TossRetryPredicate();

    @Test
    void 연결_거부_예외는_재시도_대상이다() {
        ResourceAccessException exception =
            new ResourceAccessException("Connection refused", new ConnectException("Connection refused"));

        assertThat(predicate.test(exception)).isTrue();
    }

    @Test
    void 순수_타임아웃은_재시도_대상이_아니다() {
        ResourceAccessException exception =
            new ResourceAccessException("Read timed out", new SocketTimeoutException("Read timed out"));

        assertThat(predicate.test(exception)).isFalse();
    }

    @Test
    void Toss_5xx_서버_오류는_재시도_대상이다() {
        PaymentGatewayException exception = new PaymentGatewayException(
            PaymentErrorCode.PG_SERVER_ERROR, "INTERNAL_SERVER_ERROR", "Toss 서버 오류", null, null
        );

        assertThat(predicate.test(exception)).isTrue();
    }

    @Test
    void 우리_쪽_요청_오류_4xx는_재시도_대상이_아니다() {
        PaymentGatewayException exception = new PaymentGatewayException(
            PaymentErrorCode.PAYMENT_FAILED, "EXCEED_MAX_DAILY_PAYMENT_COUNT", "한도 초과", null, null
        );

        assertThat(predicate.test(exception)).isFalse();
    }

    @Test
    void Bulkhead_포화_예외는_재시도_대상이_아니다() {
        BulkheadFullException exception = BulkheadFullException.createBulkheadFullException(
            io.github.resilience4j.bulkhead.Bulkhead.ofDefaults("test-bulkhead")
        );

        assertThat(predicate.test(exception)).isFalse();
    }

    @Test
    void RateLimiter_거절_예외는_재시도_대상이_아니다() {
        RequestNotPermitted exception = RequestNotPermitted.createRequestNotPermitted(
            io.github.resilience4j.ratelimiter.RateLimiter.ofDefaults("test-rate-limiter")
        );

        assertThat(predicate.test(exception)).isFalse();
    }
}
```

- [x] **Step 3: 테스트 실패 확인**

Run:
```bash
JAVA_HOME=~/.asdf/installs/java/temurin-21.0.5+11.0.LTS ../gradlew :payment-service:test --tests "com.prompthub.payment.infrastructure.external.toss.TossRetryPredicateTest"
```
Expected: FAIL — `TossRetryPredicate` 클래스가 없어 컴파일 에러.

- [x] **Step 4: `TossRetryPredicate` 구현**

```java
package com.prompthub.payment.infrastructure.external.toss;

import com.prompthub.payment.application.exception.PaymentErrorCode;
import com.prompthub.payment.application.gateway.external.PaymentGatewayException;
import java.net.SocketTimeoutException;
import java.util.Set;
import java.util.function.Predicate;
import org.springframework.web.client.ResourceAccessException;

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

- [x] **Step 5: 테스트 통과 확인**

Run:
```bash
JAVA_HOME=~/.asdf/installs/java/temurin-21.0.5+11.0.LTS ../gradlew :payment-service:test --tests "com.prompthub.payment.infrastructure.external.toss.TossRetryPredicateTest"
```
Expected: PASS (테스트 6개 전부 통과)

- [x] **Step 6: Commit**

```bash
git add src/main/java/com/prompthub/payment/infrastructure/external/toss/TossRetryPredicate.java \
        src/test/java/com/prompthub/payment/infrastructure/external/toss/TossRetryPredicateTest.java
git commit -m "$(cat <<'EOF'
feat: Toss 결제 승인 재시도 대상 판별 Predicate 추가

- 연결 레벨 실패(ResourceAccessException, 원인이 SocketTimeoutException 아닌 경우)와 PG_SERVER_ERROR만 재시도 대상으로 판정
- 순수 타임아웃, Bulkhead/RateLimiter 거절, 4xx는 재시도 대상에서 제외

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: `resilience4j-retry` 의존성 + YAML 설정 + `TossRetryConfig`

**Files:**
- Modify: `build.gradle`
- Modify: `src/main/resources/application-local.yml`
- Modify: `src/test/resources/application-test.yml`
- Modify: `../config/src/main/resources/configs/payment-service.yml`
- Create: `src/main/java/com/prompthub/payment/infrastructure/external/toss/TossRetryConfig.java`
- Test: `src/test/java/com/prompthub/payment/infrastructure/external/toss/TossRetryConfigTest.java`

**Interfaces:**
- Consumes: 없음
- Produces: `TossRetryConfig` — `RetryRegistry tossRetryRegistry(int maxAttempts, Duration waitDuration)` 빈, `Retry tossConfirmRetry(RetryRegistry)`(`@Qualifier("tossConfirmRetry")`) 빈. Task 3의 `TossPaymentGateway` 생성자가 `tossConfirmRetry` 빈을 주입받는다.

- [ ] **Step 1: build.gradle에 의존성 추가**

`build.gradle`의 기존 resilience4j 의존성 블록(1~7행 근처, `resilience4j-circuitbreaker`/`micrometer`/`bulkhead` 선언부) 바로 아래에 추가:

```gradle
implementation 'io.github.resilience4j:resilience4j-retry:2.4.0'
```

- [ ] **Step 2: 3개 YAML 파일에 retry 설정 추가**

`src/main/resources/application-local.yml`의 `resilience4j:` 블록에 `retry:` 섹션 추가(기존 `circuitbreaker:`/`bulkhead:` 항목과 같은 들여쓰기 레벨):

```yaml
  retry:
    instances:
      toss-confirm-retry:
        max-attempts: 2
        wait-duration: 500ms
```

`src/test/resources/application-test.yml`도 동일하게 추가.

`../config/src/main/resources/configs/payment-service.yml`도 동일하게 추가(41행 근처 `resilience4j:` 블록).

- [ ] **Step 3: 실패하는 테스트 작성**

```java
package com.prompthub.payment.infrastructure.external.toss;

import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TossRetryConfigTest {

    private final TossRetryConfig config = new TossRetryConfig();

    @Test
    void 설정값대로_confirm_Retry를_생성한다() {
        RetryRegistry registry = config.tossRetryRegistry(2, Duration.ofMillis(500));

        Retry confirmRetry = config.tossConfirmRetry(registry);

        assertThat(confirmRetry.getName()).isEqualTo("tossConfirmRetry");
        assertThat(confirmRetry.getRetryConfig().getMaxAttempts()).isEqualTo(2);
        assertThat(confirmRetry.getRetryConfig().getIntervalBiFunction().apply(1, null)).isEqualTo(500L);
    }
}
```

- [ ] **Step 4: 테스트 실패 확인**

Run:
```bash
JAVA_HOME=~/.asdf/installs/java/temurin-21.0.5+11.0.LTS ../gradlew :payment-service:test --tests "com.prompthub.payment.infrastructure.external.toss.TossRetryConfigTest"
```
Expected: FAIL — `TossRetryConfig` 클래스가 없어 컴파일 에러.

- [ ] **Step 5: `TossRetryConfig` 구현**

```java
package com.prompthub.payment.infrastructure.external.toss;

import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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

- [ ] **Step 6: 테스트 통과 확인**

Run:
```bash
JAVA_HOME=~/.asdf/installs/java/temurin-21.0.5+11.0.LTS ../gradlew :payment-service:test --tests "com.prompthub.payment.infrastructure.external.toss.TossRetryConfigTest"
```
Expected: PASS

- [ ] **Step 7: 전체 컨텍스트 기동 확인 (YAML 바인딩 누락 조기 발견)**

`TossRetryConfig`가 `@Value` 바인딩에 실패하면 Spring 컨텍스트를 로드하는 다른 모든 테스트가 깨진다. 아래로 조기 확인한다.

```bash
JAVA_HOME=~/.asdf/installs/java/temurin-21.0.5+11.0.LTS ../gradlew :payment-service:test --tests "com.prompthub.payment.ConfirmPaymentIntegrationTest"
```
Expected: PASS (기존 테스트 그대로 통과 — `application-test.yml`에 값이 채워졌는지 확인하는 용도)

- [ ] **Step 8: Commit**

```bash
git add build.gradle \
        src/main/resources/application-local.yml \
        src/test/resources/application-test.yml \
        ../config/src/main/resources/configs/payment-service.yml \
        src/main/java/com/prompthub/payment/infrastructure/external/toss/TossRetryConfig.java \
        src/test/java/com/prompthub/payment/infrastructure/external/toss/TossRetryConfigTest.java
git commit -m "$(cat <<'EOF'
feat: Toss 결제 승인 Retry 설정 및 의존성 추가

- resilience4j-retry 2.4.0 의존성 추가(기존 circuitbreaker/bulkhead/ratelimiter와 버전 통일)
- toss-confirm-retry 인스턴스(max-attempts 2, wait-duration 500ms) local/test/Config Server 3곳에 반영
- TossRetryConfig로 RetryRegistry/tossConfirmRetry 빈 등록, 재시도 대상 판별은 TossRetryPredicate 사용

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: `TossPaymentGateway`에 Retry 결합 + Idempotency-Key + 회귀 테스트

**Files:**
- Modify: `src/main/java/com/prompthub/payment/infrastructure/external/toss/TossPaymentGateway.java`
- Modify: `src/test/java/com/prompthub/payment/infrastructure/external/toss/TossPaymentGatewayTest.java`
- Modify: `src/test/java/com/prompthub/payment/infrastructure/external/toss/TossPaymentGatewayBulkheadTest.java`
- Modify: `src/test/java/com/prompthub/payment/infrastructure/external/toss/TossPaymentGatewayCircuitBreakerTest.java`
- Modify: `src/test/java/com/prompthub/payment/infrastructure/external/toss/TossPaymentGatewayRateLimiterTest.java`
- Create: `src/test/java/com/prompthub/payment/infrastructure/external/toss/TossPaymentGatewayRetryTest.java`

**Interfaces:**
- Consumes: Task 1의 `TossRetryPredicate`(간접, `TossRetryConfig` 경유), Task 2의 `tossConfirmRetry` 빈(`Retry` 타입)
- Produces: `TossPaymentGateway` 생성자 시그니처 — `TossPaymentGateway(String secretKey, String baseUrl, ObjectMapper objectMapper, CircuitBreaker confirmCircuitBreaker, CircuitBreaker refundCircuitBreaker, Bulkhead confirmBulkhead, RateLimiter confirmRateLimiter, Retry confirmRetry)` — 마지막 파라미터로 `Retry confirmRetry` 추가.

먼저 기존 4개 테스트의 `new TossPaymentGateway(...)` 호출부에 `Retry` 인자를 하나씩 추가해야 컴파일이 된다(각 파일 동일 패턴 — `RateLimiter.ofDefaults(...)` 다음 줄에 `Retry.ofDefaults("test-confirm-retry")` 추가).

- [ ] **Step 1: 신규 회귀 테스트(재시도 성공/미대상 케이스) 작성**

```java
package com.prompthub.payment.infrastructure.external.toss;

import com.prompthub.payment.application.exception.PaymentErrorCode;
import com.prompthub.payment.application.gateway.external.ConfirmResult;
import com.prompthub.payment.application.gateway.external.PaymentGatewayException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TossPaymentGatewayRetryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;
    private ScheduledExecutorService scheduler;

    private static final String SUCCESS_BODY = """
        {"paymentKey":"pg-key","orderId":"order-id","method":"카드","totalAmount":1000,\
        "approvedAt":"2026-07-13T10:00:00+09:00","requestedAt":"2026-07-13T09:59:00+09:00","status":"DONE"}
        """;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    private Retry retryOf(String name) {
        return Retry.of(name, RetryConfig.custom()
            .maxAttempts(2)
            .waitDuration(Duration.ofMillis(500))
            .retryOnException(new TossRetryPredicate())
            .build());
    }

    @Test
    void 연결_거부_후_재시도로_성공한다() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        int port = server.getAddress().getPort();
        server.createContext("/v1/payments/confirm", exchange -> {
            byte[] bytes = SUCCESS_BODY.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        // 서버를 아직 start()하지 않는다 — 첫 시도는 연결 거부(ConnectException)로 실패시키기 위함

        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.schedule(() -> {
            try {
                server.start();
            } catch (Exception ignored) {
                // 테스트 종료 후 호출될 수 있는 경합은 무시
            }
        }, 200, TimeUnit.MILLISECONDS);

        String baseUrl = "http://localhost:" + port + "/v1";
        TossPaymentGateway gateway = new TossPaymentGateway(
            "test-secret-key", baseUrl, objectMapper,
            CircuitBreaker.ofDefaults("test-confirm"),
            CircuitBreaker.ofDefaults("test-refund"),
            Bulkhead.ofDefaults("test-confirm-bulkhead"),
            RateLimiter.ofDefaults("test-confirm-rate-limiter"),
            retryOf("test-confirm-retry")
        );

        ConfirmResult result = gateway.confirm("pg-key", UUID.randomUUID(), 1_000);

        assertThat(result.approvedAmount()).isEqualTo(1000);
    }

    @Test
    void PG_4xx_오류는_재시도하지_않는다() throws IOException {
        AtomicInteger callCount = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/payments/confirm", exchange -> {
            callCount.incrementAndGet();
            String body = """
                {"code":"EXCEED_MAX_DAILY_PAYMENT_COUNT","message":"한도 초과"}
                """;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        String baseUrl = "http://localhost:" + server.getAddress().getPort() + "/v1";
        TossPaymentGateway gateway = new TossPaymentGateway(
            "test-secret-key", baseUrl, objectMapper,
            CircuitBreaker.ofDefaults("test-confirm"),
            CircuitBreaker.ofDefaults("test-refund"),
            Bulkhead.ofDefaults("test-confirm-bulkhead"),
            RateLimiter.ofDefaults("test-confirm-rate-limiter"),
            retryOf("test-confirm-retry")
        );

        assertThatThrownBy(() -> gateway.confirm("pg-key", UUID.randomUUID(), 1_000))
            .isInstanceOf(PaymentGatewayException.class)
            .extracting(exception -> ((PaymentGatewayException) exception).getErrorCode())
            .isEqualTo(PaymentErrorCode.PAYMENT_FAILED);

        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    void Bulkhead_포화는_재시도하지_않는다() throws IOException {
        AtomicInteger callCount = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/payments/confirm", exchange -> {
            callCount.incrementAndGet();
            byte[] bytes = SUCCESS_BODY.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        Bulkhead exhaustedBulkhead = Bulkhead.of("test-confirm-bulkhead",
            BulkheadConfig.custom().maxConcurrentCalls(0).maxWaitDuration(Duration.ZERO).build());

        String baseUrl = "http://localhost:" + server.getAddress().getPort() + "/v1";
        TossPaymentGateway gateway = new TossPaymentGateway(
            "test-secret-key", baseUrl, objectMapper,
            CircuitBreaker.ofDefaults("test-confirm"),
            CircuitBreaker.ofDefaults("test-refund"),
            exhaustedBulkhead,
            RateLimiter.ofDefaults("test-confirm-rate-limiter"),
            retryOf("test-confirm-retry")
        );

        assertThatThrownBy(() -> gateway.confirm("pg-key", UUID.randomUUID(), 1_000))
            .isInstanceOf(PaymentGatewayException.class)
            .extracting(exception -> ((PaymentGatewayException) exception).getErrorCode())
            .isEqualTo(PaymentErrorCode.PG_BUSY);

        assertThat(callCount.get()).isZero();
    }

    @Test
    void RateLimiter_거절은_재시도하지_않는다() throws IOException {
        AtomicInteger callCount = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/payments/confirm", exchange -> {
            callCount.incrementAndGet();
            byte[] bytes = SUCCESS_BODY.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        RateLimiter exhaustedRateLimiter = RateLimiter.of("test-confirm-rate-limiter",
            RateLimiterConfig.custom()
                .limitForPeriod(0)
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .timeoutDuration(Duration.ZERO)
                .build());

        String baseUrl = "http://localhost:" + server.getAddress().getPort() + "/v1";
        TossPaymentGateway gateway = new TossPaymentGateway(
            "test-secret-key", baseUrl, objectMapper,
            CircuitBreaker.ofDefaults("test-confirm"),
            CircuitBreaker.ofDefaults("test-refund"),
            Bulkhead.ofDefaults("test-confirm-bulkhead"),
            exhaustedRateLimiter,
            retryOf("test-confirm-retry")
        );

        assertThatThrownBy(() -> gateway.confirm("pg-key", UUID.randomUUID(), 1_000))
            .isInstanceOf(PaymentGatewayException.class)
            .extracting(exception -> ((PaymentGatewayException) exception).getErrorCode())
            .isEqualTo(PaymentErrorCode.PG_RATE_LIMITED);

        assertThat(callCount.get()).isZero();
    }

    @Test
    void Idempotency_Key_헤더에_confirm_접두사와_paymentKey를_담는다() throws IOException {
        AtomicReference<HttpExchange> capturedExchange = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/payments/confirm", exchange -> {
            capturedExchange.set(exchange);
            byte[] bytes = SUCCESS_BODY.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        String baseUrl = "http://localhost:" + server.getAddress().getPort() + "/v1";
        TossPaymentGateway gateway = new TossPaymentGateway(
            "test-secret-key", baseUrl, objectMapper,
            CircuitBreaker.ofDefaults("test-confirm"),
            CircuitBreaker.ofDefaults("test-refund"),
            Bulkhead.ofDefaults("test-confirm-bulkhead"),
            RateLimiter.ofDefaults("test-confirm-rate-limiter"),
            retryOf("test-confirm-retry")
        );

        gateway.confirm("pg-key-123", UUID.randomUUID(), 1_000);

        String idempotencyKey = capturedExchange.get().getRequestHeaders().getFirst("Idempotency-Key");
        assertThat(idempotencyKey).isEqualTo("confirm-pg-key-123");
    }

    @Test
    void 재시도_소진_실패는_CircuitBreaker에_1건으로만_기록된다() throws IOException {
        AtomicInteger callCount = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/payments/confirm", exchange -> {
            callCount.incrementAndGet();
            String body = """
                {"code":"INTERNAL_SERVER_ERROR","message":"Toss 서버 오류"}
                """;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        io.github.resilience4j.circuitbreaker.CircuitBreakerConfig cbConfig =
            io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.custom()
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50)
                .recordException(new TossFailurePredicate())
                .build();
        CircuitBreaker confirmCircuitBreaker = CircuitBreaker.of("test-confirm", cbConfig);
        Retry fastRetry = Retry.of("test-confirm-retry", RetryConfig.custom()
            .maxAttempts(2)
            .waitDuration(Duration.ofMillis(10))
            .retryOnException(new TossRetryPredicate())
            .build());

        String baseUrl = "http://localhost:" + server.getAddress().getPort() + "/v1";
        TossPaymentGateway gateway = new TossPaymentGateway(
            "test-secret-key", baseUrl, objectMapper,
            confirmCircuitBreaker,
            CircuitBreaker.ofDefaults("test-refund"),
            Bulkhead.ofDefaults("test-confirm-bulkhead"),
            RateLimiter.ofDefaults("test-confirm-rate-limiter"),
            fastRetry
        );

        assertThatThrownBy(() -> gateway.confirm("pg-key", UUID.randomUUID(), 1_000))
            .isInstanceOf(PaymentGatewayException.class);
        assertThatThrownBy(() -> gateway.confirm("pg-key", UUID.randomUUID(), 1_000))
            .isInstanceOf(PaymentGatewayException.class);

        // confirm() 2번 호출, 각 호출마다 원 시도+재시도 = 총 HTTP 호출 4번
        assertThat(callCount.get()).isEqualTo(4);
        // 그러나 CircuitBreaker 슬라이딩 윈도우(크기 2)에는 confirm() 호출 단위로 2건만 기록되어 OPEN
        assertThat(confirmCircuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }
}
```

순수 타임아웃(readTimeout 60초 소진) 시 재시도하지 않는다는 요구사항은 실제 60초를 기다려야 재현되므로 게이트웨이 레벨 테스트로 만들지 않는다 — Task 1의 `TossRetryPredicateTest.순수_타임아웃은_재시도_대상이_아니다`가 이 로직을 이미 단위 테스트로 검증한다.

- [ ] **Step 2: 테스트 실패 확인**

Run:
```bash
JAVA_HOME=~/.asdf/installs/java/temurin-21.0.5+11.0.LTS ../gradlew :payment-service:test --tests "com.prompthub.payment.infrastructure.external.toss.TossPaymentGatewayRetryTest"
```
Expected: FAIL — `TossPaymentGateway` 생성자에 `Retry` 파라미터가 없어 컴파일 에러.

- [ ] **Step 3: `TossPaymentGateway` 수정**

`private final Bulkhead confirmBulkhead;` 아래(현재 `RateLimiter confirmRateLimiter` 필드 다음)에 필드 추가:

```java
    private final Retry confirmRetry;
```

생성자 파라미터 마지막에 추가:

```java
    public TossPaymentGateway(
        @Value("${payment.toss.secret-key}") String secretKey,
        @Value("${payment.toss.base-url:https://api.tosspayments.com/v1}") String baseUrl,
        ObjectMapper objectMapper,
        @Qualifier("tossConfirmCircuitBreaker") CircuitBreaker confirmCircuitBreaker,
        @Qualifier("tossRefundCircuitBreaker") CircuitBreaker refundCircuitBreaker,
        @Qualifier("tossConfirmBulkhead") Bulkhead confirmBulkhead,
        @Qualifier("tossConfirmRateLimiter") RateLimiter confirmRateLimiter,
        @Qualifier("tossConfirmRetry") Retry confirmRetry
    ) {
        // ... 기존 대입 코드 그대로 ...
        this.confirmRetry = confirmRetry;
    }
```

`confirm()` 메서드의 요청 빌더에 헤더 추가(`.contentType(MediaType.APPLICATION_JSON)` 앞):

```java
            TossConfirmResponse response = restClient.post()
                .uri("/payments/confirm")
                .header("Idempotency-Key", "confirm-" + paymentKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                // ... 이하 기존과 동일
```

`executeConfirm`을 아래로 교체:

```java
    private <T> T executeConfirm(Supplier<T> supplier) {
        try {
            Supplier<T> bulkheadDecorated = Bulkhead.decorateSupplier(confirmBulkhead, supplier);
            Supplier<T> rateLimiterDecorated = RateLimiter.decorateSupplier(confirmRateLimiter, bulkheadDecorated);
            Supplier<T> retryDecorated = Retry.decorateSupplier(confirmRetry, rateLimiterDecorated);
            return CircuitBreaker.decorateSupplier(confirmCircuitBreaker, retryDecorated).get();
        } catch (CallNotPermittedException exception) {
            log.warn("Toss 서킷브레이커 OPEN — 호출 차단됨. circuitBreaker={}", confirmCircuitBreaker.getName());
            throw new PaymentGatewayException(
                PaymentErrorCode.PG_UNAVAILABLE, "CIRCUIT_OPEN",
                "PG사 서킷브레이커가 열려 있어 호출을 차단했습니다.", null, null
            );
        } catch (RequestNotPermitted exception) {
            log.warn("Toss 확인 RateLimiter 거절 — 유량 상한 초과. rateLimiter={}", confirmRateLimiter.getName());
            throw new PaymentGatewayException(
                PaymentErrorCode.PG_RATE_LIMITED, "RATE_LIMITED",
                "결제 승인 유량 상한을 초과했습니다.", null, null
            );
        } catch (BulkheadFullException exception) {
            log.warn("Toss 확인 Bulkhead 포화 — 동시 호출 상한 초과. bulkhead={}", confirmBulkhead.getName());
            throw new PaymentGatewayException(
                PaymentErrorCode.PG_BUSY, "BULKHEAD_FULL",
                "결제 승인 동시 호출 상한을 초과했습니다.", null, null
            );
        }
    }
```

import 추가: `io.github.resilience4j.retry.Retry`.

- [ ] **Step 4: 기존 4개 테스트에 `Retry` 인자 추가**

`TossPaymentGatewayTest.java`, `TossPaymentGatewayBulkheadTest.java`, `TossPaymentGatewayCircuitBreakerTest.java`, `TossPaymentGatewayRateLimiterTest.java` 각각의 `new TossPaymentGateway(...)` 호출부 마지막 인자 뒤에 추가:

```java
            RateLimiter.ofDefaults("test-confirm-rate-limiter"),
            Retry.ofDefaults("test-confirm-retry")
```

`import io.github.resilience4j.retry.Retry;` 각 파일에 추가.

- [ ] **Step 5: 테스트 통과 확인**

Run:
```bash
JAVA_HOME=~/.asdf/installs/java/temurin-21.0.5+11.0.LTS ../gradlew :payment-service:test --tests "com.prompthub.payment.infrastructure.external.toss.*"
```
Expected: PASS (Toss 게이트웨이 관련 테스트 전부 통과 — `TossPaymentGatewayRetryTest` 포함)

- [ ] **Step 6: 전체 테스트 스위트 회귀 확인**

Run:
```bash
JAVA_HOME=~/.asdf/installs/java/temurin-21.0.5+11.0.LTS ../gradlew :payment-service:test
```
Expected: BUILD SUCCESSFUL, 전체 테스트 PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/prompthub/payment/infrastructure/external/toss/TossPaymentGateway.java \
        src/test/java/com/prompthub/payment/infrastructure/external/toss/TossPaymentGatewayTest.java \
        src/test/java/com/prompthub/payment/infrastructure/external/toss/TossPaymentGatewayBulkheadTest.java \
        src/test/java/com/prompthub/payment/infrastructure/external/toss/TossPaymentGatewayCircuitBreakerTest.java \
        src/test/java/com/prompthub/payment/infrastructure/external/toss/TossPaymentGatewayRateLimiterTest.java \
        src/test/java/com/prompthub/payment/infrastructure/external/toss/TossPaymentGatewayRetryTest.java
git commit -m "$(cat <<'EOF'
feat: 결제 승인 호출에 Retry 결합 및 Idempotency-Key 추가

- 데코레이터 순서를 CircuitBreaker(Retry(RateLimiter(Bulkhead(호출))))로 결합해 재시도 전체가 CircuitBreaker에 결과 1건으로만 기록되게 함
- confirm() 요청에 Idempotency-Key(confirm-{paymentKey}) 헤더 추가
- 연결 레벨 실패/PG 5xx는 최대 2회(원 시도+1회) 재시도, 순수 타임아웃/Bulkhead·RateLimiter 거절/4xx는 재시도 제외

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Self-Review 메모

- **스펙 커버리지**: 설계 문서(`529-payment-confirm-retry-timeout.md`)의 확정 사항 1~6번 전부 태스크에 반영됨 — 1(readTimeout 유지, 변경 없음 자체가 확인 대상), 2(Task 1 predicate + Task 3 테스트 주석으로 근거 명시), 3(Task 3 Idempotency-Key), 4(Task 2 YAML max-attempts/wait-duration), 5(Task 3 데코레이터 순서 + 회귀 테스트), 6(Task 1 predicate 테스트).
- **플레이스홀더 스캔**: "TBD"/"나중에"/"적절히 처리" 패턴 없음. 모든 스텝에 실행 가능한 코드/명령 포함.
- **타입 일관성**: `TossPaymentGateway` 생성자 파라미터 순서(Task 3에서 `confirmRateLimiter` 다음 `confirmRetry`)가 Task 2가 만든 빈 이름(`tossConfirmRetry`)과 Task 1의 `TossRetryPredicate` 타입과 전부 일치.
- 순수 타임아웃의 게이트웨이 레벨(end-to-end) 검증은 60초 대기가 필요해 비현실적이라 의도적으로 제외했고, Task 3 테스트 파일 내 주석과 이 메모에 그 이유를 남겼다 — 누락이 아니라 트레이드오프임을 명시.
