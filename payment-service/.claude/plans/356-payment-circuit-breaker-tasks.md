# Toss 결제 서킷브레이커 구현 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `TossPaymentGateway`의 confirm/refund 호출에 Resilience4j CircuitBreaker를 적용해, Toss 장애·지연 시 스레드 고갈 없이 빠르게 실패 처리(fail-fast)한다.

**Architecture:** `infrastructure/external/toss` 패키지에 `TossFailurePredicate`(실패 판정)와 `TossCircuitBreakerConfig`(CircuitBreaker 빈 2개 + 메트릭)를 추가하고, `TossPaymentGateway`가 이 두 `CircuitBreaker`를 생성자로 주입받아 confirm/refund 호출을 감싼다. OPEN 상태에서는 실제 HTTP 호출 없이 신규 에러코드 `PG_UNAVAILABLE`(503)을 던진다.

**Tech Stack:** Java 21, Spring Boot 4.1, `io.github.resilience4j:resilience4j-circuitbreaker:2.4.0`, `io.github.resilience4j:resilience4j-micrometer:2.4.0`, JUnit 5, AssertJ, `com.sun.net.httpserver.HttpServer`(기존 테스트 방식).

## Global Constraints

- 테스트 메서드명은 한국어로 작성한다(CLAUDE.md 언어 정책).
- 단언은 AssertJ(`assertThat`, `assertThatThrownBy`)만 사용한다.
- 신규 의존성은 `payment-service/build.gradle`에만 추가한다(루트 `build.gradle`에 resilience4j 없음 — 중복 확인 완료).
- 커밋 메시지는 `type: 한국어 설명` 형식, AI 협업 트레일러 `Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>` 포함(git-conventions.md).
- 브랜치는 이미 존재(`feat/#356-payment-circuit-breaker`) — 새로 만들지 않는다.
- 설계 근거는 `.claude/plans/356-payment-circuit-breaker.md` 참조.

---

### Task 1: TossFailurePredicate — 실패 판정 로직

**Files:**
- Create: `src/main/java/com/prompthub/payment/infrastructure/external/toss/TossFailurePredicate.java`
- Test: `src/test/java/com/prompthub/payment/infrastructure/external/toss/TossFailurePredicateTest.java`

**Interfaces:**
- Consumes: 없음(기존 `PaymentGatewayException`, `PaymentErrorCode`만 참조)
- Produces: `TossFailurePredicate implements Predicate<Throwable>` — Task 2의 `CircuitBreakerConfig.recordException(...)`에서 사용

- [x] **Step 1: 실패 테스트 작성**

```java
package com.prompthub.payment.infrastructure.external.toss;

import com.prompthub.payment.application.exception.PaymentErrorCode;
import com.prompthub.payment.application.gateway.external.PaymentGatewayException;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import static org.assertj.core.api.Assertions.assertThat;

class TossFailurePredicateTest {

    private final TossFailurePredicate predicate = new TossFailurePredicate();

    @Test
    void 네트워크_타임아웃_예외는_실패로_판정한다() {
        ResourceAccessException exception = new ResourceAccessException("connect timed out");

        assertThat(predicate.test(exception)).isTrue();
    }

    @Test
    void Toss_5xx_서버_오류는_실패로_판정한다() {
        PaymentGatewayException exception = new PaymentGatewayException(
            PaymentErrorCode.PG_SERVER_ERROR, "INTERNAL_SERVER_ERROR", "Toss 서버 오류", null, null
        );

        assertThat(predicate.test(exception)).isTrue();
    }

    @Test
    void 우리_쪽_요청_오류는_실패로_판정하지_않는다() {
        PaymentGatewayException exception = new PaymentGatewayException(
            PaymentErrorCode.PG_INVALID_REQUEST, "INVALID_REQUEST", "잘못된 요청", null, null
        );

        assertThat(predicate.test(exception)).isFalse();
    }

    @Test
    void 정상적인_결제_거절은_실패로_판정하지_않는다() {
        PaymentGatewayException exception = new PaymentGatewayException(
            PaymentErrorCode.PAYMENT_FAILED, "EXCEED_MAX_DAILY_PAYMENT_COUNT", "한도 초과", null, null
        );

        assertThat(predicate.test(exception)).isFalse();
    }
}
```

