# Toss 결제 서킷브레이커 구현 계획

`TossPaymentGateway`(confirm/refund)에 Resilience4j CircuitBreaker를 적용해 Toss 장애 시 스레드 고갈과 무한 대기를 막는다.

---

## 배경 및 목표

Toss Payments 연동 지점(`infrastructure.external.toss.TossPaymentGateway`)은 동기 REST 호출(`RestClient`)이다. Toss가 느려지거나 5xx를 반복하면 요청마다 read timeout까지 스레드를 점유해, 결제 승인/환불 외의 다른 API까지 영향을 받을 수 있다(이슈 #356).

모노레포 공통 가이드(`../docs/architecture/incident-management.md`)는 동기 외부 호출 경계에 CircuitBreaker + Bulkhead 조합을 권장한다. 이번 작업은 그중 **CircuitBreaker만** 우선 적용한다. Bulkhead는 별도 이슈로 분리한다 — payment-service의 Toss 호출은 order-service의 상품 gRPC 호출과 달리 요청 1건당 Toss 호출 1건(fan-out 없음)이라, 스레드 풀 고갈 방지 효과가 상대적으로 낮고 이번 범위에서 우선순위가 낮다고 판단했다.

## 확정 사항

**적용 범위는 Toss REST 호출(confirm/refund)만으로 한정한다.** 주문 정보 조회(`OrderGrpcClientAdapter`, gRPC)는 이번 범위에서 제외한다. Toss 연동은 결제 승인/환불이라는 사용자 결제 흐름의 가장 장애 격리가 시급한 지점이라 우선순위를 뒀고, gRPC 쪽은 별도로 필요성을 검토하기로 했다.

**confirm과 refund는 서킷브레이커 인스턴스를 분리한다(`tossConfirmCircuitBreaker`, `tossRefundCircuitBreaker`).** 두 호출은 장애 영향도와 재처리 성격이 다르다 — confirm은 사용자가 결제 화면에서 즉시 기다리는 경로라 실패를 빠르게 노출해야 하고, refund는 후처리 성격이 강해 재시도 여지가 더 있다. 하나의 서킷브레이커를 공유하면 한쪽의 실패 폭주가 다른 쪽 호출까지 불필요하게 차단할 수 있어, 분리해 서로 독립적으로 장애 격리되게 했다.

**컴포넌트는 파일 2개(`TossCircuitBreakerConfig`, `TossFailurePredicate`)로 구성하고, order-service의 `ProductGrpcResilience`/`ProductGrpcOperation` 같은 이름 기반 조회 레이어는 두지 않는다.** order-service는 gRPC 메서드 3개를 CB 2개에 매핑하는 다대일 구조라 `record` + `enum` + 문자열 이름 조회(`switch`)가 필요했다. payment-service는 confirm/refund가 CB와 1:1로 대응해 그 간접 계층이 불필요하다(YAGNI) — `TossPaymentGateway` 생성자가 `CircuitBreaker` 두 개를 `@Qualifier`로 직접 주입받는 편이 더 단순하다.

**실패 판정은 네트워크 오류·5xx만 반영하고, 4xx(우리 쪽 잘못된 요청·정상적인 결제 거절)는 제외한다.** 모노레포 가이드가 "입력값 오류·인증 오류·리소스 없음은 실패율에서 제외"를 명시하기 때문이다. `TossPaymentGateway`가 이미 4xx를 `PG_INVALID_REQUEST`(우리 쪽 요청 오류)와 `PAYMENT_FAILED`(카드 한도 초과 등 정상적인 결제 거절)로 구분해 두고 있어, 이 두 코드는 Toss 장애와 무관하므로 실패율에서 제외하고, `PG_SERVER_ERROR`(5xx)와 `ResourceAccessException`(connect/read timeout)만 실패로 기록한다.

**서킷 OPEN 시 반환하는 에러코드는 기존 `PG_SERVER_ERROR`와 분리한 신규 코드 `PG_UNAVAILABLE`(503, `PAY011`)로 둔다.** `PG_SERVER_ERROR`(502)는 실제로 Toss에 요청을 보냈고 Toss가 5xx로 응답한 경우를 뜻하는데, 서킷 OPEN은 이번 요청을 아예 Toss에 보내지 않은 경우라 의미가 다르다. 이 구분이 있어야 호출 측(주문 서비스 등)이 "Toss에 도달하지 않았으니 안전하게 재시도 가능"과 "Toss가 요청을 받고 처리하다 실패해서 부분 처리 가능성이 있음"을 구분해 재시도 여부를 판단할 수 있다. 기존 `ORDER_INFO_UNAVAILABLE`(503, gRPC 다운스트림 도달 불가)도 같은 원칙으로 이미 분리돼 있어 일관성 있는 선택이다.

**Toss 공식 문서 권장에 따라 기존 `readTimeout`을 10초에서 60초로 변경한다.** [Toss 공식 문서](https://docs.tosspayments.com/resources/glossary/timeout)가 "결제 처리와 관련된 API의 Read Timeout은 60초로 설정하면 돼요"라고 명시하고 있어, 기존 10초 설정은 이 권장치의 1/6에 불과했다. `connectTimeout`(5초)은 문서에 구체적 권장치가 없어 유지한다.

**서킷브레이커 설정값은 3개 yml 파일(`application-local.yml`, `application-test.yml`, `config/payment-service.yml`)에 각각 명시한다.** payment-service는 환경별 설정(`payment.toss.secret-key`/`base-url` 등)을 프로필별 파일에 각자 명시하는 기존 컨벤션을 쓰고 있다(base `application.yml`에 공유값을 두고 Config Server가 덮어쓰는 방식이 아니다). 서킷브레이커 설정도 이 컨벤션을 따른다. `TossCircuitBreakerConfig`는 `@Value`로 값을 읽어오며, 3곳 모두 값을 명시하므로 기본값 없이 필수 바인딩으로 둔다. 설정 누락 시 조용히 기본값으로 기동되기보다 컨텍스트 기동 자체가 실패하는 편이 안전하다.

## 컴포넌트 구조

`infrastructure/external/toss` 패키지에 2개 파일 추가.

| 파일 | 역할 |
|---|---|
| `TossCircuitBreakerConfig` | `@Configuration`. `resilience4j.circuitbreaker.configs.toss-payment-default` 값을 읽어 `CircuitBreakerConfig` 하나를 만들고, 그 config로 `tossConfirmCircuitBreaker`/`tossRefundCircuitBreaker` `@Bean` 두 개를 직접 노출. `TaggedCircuitBreakerMetrics`로 Micrometer 메트릭도 등록 |
| `TossFailurePredicate` | 두 CircuitBreaker가 공유하는 실패 판정 `Predicate<Throwable>` |

`TossPaymentGateway`는 생성자에 `CircuitBreaker` 두 개(`@Qualifier`로 구분)를 주입받아, `confirm`/`refund` 내부 로직을 `execute(CircuitBreaker, Supplier<T>)` 헬퍼로 감싼다. `CallNotPermittedException`을 잡아 `PaymentGatewayException(PG_UNAVAILABLE, "CIRCUIT_OPEN", ...)`으로 변환한다.

## 실패 판정 정책

`TossFailurePredicate.test(Throwable)` — true면 실패율에 반영.

| 예외 | 반영 여부 | 근거 |
|---|---|---|
| `ResourceAccessException`(connect/read timeout) | 반영 | 네트워크 장애 |
| `PaymentGatewayException`(`errorCode == PG_SERVER_ERROR`) | 반영 | Toss 5xx |
| `PaymentGatewayException`(`errorCode == PG_INVALID_REQUEST`) | 제외 | 우리 쪽 요청 오류(잘못된 API 키·요청 형식 등) |
| `PaymentGatewayException`(`errorCode == PAYMENT_FAILED`) | 제외 | 카드 한도 초과 등 정상적인 결제 거절(장애 아님) |

`NULL_RESPONSE` 케이스는 이미 `PG_INVALID_REQUEST`/`PG_SERVER_ERROR`로 구분돼 던져지므로 이 표에 자연히 포함된다.

## 설정값

**readTimeout**: `TossPaymentGateway`의 `factory.setReadTimeout(Duration.ofSeconds(10))` → `Duration.ofSeconds(60)`(Toss 공식 권장 준수). `connectTimeout`(5초)은 유지.

```yaml
resilience4j:
  circuitbreaker:
    configs:
      toss-payment-default:
        sliding-window-size: 20
        minimum-number-of-calls: 10
        failure-rate-threshold: 50
        slow-call-duration-threshold: 20000ms   # readTimeout(60s)의 1/3 지점, 조기 경보용
        slow-call-rate-threshold: 50
        wait-duration-in-open-state: 30s
        permitted-number-of-calls-in-half-open-state: 3
```

**중요 — 위 값 중 `slow-call-duration-threshold`를 제외한 나머지(윈도우 크기, 임계 비율, 대기 시간 등)는 실제 트래픽/장애 데이터 없이 잡은 잠정값이다.** Toss 공식 문서에는 타임아웃 권장치만 있고 통계적 임계값 권장은 없다. 배포 후 실제 실패율·OPEN 전환 빈도를 모니터링하고 재조정이 필요하다.

**에러코드 추가** (`PaymentErrorCode`):

```java
PG_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "PAY011", "PG사 서비스에 일시적으로 연결할 수 없습니다.")
```

## 테스트 계획

1. `TossFailurePredicateTest`(신규, 순수 단위) — 실패 판정 표의 4가지 케이스 검증(AssertJ).
2. `TossPaymentGatewayTest` 확장 — 생성자 시그니처 변경(`CircuitBreaker` 2개 추가) 반영. 신규 케이스: mock HTTP 서버가 5xx 반복 응답 → confirm/refund 각각 별도 CB가 `minimum-number-of-calls` 채우고 실패율 초과 시 OPEN 전환 확인, OPEN 상태에서 실제 HTTP 호출 없이 즉시 `PaymentGatewayException(PG_UNAVAILABLE)`를 던지는지 확인. 테스트에서는 yml 설정값 대신 작은 window(size 2~4)로 직접 만든 `CircuitBreaker`를 주입해 결정론적으로 빠르게 트립시킨다.
3. `TossCircuitBreakerConfig` bean 생성 테스트 — `@Value` 바인딩으로 두 `CircuitBreaker` 인스턴스가 올바른 설정으로 만들어지는지 확인.
4. 기존 `ConfirmPaymentIntegrationTest`/`PartialRefundIntegrationTest`는 `@MockitoBean PaymentGateway`라 CB 로직을 타지 않아 테스트 코드 변경은 불필요하다. 단 `application-test.yml`에 값이 없으면 `TossCircuitBreakerConfig`의 `@Value` 바인딩 실패로 컨텍스트 기동 자체가 실패하므로, 값을 반드시 채워야 한다.

## 이번 범위에서 제외한 것

- **Bulkhead**: 모노레포 가이드는 CircuitBreaker와 항상 짝으로 쓰기를 권장하지만, Toss 호출이 요청당 1건(fan-out 없음)이라는 특성상 이번 범위에서는 우선순위를 낮췄다. 필요성이 확인되면 별도 이슈로 진행한다.
- **주문 gRPC(`OrderGrpcClientAdapter`) 서킷브레이커**: 이번 이슈(#356) 범위 밖. 필요 시 별도 논의.
