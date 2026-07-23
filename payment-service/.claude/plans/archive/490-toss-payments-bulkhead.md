# Toss 결제 승인 Bulkhead 구현 계획

`TossPaymentGateway.confirm()` 호출에 Resilience4j Semaphore Bulkhead를 적용해, Toss 지연이 payment-service의 Tomcat 스레드 풀 전체를 잠식하는 것을 막는다.

---

## 배경 및 목표

`TossPaymentGateway`는 이슈 #356에서 CircuitBreaker(`tossConfirmCircuitBreaker`/`tossRefundCircuitBreaker`)를 이미 적용했다. 당시 문서(`.claude/plans/356-payment-circuit-breaker.md`)는 "Toss 호출이 요청당 1건(fan-out 없음)이라 Bulkhead 우선순위를 낮춘다"고 판단하고 별도 이슈(#490)로 미뤘다.

이번 작업에서 그 판단을 다시 검토했다. CircuitBreaker는 최근 호출 실패율을 관찰해 "다음 호출을 걸지 말지"를 판단하는 장치이고, `minimum-number-of-calls`(10건)가 쌓이기 전까지는 Toss가 느려도 호출을 계속 통과시킨다. 이 관찰 구간 동안 동시 결제 승인 요청이 몰리면(예: 프로모션 등으로 동시 체크아웃 급증), 각 요청이 `readTimeout`(60초)까지 Tomcat worker 스레드를 하나씩 점유한다. `PaymentController`에 노출된 HTTP 엔드포인트는 `POST /api/v2/payments/confirm` 하나뿐이지만, 같은 Tomcat 스레드 풀을 `/actuator/health` 등 인프라 엔드포인트와 공유하기 때문에, 확인 요청이 스레드를 다수 점유하면 헬스체크까지 응답하지 못해 Eureka/게이트웨이가 정상 인스턴스를 장애로 오판할 수 있다. CircuitBreaker는 이 관찰 구간의 점유량 자체는 제어하지 못하므로, 별도로 동시 호출 수를 제한하는 Bulkhead가 필요하다는 결론을 냈다.

## 범위

**적용 대상은 `TossPaymentGateway.confirm()`뿐이다. `refund()`는 이번 범위에서 제외한다.** refund는 HTTP로 노출되지 않고 `infrastructure.messaging.consumer.OrderEventConsumer`(`@KafkaListener`, `order-events` 토픽)가 유일한 진입점이며, 이 리스너의 concurrency는 명시적으로 설정된 적 없어 기본값 1이다. 즉 refund 호출은 이미 컨슈머 스레드 1개로 순차 처리가 강제돼 있어, "동시 호출 수를 제한"하는 Bulkhead를 얹어도 추가로 막을 동시성이 없다. 이슈 #490 원문은 confirm/refund 모두를 언급하지만, 실제 호출 진입 구조를 확인한 결과 범위를 confirm으로 좁히는 것이 맞다고 판단했다.

## 확정 사항

**Bulkhead 방식은 Semaphore Bulkhead로 하고, Thread Pool Bulkhead는 쓰지 않는다.** `TossPaymentGateway`는 `RestClient` + `SimpleClientHttpRequestFactory`로 커넥션 풀링 없이 요청당 1커넥션을 맺는 단순 blocking 호출이라, 어떤 방식을 쓰든 "Toss 응답을 기다리는 시간" 자체는 없어지지 않는다. Semaphore는 이 대기를 요청을 받은 Tomcat worker 스레드가 그대로 수행하되 동시에 몇 개까지 허용할지만 카운터로 제한한다. Thread Pool Bulkhead를 쓰려면 별도 전용 스레드 풀에 호출을 위임하고 결과를 `CompletableFuture`로 받아야 하는데, `PaymentController`가 동기 응답을 유지하는 한(클라이언트가 승인/거절 결과를 즉시 받아야 하는 결제 확인 플로우 특성상 비동기 응답으로 바꿀 이유가 없다) 결국 어딘가에서 `future.get()`으로 다시 blocking해야 한다. 이 경우 Tomcat 스레드(get() 대기)와 Bulkhead 전용 스레드(실제 호출 수행)가 동시에 점유돼, 같은 일을 처리하는 데 스레드를 2배 쓰면서 격리 이득은 추가로 없다. Thread Pool 방식이 실제 이득을 보려면 presentation 계층까지 비동기 응답으로 전환해야 하는데 이는 이번 작업 범위를 넘는 아키텍처 변경이라 채택하지 않았다.