- [x] **Step 2: 테스트 실행해서 실패 확인**

Run: `../gradlew :payment-service:test --tests "com.prompthub.payment.infrastructure.external.toss.TossFailurePredicateTest"`
Expected: FAIL — `TossFailurePredicate` 클래스가 없어 컴파일 에러

- [x] **Step 3: 최소 구현**

```java
package com.prompthub.payment.infrastructure.external.toss;

import com.prompthub.payment.application.exception.PaymentErrorCode;
import com.prompthub.payment.application.gateway.external.PaymentGatewayException;
import java.util.Set;
import java.util.function.Predicate;
import org.springframework.web.client.ResourceAccessException;

public class TossFailurePredicate implements Predicate<Throwable> {

    private static final Set<PaymentErrorCode> SYSTEM_FAILURE_CODES = Set.of(
        PaymentErrorCode.PG_SERVER_ERROR
    );

    @Override
    public boolean test(Throwable throwable) {
        if (throwable instanceof ResourceAccessException) {
            return true;
        }
        return throwable instanceof PaymentGatewayException exception
            && SYSTEM_FAILURE_CODES.contains(exception.getErrorCode());
    }
}
```

- [x] **Step 4: 테스트 실행해서 통과 확인**

Run: `../gradlew :payment-service:test --tests "com.prompthub.payment.infrastructure.external.toss.TossFailurePredicateTest"`
Expected: PASS (4 tests)

- [x] **Step 5: 커밋**

