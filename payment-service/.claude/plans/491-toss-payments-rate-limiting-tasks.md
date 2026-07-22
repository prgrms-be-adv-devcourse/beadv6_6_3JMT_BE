# Toss 결제 승인 유량제어(RateLimiter) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `TossPaymentGateway.confirm()` 호출에 Resilience4j RateLimiter를 적용해, Toss API 호출 빈도가 PG사의 (문서화되지 않은) quota를 초과하지 않도록 선제 방어한다.

**Architecture:** `infrastructure/external/toss` 패키지에 `TossRateLimiterConfig`(RateLimiter 빈 1개 + 메트릭)를 추가하고, `TossPaymentGateway`가 이 `RateLimiter`를 생성자로 주입받아 `confirm()` 호출을 CircuitBreaker(바깥) → RateLimiter(중간) → Bulkhead(안쪽) 순서로 감싼다. 유량 상한 초과 시 실제 HTTP 호출 없이 신규 에러코드 `PG_RATE_LIMITED`(503)를 던진다. `refund()`는 이번 범위에서 제외한다(설계 문서 참고).

**Tech Stack:** Java 21, Spring Boot 4.1, `io.github.resilience4j:resilience4j-ratelimiter:2.4.0`, `io.github.resilience4j:resilience4j-micrometer:2.4.0`(기존 의존성 재사용), JUnit 5, AssertJ, `com.sun.net.httpserver.HttpServer`(기존 테스트 방식).

## Global Constraints