**`maxWaitDuration`은 0ms 즉시 거절이 아니라 200ms bounded wait로 둔다.** Toss confirm은 평시 응답이 빠르다(수백ms 내외). 순간적인 동시 체크아웃 버스트로 잠깐 상한을 넘겨도, 200ms 정도 기다리면 먼저 들어온 호출이 끝나며 permit이 반환돼 구제될 여지가 크다. 반면 Toss가 실제로 장애 상태라 호출이 초 단위로 늘어지는 상황에서는 200ms 대기가 있으나 없으나 결과가 똑같이 실패라 추가 비용이 미미하다(readTimeout 60초 대비 200ms는 노이즈 수준). 결제 승인은 실패 시 사용자 재시도 유도·CS 대응 비용이 큰 도메인이라, "정상 버스트에서의 불필요한 거절을 줄이는 이득"이 "장애 시 미세한 지연 증가라는 비용"보다 크다고 판단했다.

**`maxConcurrentCalls`는 20으로 잠정 설정한다.** payment-service 자신의 Tomcat 스레드 풀은 기본값(200개, 커스텀 설정 없음)을 그대로 쓰고 있고, 이 서비스에 다른 모노레포 서비스의 스레드 풀은 관여하지 않는다(서비스별 독립 배포·독립 JVM). 20은 전체의 10% 수준으로, 확인 요청이 몰려도 최악의 경우에도 180개 이상의 스레드가 헬스체크 등 다른 처리에 남도록 보수적으로 잡은 값이다. **실제 트래픽/피크 동시 결제 건수 데이터 없이 잡은 잠정값이므로, 배포 후 모니터링 지표를 보고 재조정이 필요하다** (356번 CircuitBreaker 설정값과 동일한 성격의 한계).

**CircuitBreaker(바깥) → Bulkhead(안쪽) 순서로 결합한다.** CircuitBreaker가 OPEN이면 이 호출은 이미 실패가 확정된 것이므로, Bulkhead의 permit(동시 호출 예산)을 소비할 이유가 없다. CB를 바깥에 두면 OPEN 상태에서 Bulkhead 안쪽 로직 자체가 호출되지 않아 permit이 온전히 보존된다. 반대로 Bulkhead를 바깥에 두면 CB가 OPEN인 호출도 일단 permit을 잡았다가 반환하는 낭비가 생긴다.

**CircuitBreaker 설정에 `ignoreExceptions`로 `BulkheadFullException`을 추가한다.** Resilience4j CircuitBreaker는 기본적으로 내부에서 던져지는 예외를 전부 실패로 기록한다. Bulkhead를 CB 안쪽에 두면 Bulkhead가 거절한 호출의 예외도 CB를 그대로 통과하며 실패로 잡혀버리는데, 이는 Toss 장애가 아니라 우리 쪽 자원 보호 동작이므로 CB의 실패율 통계에 섞이면 안 된다. 이 설정을 빠뜨리면 순간적인 동시 요청 폭주가 실제 Toss 장애처럼 집계돼 불필요하게 CB를 OPEN시킬 수 있다.

**Bulkhead 포화 시 에러 코드는 기존 `PG_UNAVAILABLE`(CB OPEN용, PAY011)과 분리한 신규 코드로 둔다.** 둘 다 HTTP 503으로 응답하지만 원인이 다르다 — `PG_UNAVAILABLE`은 "Toss 자체가 불안정하다고 판단해 호출을 걸지 않음"이고, Bulkhead 포화는 "Toss 상태와 무관하게 우리 서버가 동시에 감당할 수 있는 양을 넘었음"이다. 원인을 구분해야 운영 시 "Toss 장애인지 우리 쪽 용량 문제인지"를 로그/메트릭에서 바로 구분할 수 있다. 신규 코드는 `PG_BUSY`(`PAY013`, `HttpStatus.SERVICE_UNAVAILABLE`, "결제 승인 요청이 많아 일시적으로 처리할 수 없습니다. 잠시 후 다시 시도해주세요.")로 추가한다.

## 컴포넌트 구조

`infrastructure/external/toss` 패키지에 `TossBulkheadConfig`를 신규 추가한다. 기존 `TossCircuitBreakerConfig`와 동일한 패턴 — `resilience4j.bulkhead.instances.toss-confirm-bulkhead` 값을 `@Value`로 읽어 `BulkheadConfig` 하나를 만들고, `tossConfirmBulkhead` `@Bean` 하나만 노출한다(confirm 전용이라 refund용 인스턴스는 두지 않는다). `TaggedBulkheadMetrics`로 Micrometer 메트릭도 등록한다.

`TossPaymentGateway`는 생성자에 `Bulkhead confirmBulkhead`를 추가로 주입받는다. `confirm()` 내부는 기존 `execute(CircuitBreaker, Supplier)` 헬퍼를 그대로 쓰지 않고, CB와 Bulkhead를 함께 감싸는 별도 실행 경로로 분리한다(`refund()`는 CB만 쓰는 기존 `execute` 그대로 유지 — 두 메서드의 보호 정책이 이제 다르므로 억지로 공통 헬퍼에 우겨넣지 않는다).