```bash
git add src/main/java/com/prompthub/payment/infrastructure/external/toss/TossFailurePredicate.java src/test/java/com/prompthub/payment/infrastructure/external/toss/TossFailurePredicateTest.java
git commit -m "$(cat <<'EOF'
feat: Toss 서킷브레이커 실패 판정 Predicate 추가

- 네트워크 오류·Toss 5xx는 실패로 기록, 우리 쪽 요청 오류·정상 결제 거절은 제외

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: TossCircuitBreakerConfig — CircuitBreaker 빈 + 설정값

**Files:**
- Modify: `build.gradle:3-18` (dependencies 블록에 resilience4j 추가)
- Create: `src/main/java/com/prompthub/payment/infrastructure/external/toss/TossCircuitBreakerConfig.java`
- Test: `src/test/java/com/prompthub/payment/infrastructure/external/toss/TossCircuitBreakerConfigTest.java`
- Modify: `src/main/resources/application-local.yml` (resilience4j 섹션 추가)
- Modify: `src/test/resources/application-test.yml` (resilience4j 섹션 추가)
- Modify: `../config/src/main/resources/configs/payment-service.yml` (resilience4j 섹션 추가)

**Interfaces:**
- Consumes: `TossFailurePredicate`(Task 1)
- Produces: `TossCircuitBreakerConfig`의 빈 3개 — `CircuitBreakerRegistry tossCircuitBreakerRegistry(...)`, `CircuitBreaker tossConfirmCircuitBreaker(CircuitBreakerRegistry)`(빈 이름 `"tossConfirmCircuitBreaker"`), `CircuitBreaker tossRefundCircuitBreaker(CircuitBreakerRegistry)`(빈 이름 `"tossRefundCircuitBreaker"`) — Task 3의 `TossPaymentGateway` 생성자가 `@Qualifier`로 주입받아 사용

- [x] **Step 1: build.gradle에 의존성 추가**

`build.gradle`의 `dependencies` 블록 맨 앞에 추가:

```groovy
dependencies {
    // Resilience4j - Toss REST 호출 장애 격리
    implementation 'io.github.resilience4j:resilience4j-circuitbreaker:2.4.0'
    implementation 'io.github.resilience4j:resilience4j-micrometer:2.4.0'

    // swagger (api-docs JSON 엔드포인트만 노출 — UI는 Gateway에서 집계)
    implementation "org.springdoc:springdoc-openapi-starter-webmvc-api:${springdocVersion}"
    ...(이하 기존 내용 동일, 변경 없음)
```

- [x] **Step 2: 빌드 확인**

Run: `../gradlew :payment-service:compileJava`
Expected: BUILD SUCCESSFUL (새 의존성이 정상적으로 resolve됨)

- [x] **Step 3: 실패 테스트 작성**

```java
package com.prompthub.payment.infrastructure.external.toss;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TossCircuitBreakerConfigTest {

    private final TossCircuitBreakerConfig config = new TossCircuitBreakerConfig();

    @Test
    void 설정값대로_confirm과_refund_CircuitBreaker를_각각_생성한다() {
        CircuitBreakerRegistry registry = config.tossCircuitBreakerRegistry(
            20, 10, 50f, Duration.ofMillis(20_000), 50f, Duration.ofSeconds(30), 3
        );

        CircuitBreaker confirmCircuitBreaker = config.tossConfirmCircuitBreaker(registry);
        CircuitBreaker refundCircuitBreaker = config.tossRefundCircuitBreaker(registry);

        assertThat(confirmCircuitBreaker.getName()).isEqualTo("tossConfirmCircuitBreaker");
        assertThat(refundCircuitBreaker.getName()).isEqualTo("tossRefundCircuitBreaker");
        assertThat(confirmCircuitBreaker).isNotSameAs(refundCircuitBreaker);

        assertThat(confirmCircuitBreaker.getCircuitBreakerConfig().getSlidingWindowSize()).isEqualTo(20);
        assertThat(confirmCircuitBreaker.getCircuitBreakerConfig().getMinimumNumberOfCalls()).isEqualTo(10);
        assertThat(confirmCircuitBreaker.getCircuitBreakerConfig().getFailureRateThreshold()).isEqualTo(50f);
        assertThat(confirmCircuitBreaker.getCircuitBreakerConfig().getSlowCallDurationThreshold())
            .isEqualTo(Duration.ofMillis(20_000));
        assertThat(confirmCircuitBreaker.getCircuitBreakerConfig().getWaitIntervalFunctionInOpenState().apply(1))
            .isEqualTo(30_000L);
        assertThat(confirmCircuitBreaker.getCircuitBreakerConfig().getPermittedNumberOfCallsInHalfOpenState())
            .isEqualTo(3);
    }
}
```

- [x] **Step 4: 테스트 실행해서 실패 확인**

Run: `../gradlew :payment-service:test --tests "com.prompthub.payment.infrastructure.external.toss.TossCircuitBreakerConfigTest"`
Expected: FAIL — `TossCircuitBreakerConfig` 클래스가 없어 컴파일 에러

- [x] **Step 5: 최소 구현**

```java
package com.prompthub.payment.infrastructure.external.toss;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TossCircuitBreakerConfig {

    @Bean
    public CircuitBreakerRegistry tossCircuitBreakerRegistry(
        @Value("${resilience4j.circuitbreaker.configs.toss-payment-default.sliding-window-size}")
        int slidingWindowSize,
        @Value("${resilience4j.circuitbreaker.configs.toss-payment-default.minimum-number-of-calls}")
        int minimumNumberOfCalls,
        @Value("${resilience4j.circuitbreaker.configs.toss-payment-default.failure-rate-threshold}")
        float failureRateThreshold,
        @Value("${resilience4j.circuitbreaker.configs.toss-payment-default.slow-call-duration-threshold}")
        Duration slowCallDurationThreshold,
        @Value("${resilience4j.circuitbreaker.configs.toss-payment-default.slow-call-rate-threshold}")
        float slowCallRateThreshold,
        @Value("${resilience4j.circuitbreaker.configs.toss-payment-default.wait-duration-in-open-state}")
        Duration waitDurationInOpenState,
        @Value(
            "${resilience4j.circuitbreaker.configs.toss-payment-default.permitted-number-of-calls-in-half-open-state}"
        )
        int permittedNumberOfCallsInHalfOpenState
    ) {
        CircuitBreakerConfig circuitBreakerConfig = CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(slidingWindowSize)
            .minimumNumberOfCalls(minimumNumberOfCalls)
            .failureRateThreshold(failureRateThreshold)
            .slowCallDurationThreshold(slowCallDurationThreshold)
            .slowCallRateThreshold(slowCallRateThreshold)
            .waitDurationInOpenState(waitDurationInOpenState)
            .permittedNumberOfCallsInHalfOpenState(permittedNumberOfCallsInHalfOpenState)
            .recordException(new TossFailurePredicate())
            .build();
        return CircuitBreakerRegistry.of(circuitBreakerConfig);
    }

    @Bean("tossConfirmCircuitBreaker")
    public CircuitBreaker tossConfirmCircuitBreaker(CircuitBreakerRegistry tossCircuitBreakerRegistry) {
        return tossCircuitBreakerRegistry.circuitBreaker("tossConfirmCircuitBreaker");
    }

    @Bean("tossRefundCircuitBreaker")
    public CircuitBreaker tossRefundCircuitBreaker(CircuitBreakerRegistry tossCircuitBreakerRegistry) {
        return tossCircuitBreakerRegistry.circuitBreaker("tossRefundCircuitBreaker");
    }

    @Bean
    public MeterBinder tossCircuitBreakerMetrics(CircuitBreakerRegistry tossCircuitBreakerRegistry) {
        return TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(tossCircuitBreakerRegistry);
    }
}
```

- [x] **Step 6: 테스트 실행해서 통과 확인**

Run: `../gradlew :payment-service:test --tests "com.prompthub.payment.infrastructure.external.toss.TossCircuitBreakerConfigTest"`
Expected: PASS

- [x] **Step 7: yml 3곳에 설정값 명시**

`src/main/resources/application-local.yml` 끝에 추가(`eureka:` 섹션 앞):

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

```

`src/test/resources/application-test.yml` 끝에 추가:

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
```

`../config/src/main/resources/configs/payment-service.yml` 끝에 추가:

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
```

- [x] **Step 8: 전체 테스트 스위트 실행해서 기존 테스트 회귀 없는지 확인**

Run: `JAVA_HOME=~/.asdf/installs/java/temurin-21.0.5+11.0.LTS ../gradlew :payment-service:test`
Expected: BUILD SUCCESSFUL (기존 통합 테스트가 `TossCircuitBreakerConfig` 빈을 추가로 로드하지만, 이 시점엔 아직 `TossPaymentGateway`가 이 빈들을 사용하지 않으므로 영향 없음 — 다음 Task에서 실제 연결)

- [x] **Step 9: 커밋**

```bash
git add build.gradle src/main/java/com/prompthub/payment/infrastructure/external/toss/TossCircuitBreakerConfig.java src/test/java/com/prompthub/payment/infrastructure/external/toss/TossCircuitBreakerConfigTest.java src/main/resources/application-local.yml src/test/resources/application-test.yml ../config/src/main/resources/configs/payment-service.yml
git commit -m "$(cat <<'EOF'
feat: Toss 서킷브레이커 CircuitBreaker 빈 및 설정값 추가

- resilience4j-circuitbreaker/micrometer 의존성 추가
- confirm/refund 전용 CircuitBreaker 인스턴스 분리 생성
- local/test/config-server 설정 파일에 임계값 명시(잠정치, 모니터링 후 재조정 필요)

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: TossPaymentGateway 통합 — CircuitBreaker 적용, readTimeout 변경, PG_UNAVAILABLE

**Files:**
- Modify: `src/main/java/com/prompthub/payment/application/exception/PaymentErrorCode.java`
- Modify: `src/main/java/com/prompthub/payment/infrastructure/external/toss/TossPaymentGateway.java`
- Modify: `src/test/java/com/prompthub/payment/infrastructure/external/toss/TossPaymentGatewayTest.java`
- Create: `src/test/java/com/prompthub/payment/infrastructure/external/toss/TossPaymentGatewayCircuitBreakerTest.java`

**Interfaces:**
- Consumes: `CircuitBreaker` 빈 `"tossConfirmCircuitBreaker"`/`"tossRefundCircuitBreaker"`(Task 2)
- Produces: `TossPaymentGateway(String, String, ObjectMapper, CircuitBreaker, CircuitBreaker)` 생성자, `PaymentErrorCode.PG_UNAVAILABLE`

- [ ] **Step 1: PaymentErrorCode에 PG_UNAVAILABLE 추가**

`src/main/java/com/prompthub/payment/application/exception/PaymentErrorCode.java`의 `NOT_ORDER_OWNER` 다음 줄에 추가:

```java
    NOT_ORDER_OWNER(HttpStatus.FORBIDDEN, "PAY010", "본인 주문만 결제할 수 있습니다."),
    PG_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "PAY011", "PG사 서비스에 일시적으로 연결할 수 없습니다.");