- 테스트 메서드명은 한국어로 작성한다(CLAUDE.md 언어 정책).
- 단언은 AssertJ(`assertThat`/`assertThatThrownBy`)만 사용한다.
- 신규 의존성은 `payment-service/build.gradle`에만 추가한다(루트 `build.gradle`에 resilience4j 없음 — 중복 확인 완료).
- 커밋 메시지는 `type: 한국어 설명` 형식, AI 협업 트레일러 `Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>` 포함(git-conventions.md).
- 브랜치는 이미 존재(`feat/#491-payment-rate-limiting`) — 새로 만들지 않는다. Bulkhead(#490)가 `origin/develop`에 머지되어(PR #512), 이 브랜치는 이미 `origin/develop`으로 rebase 완료된 상태다 — `TossBulkheadConfig`, `TossPaymentGateway`의 6-arg 생성자(Bulkhead 포함), `PaymentErrorCode.PG_BUSY`(PAY013)가 이미 존재한다.
- 설계 근거는 `.claude/plans/491-toss-payments-rate-limiting.md` 참조.
- `refund()`, `OrderEventConsumer` concurrency 값은 이번 범위에서 손대지 않는다(설계 문서 "이번 범위에서 제외한 것" 참고).

---

### Task 1: TossRateLimiterConfig — RateLimiter 빈 + 설정값

**Files:**
- Modify: `build.gradle`(dependencies 블록에 resilience4j-ratelimiter 추가)
- Create: `src/main/java/com/prompthub/payment/infrastructure/external/toss/TossRateLimiterConfig.java`
- Test: `src/test/java/com/prompthub/payment/infrastructure/external/toss/TossRateLimiterConfigTest.java`
- Modify: `src/main/resources/application-local.yml`(ratelimiter 설정 + CB ignore-exceptions 추가)
- Modify: `src/test/resources/application-test.yml`(동일)
- Modify: `../config/src/main/resources/configs/payment-service.yml`(동일)

**Interfaces:**
- Consumes: 없음
- Produces: `TossRateLimiterConfig`의 빈 2개 — `RateLimiterRegistry tossRateLimiterRegistry(int, Duration, Duration)`, `RateLimiter tossConfirmRateLimiter(RateLimiterRegistry)`(빈 이름 `"tossConfirmRateLimiter"`) — Task 2의 `TossPaymentGateway` 생성자가 주입받아 사용

- [ ] **Step 1: build.gradle에 의존성 추가**

`build.gradle`의 `dependencies` 블록에서 기존 resilience4j 줄 바로 아래에 추가:

```groovy
    implementation 'io.github.resilience4j:resilience4j-circuitbreaker:2.4.0'
    implementation 'io.github.resilience4j:resilience4j-micrometer:2.4.0'
    implementation 'io.github.resilience4j:resilience4j-bulkhead:2.4.0'
    implementation 'io.github.resilience4j:resilience4j-ratelimiter:2.4.0'
```

- [ ] **Step 2: 빌드 확인**

Run: `../gradlew :payment-service:compileJava`
Expected: BUILD SUCCESSFUL (새 의존성이 정상적으로 resolve됨)

- [ ] **Step 3: 실패 테스트 작성**

```java
package com.prompthub.payment.infrastructure.external.toss;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TossRateLimiterConfigTest {

    private final TossRateLimiterConfig config = new TossRateLimiterConfig();

    @Test
    void 설정값대로_confirm_RateLimiter를_생성한다() {
        RateLimiterRegistry registry = config.tossRateLimiterRegistry(30, Duration.ofSeconds(1), Duration.ZERO);

        RateLimiter confirmRateLimiter = config.tossConfirmRateLimiter(registry);

        assertThat(confirmRateLimiter.getName()).isEqualTo("tossConfirmRateLimiter");
        assertThat(confirmRateLimiter.getRateLimiterConfig().getLimitForPeriod()).isEqualTo(30);
        assertThat(confirmRateLimiter.getRateLimiterConfig().getLimitRefreshPeriod())
            .isEqualTo(Duration.ofSeconds(1));
        assertThat(confirmRateLimiter.getRateLimiterConfig().getTimeoutDuration()).isEqualTo(Duration.ZERO);
    }
}
```

- [ ] **Step 4: 테스트 실행해서 실패 확인**

Run: `../gradlew :payment-service:test --tests "com.prompthub.payment.infrastructure.external.toss.TossRateLimiterConfigTest"`
Expected: FAIL — `TossRateLimiterConfig` 클래스가 없어 컴파일 에러

- [ ] **Step 5: 최소 구현**

```java
package com.prompthub.payment.infrastructure.external.toss;

import io.github.resilience4j.micrometer.tagged.TaggedRateLimiterMetrics;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TossRateLimiterConfig {

    @Bean
    public RateLimiterRegistry tossRateLimiterRegistry(
        @Value("${resilience4j.ratelimiter.instances.toss-confirm-rate-limiter.limit-for-period}")
        int limitForPeriod,
        @Value("${resilience4j.ratelimiter.instances.toss-confirm-rate-limiter.limit-refresh-period}")
        Duration limitRefreshPeriod,
        @Value("${resilience4j.ratelimiter.instances.toss-confirm-rate-limiter.timeout-duration}")
        Duration timeoutDuration
    ) {
        RateLimiterConfig rateLimiterConfig = RateLimiterConfig.custom()
            .limitForPeriod(limitForPeriod)
            .limitRefreshPeriod(limitRefreshPeriod)
            .timeoutDuration(timeoutDuration)
            .build();
        return RateLimiterRegistry.of(rateLimiterConfig);
    }

    @Bean("tossConfirmRateLimiter")
    public RateLimiter tossConfirmRateLimiter(RateLimiterRegistry tossRateLimiterRegistry) {
        return tossRateLimiterRegistry.rateLimiter("tossConfirmRateLimiter");
    }

    @Bean
    public MeterBinder tossRateLimiterMetrics(RateLimiterRegistry tossRateLimiterRegistry) {
        return TaggedRateLimiterMetrics.ofRateLimiterRegistry(tossRateLimiterRegistry);
    }
}
```

- [ ] **Step 6: 테스트 실행해서 통과 확인**

Run: `../gradlew :payment-service:test --tests "com.prompthub.payment.infrastructure.external.toss.TossRateLimiterConfigTest"`
Expected: PASS

- [ ] **Step 7: yml 3곳에 설정값 반영**

`src/main/resources/application-local.yml`의 기존 `resilience4j:` 블록을 아래로 교체(`ignore-exceptions`에 `RequestNotPermitted` 추가 + `ratelimiter` 섹션 신설):

```yaml
resilience4j:
  circuitbreaker:
    configs:
      toss-payment-default:
        sliding-window-size: 20
        minimum-number-of-calls: 10
        failure-rate-threshold: 50
        slow-call-duration-threshold: 20000ms
        slow-call-rate-threshold: 50
        wait-duration-in-open-state: 30s
        permitted-number-of-calls-in-half-open-state: 3
        ignore-exceptions:
          - io.github.resilience4j.bulkhead.BulkheadFullException
          - io.github.resilience4j.ratelimiter.RequestNotPermitted
  bulkhead:
    instances:
      toss-confirm-bulkhead:
        max-concurrent-calls: 20
        max-wait-duration: 200ms
  ratelimiter:
    instances:
      toss-confirm-rate-limiter:
        limit-for-period: 30
        limit-refresh-period: 1s
        timeout-duration: 0ms
```

`src/test/resources/application-test.yml`의 `resilience4j:` 블록도 동일하게 교체:

```yaml
resilience4j:
  circuitbreaker:
    configs:
      toss-payment-default:
        sliding-window-size: 20
        minimum-number-of-calls: 10
        failure-rate-threshold: 50
        slow-call-duration-threshold: 20000ms
        slow-call-rate-threshold: 50
        wait-duration-in-open-state: 30s
        permitted-number-of-calls-in-half-open-state: 3
        ignore-exceptions:
          - io.github.resilience4j.bulkhead.BulkheadFullException
          - io.github.resilience4j.ratelimiter.RequestNotPermitted
  bulkhead:
    instances:
      toss-confirm-bulkhead:
        max-concurrent-calls: 20
        max-wait-duration: 200ms
  ratelimiter:
    instances:
      toss-confirm-rate-limiter:
        limit-for-period: 30
        limit-refresh-period: 1s
        timeout-duration: 0ms
```

`../config/src/main/resources/configs/payment-service.yml`의 `resilience4j:` 블록도 동일하게 교체:

```yaml
resilience4j:
  circuitbreaker:
    configs:
      toss-payment-default:
        sliding-window-size: 20
        minimum-number-of-calls: 10
        failure-rate-threshold: 50
        slow-call-duration-threshold: 20000ms
        slow-call-rate-threshold: 50
        wait-duration-in-open-state: 30s
        permitted-number-of-calls-in-half-open-state: 3
        ignore-exceptions:
          - io.github.resilience4j.bulkhead.BulkheadFullException
          - io.github.resilience4j.ratelimiter.RequestNotPermitted
  bulkhead:
    instances:
      toss-confirm-bulkhead:
        max-concurrent-calls: 20
        max-wait-duration: 200ms
  ratelimiter:
    instances:
      toss-confirm-rate-limiter:
        limit-for-period: 30
        limit-refresh-period: 1s
        timeout-duration: 0ms
```

- [ ] **Step 8: 전체 테스트 스위트 실행 — 회귀 확인**

Run: `JAVA_HOME=~/.asdf/installs/java/temurin-21.0.5+11.0.LTS ../gradlew :payment-service:test`
Expected: BUILD SUCCESSFUL (이 시점엔 아직 `TossPaymentGateway`가 `TossRateLimiterConfig`의 빈을 쓰지 않으므로 기존 테스트에 영향 없음 — 다음 Task에서 실제 연결)

- [ ] **Step 9: 커밋**

```bash
git add build.gradle src/main/java/com/prompthub/payment/infrastructure/external/toss/TossRateLimiterConfig.java src/test/java/com/prompthub/payment/infrastructure/external/toss/TossRateLimiterConfigTest.java src/main/resources/application-local.yml src/test/resources/application-test.yml ../config/src/main/resources/configs/payment-service.yml
git commit -m "$(cat <<'EOF'
feat: Toss 결제 승인 RateLimiter 빈 및 설정값 추가

- confirm 전용 RateLimiter 인스턴스(tossConfirmRateLimiter) 생성
- CircuitBreaker가 RateLimiter 거절을 실패율에 반영하지 않도록 ignore-exceptions에 RequestNotPermitted 추가
- local/test/config-server 설정 파일에 초당 호출 상한(30)/즉시거절(timeout 0ms) 명시(잠정치, 모니터링 후 재조정 필요)

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: TossPaymentGateway 통합 — confirm()에 RateLimiter 적용, PG_RATE_LIMITED

**Files:**
- Modify: `src/main/java/com/prompthub/payment/application/exception/PaymentErrorCode.java`
- Modify: `src/main/java/com/prompthub/payment/infrastructure/external/toss/TossPaymentGateway.java`
- Modify: `src/test/java/com/prompthub/payment/infrastructure/external/toss/TossPaymentGatewayTest.java`
- Modify: `src/test/java/com/prompthub/payment/infrastructure/external/toss/TossPaymentGatewayCircuitBreakerTest.java`
- Modify: `src/test/java/com/prompthub/payment/infrastructure/external/toss/TossPaymentGatewayBulkheadTest.java`
- Create: `src/test/java/com/prompthub/payment/infrastructure/external/toss/TossPaymentGatewayRateLimiterTest.java`

**Interfaces:**
- Consumes: `RateLimiter` 빈 `"tossConfirmRateLimiter"`(Task 1)
- Produces: `TossPaymentGateway(String, String, ObjectMapper, CircuitBreaker, CircuitBreaker, Bulkhead, RateLimiter)` 생성자, `PaymentErrorCode.PG_RATE_LIMITED`

- [ ] **Step 1: PaymentErrorCode에 PG_RATE_LIMITED 추가**

`src/main/java/com/prompthub/payment/application/exception/PaymentErrorCode.java`의 `PG_BUSY` 다음 줄에 추가(세미콜론을 `PG_RATE_LIMITED` 뒤로 이동):

```java
    PG_BUSY(HttpStatus.SERVICE_UNAVAILABLE, "PAY013", "결제 승인 요청이 많아 일시적으로 처리할 수 없습니다. 잠시 후 다시 시도해주세요."),
    PG_RATE_LIMITED(HttpStatus.SERVICE_UNAVAILABLE, "PAY014", "결제 승인 요청이 많아 일시적으로 제한되었습니다. 잠시 후 다시 시도해주세요.");
```

- [ ] **Step 2: 기존 테스트 생성자 호출부 수정 — TossPaymentGatewayTest**

`src/test/java/com/prompthub/payment/infrastructure/external/toss/TossPaymentGatewayTest.java`의 생성자 호출부 교체:

```java
        TossPaymentGateway gateway = new TossPaymentGateway(
            "test-secret-key", baseUrl, objectMapper,
            CircuitBreaker.ofDefaults("test-confirm"),
            CircuitBreaker.ofDefaults("test-refund"),
            Bulkhead.ofDefaults("test-confirm-bulkhead"),
            RateLimiter.ofDefaults("test-confirm-rate-limiter")
        );
```

파일 상단 import에 추가:

```java
import io.github.resilience4j.ratelimiter.RateLimiter;
```

- [ ] **Step 3: 기존 테스트 생성자 호출부 수정 — TossPaymentGatewayCircuitBreakerTest**

`src/test/java/com/prompthub/payment/infrastructure/external/toss/TossPaymentGatewayCircuitBreakerTest.java`의 생성자 호출부 교체:

```java
        TossPaymentGateway gateway = new TossPaymentGateway(
            "test-secret-key", baseUrl, objectMapper,
            confirmCircuitBreaker,
            CircuitBreaker.ofDefaults("test-refund"),
            Bulkhead.ofDefaults("test-confirm-bulkhead"),
            RateLimiter.ofDefaults("test-confirm-rate-limiter")
        );
```

파일 상단 import에 추가:

```java
import io.github.resilience4j.ratelimiter.RateLimiter;
```

- [ ] **Step 4: 기존 테스트 생성자 호출부 수정 — TossPaymentGatewayBulkheadTest**

`src/test/java/com/prompthub/payment/infrastructure/external/toss/TossPaymentGatewayBulkheadTest.java`의 생성자 호출부 교체:

```java
        TossPaymentGateway gateway = new TossPaymentGateway(
            "test-secret-key", baseUrl, objectMapper,
            CircuitBreaker.ofDefaults("test-confirm"),
            CircuitBreaker.ofDefaults("test-refund"),
            confirmBulkhead,
            RateLimiter.ofDefaults("test-confirm-rate-limiter")
        );
```

파일 상단 import에 추가:

```java
import io.github.resilience4j.ratelimiter.RateLimiter;
```

- [ ] **Step 5: 신규 실패 테스트 작성 — 유량 상한 초과 시 즉시 PG_RATE_LIMITED**

```java
package com.prompthub.payment.infrastructure.external.toss;

import com.prompthub.payment.application.exception.PaymentErrorCode;
import com.prompthub.payment.application.gateway.external.ConfirmResult;
import com.prompthub.payment.application.gateway.external.PaymentGatewayException;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TossPaymentGatewayRateLimiterTest {

    private HttpServer server;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/v1/payments/confirm", exchange -> {
            String body = """
                {"paymentKey":"pg-key","orderId":"order-id","method":"카드","totalAmount":1000,\
                "approvedAt":"2026-07-13T10:00:00+09:00","requestedAt":"2026-07-13T09:59:00+09:00","status":"DONE"}
                """;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void confirm_유량_상한을_초과하면_초과분은_즉시_PG_RATE_LIMITED를_던진다() {
        String baseUrl = "http://localhost:" + server.getAddress().getPort() + "/v1";
        RateLimiter confirmRateLimiter = RateLimiter.of(
            "test-confirm-rate-limiter",
            RateLimiterConfig.custom()
                .limitForPeriod(1)
                .limitRefreshPeriod(Duration.ofSeconds(10))
                .timeoutDuration(Duration.ZERO)
                .build()
        );
        TossPaymentGateway gateway = new TossPaymentGateway(
            "test-secret-key", baseUrl, objectMapper,
            CircuitBreaker.ofDefaults("test-confirm"),
            CircuitBreaker.ofDefaults("test-refund"),
            Bulkhead.ofDefaults("test-confirm-bulkhead"),
            confirmRateLimiter
        );

        ConfirmResult first = gateway.confirm("pg-key-1", UUID.randomUUID(), 1_000);

        assertThatThrownBy(() -> gateway.confirm("pg-key-2", UUID.randomUUID(), 1_000))
            .isInstanceOf(PaymentGatewayException.class)
            .extracting(exception -> ((PaymentGatewayException) exception).getErrorCode())
            .isEqualTo(PaymentErrorCode.PG_RATE_LIMITED);

        assertThat(first).isNotNull();
    }
}
```

- [ ] **Step 6: 테스트 실행해서 실패 확인**

Run: `../gradlew :payment-service:test --tests "com.prompthub.payment.infrastructure.external.toss.TossPaymentGatewayRateLimiterTest" --tests "com.prompthub.payment.infrastructure.external.toss.TossPaymentGatewayTest" --tests "com.prompthub.payment.infrastructure.external.toss.TossPaymentGatewayCircuitBreakerTest" --tests "com.prompthub.payment.infrastructure.external.toss.TossPaymentGatewayBulkheadTest"`
Expected: FAIL — `TossPaymentGateway` 생성자가 아직 6-arg라 컴파일 에러

- [ ] **Step 7: TossPaymentGateway 구현 — RateLimiter 통합**

`src/main/java/com/prompthub/payment/infrastructure/external/toss/TossPaymentGateway.java` 전체를 아래로 교체:

```java
package com.prompthub.payment.infrastructure.external.toss;

import com.prompthub.payment.application.exception.PaymentErrorCode;
import com.prompthub.payment.application.gateway.external.PaymentGateway;
import com.prompthub.payment.application.gateway.external.PaymentGatewayException;
import com.prompthub.payment.application.gateway.external.ConfirmResult;
import com.prompthub.payment.application.gateway.external.RefundResult;
import com.prompthub.payment.infrastructure.external.toss.dto.TossConfirmRequest;
import com.prompthub.payment.infrastructure.external.toss.dto.TossConfirmResponse;
import com.prompthub.payment.infrastructure.external.toss.dto.TossErrorResponse;
import com.prompthub.payment.infrastructure.external.toss.dto.TossRefundRequest;
import com.prompthub.payment.infrastructure.external.toss.dto.TossRefundResponse;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
public class TossPaymentGateway implements PaymentGateway {

    // 우리 서버가 잘못된 요청을 전송한 경우 — PG_INVALID_REQUEST(502)로 분류
    private static final Set<String> TOSS_SERVER_ERROR_CODES = Set.of(
        "INVALID_REQUEST",
        "INVALID_API_KEY",
        "UNAUTHORIZED_KEY",
        "FORBIDDEN_REQUEST",
        "NOT_FOUND_PAYMENT",
        "NOT_FOUND_PAYMENT_SESSION",
        "ALREADY_PROCESSED_PAYMENT"
    );

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final CircuitBreaker confirmCircuitBreaker;
    private final CircuitBreaker refundCircuitBreaker;
    private final Bulkhead confirmBulkhead;
    private final RateLimiter confirmRateLimiter;

    public TossPaymentGateway(
        @Value("${payment.toss.secret-key}") String secretKey,
        @Value("${payment.toss.base-url:https://api.tosspayments.com/v1}") String baseUrl,
        ObjectMapper objectMapper,
        @Qualifier("tossConfirmCircuitBreaker") CircuitBreaker confirmCircuitBreaker,
        @Qualifier("tossRefundCircuitBreaker") CircuitBreaker refundCircuitBreaker,
        @Qualifier("tossConfirmBulkhead") Bulkhead confirmBulkhead,
        @Qualifier("tossConfirmRateLimiter") RateLimiter confirmRateLimiter
    ) {
        String credentials = Base64.getEncoder()
            .encodeToString((secretKey + ":").getBytes(StandardCharsets.UTF_8));
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(60));
        this.restClient = RestClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader("Authorization", "Basic " + credentials)
            .requestFactory(factory)
            .build();
        this.objectMapper = objectMapper;
        this.confirmCircuitBreaker = confirmCircuitBreaker;
        this.refundCircuitBreaker = refundCircuitBreaker;
        this.confirmBulkhead = confirmBulkhead;
        this.confirmRateLimiter = confirmRateLimiter;
    }

    @Override
    public ConfirmResult confirm(String paymentKey, UUID orderId, int amount) {
        return executeConfirm(() -> {
            TossConfirmRequest request = new TossConfirmRequest(paymentKey, orderId.toString(), amount);
            String requestJson = toJson(request);

            TossConfirmResponse response = restClient.post()
                .uri("/payments/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (req, resp) -> {
                    TossErrorResponse error = parseError(resp);
                    PaymentErrorCode errorCode = TOSS_SERVER_ERROR_CODES.contains(error.code())
                        ? PaymentErrorCode.PG_INVALID_REQUEST
                        : PaymentErrorCode.PAYMENT_FAILED;
                    throw new PaymentGatewayException(
                        errorCode, error.code(), error.message(), requestJson, null
                    );
                })
                .onStatus(HttpStatusCode::is5xxServerError, (req, resp) -> {
                    TossErrorResponse error = parseError(resp);
                    throw new PaymentGatewayException(
                        PaymentErrorCode.PG_SERVER_ERROR,
                        error.code(), error.message(),
                        requestJson, null
                    );
                })
                .body(TossConfirmResponse.class);

            if (response == null) {
                throw new PaymentGatewayException(
                    PaymentErrorCode.PG_INVALID_REQUEST, "NULL_RESPONSE", "PG사 응답이 없습니다.", requestJson, null
                );
            }

            return new ConfirmResult(
                response.method(),
                response.totalAmount(),
                requestJson,
                toJson(response),
                response.approvedAt()
            );
        });
    }

    @Override
    public RefundResult refund(String paymentKey, UUID refundId, int amount) {
        return execute(refundCircuitBreaker, () -> {
            TossRefundRequest request = new TossRefundRequest("구매자 환불 요청", amount);

            TossRefundResponse response = restClient.post()
                .uri("/payments/{paymentKey}/cancel", paymentKey)
                .header("Idempotency-Key", "refund-" + refundId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (req, resp) -> {
                    TossErrorResponse error = parseError(resp);
                    PaymentErrorCode errorCode = TOSS_SERVER_ERROR_CODES.contains(error.code())
                        ? PaymentErrorCode.PG_INVALID_REQUEST
                        : PaymentErrorCode.PAYMENT_FAILED;
                    throw new PaymentGatewayException(
                        errorCode, error.code(), error.message(), null, null
                    );
                })
                .onStatus(HttpStatusCode::is5xxServerError, (req, resp) -> {
                    TossErrorResponse error = parseError(resp);
                    throw new PaymentGatewayException(
                        PaymentErrorCode.PG_SERVER_ERROR,
                        error.code(), error.message(),
                        null, null
                    );
                })
                .body(TossRefundResponse.class);

            if (response == null) {
                throw new PaymentGatewayException(
                    PaymentErrorCode.PG_SERVER_ERROR, "NULL_RESPONSE", "PG사 환불 응답이 없습니다.", null, null
                );
            }
            List<TossRefundResponse.TossCancel> cancels = response.cancels();
            if (cancels == null || cancels.isEmpty()) {
                throw new PaymentGatewayException(
                    PaymentErrorCode.PG_SERVER_ERROR, "NO_CANCEL_DATA", "Toss 환불 응답에 취소 내역이 없습니다.", null, null
                );
            }
            TossRefundResponse.TossCancel lastCancel = cancels.get(cancels.size() - 1);
            return new RefundResult(lastCancel.canceledAt());
        });
    }

    private <T> T execute(CircuitBreaker circuitBreaker, Supplier<T> supplier) {
        try {
            return CircuitBreaker.decorateSupplier(circuitBreaker, supplier).get();
        } catch (CallNotPermittedException exception) {
            log.warn("Toss 서킷브레이커 OPEN — 호출 차단됨. circuitBreaker={}", circuitBreaker.getName());
            throw new PaymentGatewayException(
                PaymentErrorCode.PG_UNAVAILABLE, "CIRCUIT_OPEN",
                "PG사 서킷브레이커가 열려 있어 호출을 차단했습니다.", null, null
            );
        }
    }

    private <T> T executeConfirm(Supplier<T> supplier) {
        try {
            Supplier<T> bulkheadDecorated = Bulkhead.decorateSupplier(confirmBulkhead, supplier);
            Supplier<T> rateLimiterDecorated = RateLimiter.decorateSupplier(confirmRateLimiter, bulkheadDecorated);
            return CircuitBreaker.decorateSupplier(confirmCircuitBreaker, rateLimiterDecorated).get();
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

    private String toJson(Object obj) {
        return objectMapper.writeValueAsString(obj);
    }

    private TossErrorResponse parseError(org.springframework.http.client.ClientHttpResponse resp) {
        try {
            String body = new String(resp.getBody().readAllBytes(), StandardCharsets.UTF_8);
            tools.jackson.databind.JsonNode node = objectMapper.readTree(body);
            String code = node.path("code").asText("UNKNOWN");
            tools.jackson.databind.JsonNode messageNode = node.path("message");
            String message = messageNode.isObject() ? messageNode.toString() : messageNode.asText("PG사 오류");
            return new TossErrorResponse(code, message);
        } catch (IOException e) {
            log.warn("Toss 에러 응답 파싱 실패 — cause={}", e.getMessage(), e);
            return new TossErrorResponse("UNKNOWN", "PG사 응답 파싱 실패");
        }
    }
}
```

- [ ] **Step 8: 테스트 실행해서 통과 확인**

Run: `../gradlew :payment-service:test --tests "com.prompthub.payment.infrastructure.external.toss.TossPaymentGatewayRateLimiterTest" --tests "com.prompthub.payment.infrastructure.external.toss.TossPaymentGatewayTest" --tests "com.prompthub.payment.infrastructure.external.toss.TossPaymentGatewayCircuitBreakerTest" --tests "com.prompthub.payment.infrastructure.external.toss.TossPaymentGatewayBulkheadTest"`
Expected: PASS

- [ ] **Step 9: 전체 테스트 스위트 실행 — 회귀 확인**

Run: `JAVA_HOME=~/.asdf/installs/java/temurin-21.0.5+11.0.LTS ../gradlew :payment-service:test`
Expected: BUILD SUCCESSFUL — `ConfirmPaymentIntegrationTest`는 `@MockitoBean PaymentGateway`라 RateLimiter 로직과 무관하게 그대로 통과

- [ ] **Step 10: 커밋**

```bash
git add src/main/java/com/prompthub/payment/application/exception/PaymentErrorCode.java src/main/java/com/prompthub/payment/infrastructure/external/toss/TossPaymentGateway.java src/test/java/com/prompthub/payment/infrastructure/external/toss/TossPaymentGatewayTest.java src/test/java/com/prompthub/payment/infrastructure/external/toss/TossPaymentGatewayCircuitBreakerTest.java src/test/java/com/prompthub/payment/infrastructure/external/toss/TossPaymentGatewayBulkheadTest.java src/test/java/com/prompthub/payment/infrastructure/external/toss/TossPaymentGatewayRateLimiterTest.java
git commit -m "$(cat <<'EOF'
feat: TossPaymentGateway confirm()에 RateLimiter 적용

- CircuitBreaker(바깥) → RateLimiter(중간) → Bulkhead(안쪽) 순서로 결합
- 유량 상한 초과 시 신규 에러코드 PG_RATE_LIMITED(503)로 즉시 반환
- refund()는 순차 처리(컨슈머 concurrency=1)로 자연 처리량이 낮아 범위 제외

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Self-Review 결과

- **spec 커버리지**: 설계 문서(`491-toss-payments-rate-limiting.md`)의 확정 사항 — resilience4j-ratelimiter 채택(Task1 전체 구조), 로컬 상태로 충분(replicas=1 전제, Global Constraints/설계 문서 참고), CB→RateLimiter→Bulkhead 조합 순서(Task2 `executeConfirm`), CB ignore-exceptions에 `RequestNotPermitted` 추가(Task1 yml), 신규 에러코드 `PG_RATE_LIMITED`(Task2), 설정값 `limit-for-period=30`/`limit-refresh-period=1s`/`timeout-duration=0ms`(Task1 yml), refund 제외 및 재검토 트리거(Global Constraints·설계 문서) — 전부 태스크로 매핑됨.
- **placeholder 스캔**: TBD/TODO/"적절한 에러 처리" 등 없음.
- **타입 일관성**: `RateLimiter` 빈 이름 `"tossConfirmRateLimiter"`가 Task1 생성부(`@Bean("tossConfirmRateLimiter")`)와 Task2 `@Qualifier("tossConfirmRateLimiter")` 소비부에서 동일하게 일치. `PaymentErrorCode.PG_RATE_LIMITED`가 Task2 내에서 정의와 사용 모두 동일 이름 사용. `TossPaymentGateway` 생성자 7-arg 시그니처가 Task2의 모든 테스트 호출부(4곳: Test/CircuitBreakerTest/BulkheadTest/RateLimiterTest)에서 동일하게 일치.
- **범위 확인**: 단일 서브시스템(Toss confirm RateLimiter)으로 응집돼 있어 추가 분해 불필요.