```java
private <T> T executeWithBulkhead(CircuitBreaker circuitBreaker, Bulkhead bulkhead, Supplier<T> supplier) {
    try {
        Supplier<T> decorated = Bulkhead.decorateSupplier(bulkhead, supplier);
        return CircuitBreaker.decorateSupplier(circuitBreaker, decorated).get();
    } catch (CallNotPermittedException exception) {
        // 기존과 동일 — PG_UNAVAILABLE
    } catch (BulkheadFullException exception) {
        log.warn("Toss 확인 Bulkhead 포화 — 동시 호출 상한 초과. bulkhead={}", bulkhead.getName());
        throw new PaymentGatewayException(
            PaymentErrorCode.PG_BUSY, "BULKHEAD_FULL",
            "결제 승인 동시 호출 상한을 초과했습니다.", null, null
        );
    }
}
```

## 설정값

```yaml
resilience4j:
  circuitbreaker:
    configs:
      toss-payment-default:
        # 기존 값 그대로 유지
        ignore-exceptions:
          - io.github.resilience4j.bulkhead.BulkheadFullException
  bulkhead:
    instances:
      toss-confirm-bulkhead:
        max-concurrent-calls: 20
        max-wait-duration: 200ms
```

3개 yml 파일(`application-local.yml`, `application-test.yml`, `config/payment-service.yml`) 모두에 반영한다 — 356번 작업 때 확립한 컨벤션(환경별 파일에 각자 명시, 기본값 없이 필수 바인딩)을 그대로 따른다.

**에러코드 추가** (`PaymentErrorCode`):

```java
PG_BUSY(HttpStatus.SERVICE_UNAVAILABLE, "PAY013", "결제 승인 요청이 많아 일시적으로 처리할 수 없습니다. 잠시 후 다시 시도해주세요.")
```

**의존성 추가** (`../../../build.gradle`):

```gradle
implementation 'io.github.resilience4j:resilience4j-bulkhead:2.4.0'
```

기존 `resilience4j-circuitbreaker`와 동일 버전(2.4.0)으로 맞춘다. 루트 `../../../../build.gradle`에는 이 의존성이 없음을 확인했다.

## 테스트 계획

1. `TossPaymentGatewayBulkheadTest`(신규) — 기존 `TossPaymentGatewayCircuitBreakerTest`와 동일 패턴(`com.sun.net.httpserver.HttpServer` stub, 작은 값으로 만든 테스트 전용 `Bulkhead` 직접 주입). stub 서버가 응답을 지연시키는 동안 여러 스레드에서 동시에 `confirm()`을 호출해, `maxConcurrentCalls`를 넘는 호출이 `PaymentGatewayException(PG_BUSY)`을 던지는지 확인.
2. `TossBulkheadConfig` bean 생성 테스트(`TossCircuitBreakerConfigTest` 패턴 참고) — `@Value` 바인딩으로 `Bulkhead` 인스턴스가 올바른 설정(`maxConcurrentCalls`, `maxWaitDuration`)으로 만들어지는지 확인.
3. 기존 `TossPaymentGatewayTest`/`TossPaymentGatewayCircuitBreakerTest`는 `TossPaymentGateway` 생성자 시그니처 변경(`Bulkhead` 파라미터 추가)에 맞춰 호출부 수정.
4. `ConfirmPaymentIntegrationTest`는 `@MockitoBean PaymentGateway`라 Bulkhead 로직을 타지 않아 코드 변경 불필요. 단 `application-test.yml`에 값이 없으면 `TossBulkheadConfig`의 `@Value` 바인딩 실패로 컨텍스트 기동 자체가 실패하므로 값을 반드시 채운다.

## 이번 범위에서 제외한 것 (별도 이슈 후보)

- **refund Bulkhead**: 위 "범위" 절 참고 — `OrderEventConsumer`가 concurrency=1이라 이미 동시성이 1로 강제돼 있어 적용 실익이 없다.
- **`OrderEventConsumer`의 Kafka concurrency=1 자체 재검토**: 브레인스토밍 중 발견한 별도 문제. refund(Toss 호출, 최대 60초)가 느려지면 그동안 `order-events` 토픽의 다른 메시지 전부가 대기하는 head-of-line blocking이 발생할 수 있다. 다만 이 토픽은 payment-service가 소유하지 않고(파티션 수도 이 서비스가 결정하지 못함), concurrency는 파티션 수가 상한이라 파티션 전략 자체를 다른 서비스와 협의해야 하는 크로스 서비스 사안이다. Bulkhead 작업과 성격이 달라 이번 범위에 넣지 않았다. 실제로 개선하려면 별도 이슈를 파서 토픽 소유 서비스와 파티션/순서 보장 요구사항부터 논의해야 한다.
- **gRPC(`OrderGrpcClientAdapter`) Bulkhead**: 356번 계획 문서에서도 제외됐고, 이번 이슈(#490) 원문도 "gRPC는 범위 제외"를 명시하고 있어 그대로 따랐다.