```

(기존 마지막 항목의 세미콜론 위치가 `NOT_ORDER_OWNER` 뒤에서 `PG_UNAVAILABLE` 뒤로 이동)

- [ ] **Step 2: 실패 테스트 작성 — 기존 TossPaymentGatewayTest 생성자 호출 수정**

`src/test/java/com/prompthub/payment/infrastructure/external/toss/TossPaymentGatewayTest.java`의 `refund_호출_시...` 테스트에서 생성자 호출부 교체:

```java
        String baseUrl = "http://localhost:" + server.getAddress().getPort() + "/v1";
        TossPaymentGateway gateway = new TossPaymentGateway(
            "test-secret-key", baseUrl, objectMapper,
            CircuitBreaker.ofDefaults("test-confirm"),
            CircuitBreaker.ofDefaults("test-refund")
        );
```

파일 상단 import에 추가:

```java
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
```

- [ ] **Step 3: 신규 실패 테스트 작성 — CircuitBreaker OPEN 전환 및 즉시 실패**

```java
package com.prompthub.payment.infrastructure.external.toss;

import com.prompthub.payment.application.exception.PaymentErrorCode;
import com.prompthub.payment.application.gateway.external.PaymentGatewayException;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TossPaymentGatewayCircuitBreakerTest {

    private HttpServer server;
    private final AtomicInteger callCount = new AtomicInteger();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
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
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void confirm_반복_5xx_실패로_서킷브레이커가_OPEN되면_이후_호출은_즉시_PG_UNAVAILABLE을_던진다() {
        String baseUrl = "http://localhost:" + server.getAddress().getPort() + "/v1";
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
            .slidingWindowSize(2)
            .minimumNumberOfCalls(2)
            .failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofSeconds(30))
            .recordException(new TossFailurePredicate())
            .build();
        CircuitBreaker confirmCircuitBreaker = CircuitBreaker.of("test-confirm", config);
        TossPaymentGateway gateway = new TossPaymentGateway(
            "test-secret-key", baseUrl, objectMapper,
            confirmCircuitBreaker,
            CircuitBreaker.ofDefaults("test-refund")
        );
        UUID orderId = UUID.randomUUID();

        assertThatThrownBy(() -> gateway.confirm("pg-key", orderId, 1_000))
            .isInstanceOf(PaymentGatewayException.class);
        assertThatThrownBy(() -> gateway.confirm("pg-key", orderId, 1_000))
            .isInstanceOf(PaymentGatewayException.class);

        assertThat(confirmCircuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        int callCountBeforeOpenState = callCount.get();

        assertThatThrownBy(() -> gateway.confirm("pg-key", orderId, 1_000))
            .isInstanceOf(PaymentGatewayException.class)
            .extracting(exception -> ((PaymentGatewayException) exception).getErrorCode())
            .isEqualTo(PaymentErrorCode.PG_UNAVAILABLE);

        assertThat(callCount.get()).isEqualTo(callCountBeforeOpenState);
    }
}
```

- [ ] **Step 4: 테스트 실행해서 실패 확인**

Run: `../gradlew :payment-service:test --tests "com.prompthub.payment.infrastructure.external.toss.TossPaymentGatewayCircuitBreakerTest" --tests "com.prompthub.payment.infrastructure.external.toss.TossPaymentGatewayTest"`
Expected: FAIL — `TossPaymentGateway` 생성자가 아직 3-arg라 컴파일 에러

- [ ] **Step 5: TossPaymentGateway 구현 — CircuitBreaker 통합**

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
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
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

    public TossPaymentGateway(
        @Value("${payment.toss.secret-key}") String secretKey,
        @Value("${payment.toss.base-url:https://api.tosspayments.com/v1}") String baseUrl,
        ObjectMapper objectMapper,
        @Qualifier("tossConfirmCircuitBreaker") CircuitBreaker confirmCircuitBreaker,
        @Qualifier("tossRefundCircuitBreaker") CircuitBreaker refundCircuitBreaker
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
    }

    @Override
    public ConfirmResult confirm(String paymentKey, UUID orderId, int amount) {
        return execute(confirmCircuitBreaker, () -> {
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

- [ ] **Step 6: 테스트 실행해서 통과 확인**

Run: `../gradlew :payment-service:test --tests "com.prompthub.payment.infrastructure.external.toss.TossPaymentGatewayCircuitBreakerTest" --tests "com.prompthub.payment.infrastructure.external.toss.TossPaymentGatewayTest"`
Expected: PASS

- [ ] **Step 7: 전체 테스트 스위트 실행 — 회귀 확인**

Run: `JAVA_HOME=~/.asdf/installs/java/temurin-21.0.5+11.0.LTS ../gradlew :payment-service:test`
Expected: BUILD SUCCESSFUL — `ConfirmPaymentIntegrationTest`/`PartialRefundIntegrationTest`는 `@MockitoBean PaymentGateway`라 CB 로직과 무관하게 그대로 통과

- [ ] **Step 8: 커밋**

```bash
git add src/main/java/com/prompthub/payment/application/exception/PaymentErrorCode.java src/main/java/com/prompthub/payment/infrastructure/external/toss/TossPaymentGateway.java src/test/java/com/prompthub/payment/infrastructure/external/toss/TossPaymentGatewayTest.java src/test/java/com/prompthub/payment/infrastructure/external/toss/TossPaymentGatewayCircuitBreakerTest.java
git commit -m "$(cat <<'EOF'
feat: TossPaymentGateway에 서킷브레이커 적용

- confirm/refund 각각 전용 CircuitBreaker로 감싸 OPEN 시 즉시 PG_UNAVAILABLE(503) 반환
- readTimeout을 Toss 공식 권장(60초)에 맞춰 10초에서 변경

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Self-Review 결과

- **spec 커버리지**: 설계 문서(`356-payment-circuit-breaker.md`)의 확정 사항 7개 — 적용 범위(Task3), 인스턴스 분리(Task2/3), 컴포넌트 구조(Task1/2), 실패 판정(Task1), 에러코드 분리(Task3), readTimeout 변경(Task3), 설정 계층(Task2) — 전부 태스크로 매핑됨.
- **placeholder 스캔**: TBD/TODO/"적절한 에러 처리" 등 없음.
- **타입 일관성**: `TossFailurePredicate`(Task1) → `CircuitBreakerConfig.recordException(new TossFailurePredicate())`(Task2)로 그대로 사용. `tossConfirmCircuitBreaker`/`tossRefundCircuitBreaker` 빈 이름이 Task2 생성부와 Task3 `@Qualifier` 소비부에서 동일하게 일치.
