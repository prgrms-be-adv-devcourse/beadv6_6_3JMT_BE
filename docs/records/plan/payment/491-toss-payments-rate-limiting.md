# Toss 결제 승인 유량제어(RateLimiter) 구현 계획

`TossPaymentGateway.confirm()` 호출에 Resilience4j RateLimiter를 적용해, Toss API 호출 빈도가 PG사의 (문서화되지 않은) quota를 초과하지 않도록 선제 방어한다.

---

## 배경 및 목표

이슈 #491 원문은 "특정 클라이언트/IP의 과도한 요청이 DB 부하 증가 및 Toss Payments API 호출 한도 초과로 이어질 수 있다"며 두 가지 목적을 함께 언급했다 — ① 클라이언트별 인바운드 요청량 제한(DB 부하 방지), ② Toss 호출 자체의 아웃바운드 빈도 제한(PG사 quota 방지). 브레인스토밍 중 이 둘을 분리했다.

인바운드 제한은 IP/유저ID/API키별로 카운터를 따로 둬야 하고, payment-service가 수평 확장되면 인스턴스별 로컬 카운터로는 전체 유량을 정확히 볼 수 없어 Redis 같은 공유 저장소가 필요해진다. 반면 이 서비스는 모든 요청이 어차피 API Gateway(Spring Cloud Gateway)를 거치고, Gateway는 Redis 기반 `RequestRateLimiter`를 이미 내장 지원한다. 클라이언트별 제한은 한 곳(Gateway)에서 막아야 다른 서비스(order, user, product 등)도 같은 어뷰징으로부터 함께 보호되고, 서비스마다 중복 구현할 필요가 없다. 이 판단에 따라 인바운드 제한은 apigateway 쪽 별도 이슈로 분리하고, 이번 이슈 #491(payment-service 범위)은 ②번, 즉 Toss 호출 자체의 아웃바운드 유량제어만 다룬다.

**Toss 측 실제 quota 조사 결과**: 공식 개발자 문서(`docs.tosspayments.com/reference`)를 확인한 결과, 결제 승인(confirm) API 문서 자체에는 rate limit·TPS·429 관련 언급이 없다. 429 언급은 거래조회/정산조회/현금영수증조회 등 조회 계열 API에만 있으며, 그마저도 "라이브 환경에서 비정상적으로 많은 요청을 보내면 429가 내려올 수 있다"는 정성적 표현뿐 구체적 수치는 없다. 계약서 레벨의 TPS 조항 존재 여부는 문서 조사로 확인할 수 없었다(고객센터·계약서 확인이 필요한 영역이며 이번 조사 범위 밖).

즉 이 작업은 "실제 관측된 429 초과 이력"이나 "Toss가 공개 문서화한 명시적 quota"에 대응하는 것이 아니라, 결제 도메인 특성상(429 한 번이 사용자 결제 실패로 직결) 확인되지 않은 리스크에 대한 **선제적(예방적) 방어**다. 이 전제 때문에 설정값은 전부 근거가 약한 잠정치이며, 배포 후 모니터링으로 재조정하는 것을 전제로 한다 — 같은 성격의 판단을 이미 CircuitBreaker(#356)·Bulkhead(#490) 설정값에도 적용한 바 있다.

## 범위

**적용 대상은 `TossPaymentGateway.confirm()`뿐이다. `refund()`는 제외한다.**

`refund()`의 유일한 진입점은 `infrastructure.messaging.consumer.OrderEventConsumer`(`@KafkaListener`, `order-events` 토픽 구독)이고, 이 리스너의 컨테이너 concurrency는 기본값 1이라 인스턴스 내에서는 순차 처리가 강제된다. 다만 이것만으로는 부족한 논거였다 — `order-events` 토픽이 여러 파티션을 가지고 payment-service가 여러 인스턴스로 뜨면, 인스턴스별로는 순차라도 fleet 전체로는 파티션 수만큼 동시에 refund 호출이 나갈 수 있다. 이 경우 로컬(프로세스 내) 유량 제한으로는 fleet 전체 호출량을 못 본다.

k8s 배포 설정(`k8s/base/services/payment/deployment.yaml`)을 확인한 결과 `replicas: 1`이고 HorizontalPodAutoscaler도 없다. 즉 현재는 인스턴스가 1대로 고정되어 있어 "인스턴스별 순차 = fleet 전체 순차"가 성립한다. Toss confirm 응답 지연을 수백ms 수준으로 가정하면(Bulkhead 설계 문서와 동일 가정), refund도 유사한 지연이라 할 때 순차 처리 시 최대 처리량은 초당 3~4건 수준으로, confirm에 적용하려는 초당 30건보다 한참 낮다. 별도로 유량을 눌러줄 실익이 없다.

**이 결론이 깨지는 재검토 트리거**를 명시해둔다 — 아래 둘 중 하나라도 발생하면 refund RateLimiter 적용을 다시 검토한다.

- payment-service가 `replicas > 1`로 스케일아웃되는 경우
- `OrderEventConsumer`의 concurrency가 1보다 커지는 경우 — Bulkhead 설계 문서(#490)가 이미 "컨슈머 concurrency=1로 인한 head-of-line blocking" 문제를 별도 이슈 후보로 지적한 바 있으며, 그 문제가 실제로 해소되면 이번 문서의 refund 제외 근거도 함께 무효화된다.

## 확정 사항

**resilience4j-ratelimiter를 채택한다.** 기존 `tossConfirmCircuitBreaker`/`tossRefundCircuitBreaker`(#356), `tossConfirmBulkhead`(#490)와 동일 라이브러리 패밀리이며 버전(2.4.0)도 통일한다. 이미 같은 패턴(설정값 `@Value` 바인딩 → 인스턴스 생성 → Micrometer 메트릭 등록)이 두 번 확립되어 있어, 신규 서드파티 없이 그대로 재사용하는 것이 자연스럽다.

**로컬(프로세스 내) RateLimiter 상태로 충분하다고 판단한다.** RateLimiter가 지키려는 것은 "이 서비스가 Toss로 내보내는 confirm 호출의 총량"이고, 이걸 내보내는 프로세스가 현재 1개(`replicas: 1`, 위 범위 절 참고)뿐이라 로컬 카운터가 곧 fleet 전체 카운터와 같다. **전제**: 향후 `replicas > 1`로 스케일아웃하면 인스턴스마다 로컬 카운터가 따로 생겨 fleet 전체 유량을 못 보게 되므로(인바운드 유량제어를 Gateway로 보낸 것과 동일한 이유), 그 시점에 분산 저장소(Redis) 기반 전환이 필요하다.

**조합 순서는 `CircuitBreaker(바깥) → RateLimiter(중간) → Bulkhead(안쪽)`으로 정한다.** 세 장치가 지키는 자원의 성격이 다르다 — CB 체크는 메모리 상태 조회라 비용이 거의 없고, RateLimiter는 1초 단위 시간창의 permit을 소비하되 창이 지나면 곧 회복되며, Bulkhead는 Toss 응답이 올 때까지(최악 readTimeout 60초) 동시성 슬롯을 계속 붙잡는 가장 비싼 자원이다. 원칙은 "이미 실패가 확정된 호출에게 뒤쪽(안쪽)의 더 비싼 자원을 낭비시키지 않는다"이며, 이는 Bulkhead 설계 문서(#490)가 CB를 Bulkhead 바깥에 둔 논거와 동일한 논리를 한 단계 더 확장한 것이다.

- CB가 제일 바깥인 이유: CB가 OPEN이면 이 호출은 Toss 근처도 안 갈 게 확정이다. RateLimiter를 CB보다 바깥에 두면(RL→CB→BH), OPEN 상태에서도 호출마다 RateLimiter의 permit이 소비되어 버려질 호출들이 그 시간창의 유량 예산을 갉아먹는다. CB가 CLOSED로 돌아오는 순간 RateLimiter 예산이 이미 방금 전 OPEN 구간 호출들 때문에 바닥나 있어, 복구 직후 들어오는 정상 호출이 부당하게 거절될 수 있다. CB를 제일 바깥에 두면 OPEN 동안 RateLimiter 로직 자체가 실행되지 않아 이 문제가 없다.
- RateLimiter가 Bulkhead보다 바깥인 이유: 순서를 반대로 하면(CB→BH→RL) 호출이 먼저 Bulkhead 동시성 슬롯(20개 중 1개)을 잡은 뒤에야 RateLimiter 검사를 받는다. RateLimiter가 거절하면 그 호출은 Toss에 가지 않았는데도 잠깐 동시성 슬롯을 점유했다 반납한 셈이 되어, 버스트 상황에서 진짜 통과해야 할 호출이 슬롯 부족으로 밀려날 수 있다. RateLimiter를 Bulkhead보다 바깥에 둬서, 애초에 유량을 통과하지 못하는 호출은 동시성 슬롯을 점유하지 않도록 한다.

이 순서는 resilience4j의 스프링부트 통합 어노테이션 권장 조합 순서(Retry < CircuitBreaker < RateLimiter < TimeLimiter < Bulkhead, 바깥→안쪽)와도 일치한다 — 라이브러리 권장값을 그대로 따른 것이 아니라 우리 상황에 같은 논리를 적용해 재도출한 결과가 일치했다.

**CircuitBreaker 설정의 `ignore-exceptions`에 RateLimiter의 거절 예외(`RequestNotPermitted`)를 추가한다.** Bulkhead 도입 때 `BulkheadFullException`을 추가했던 것과 동일한 이유다 — RateLimiter가 거절한 호출은 Toss 장애가 아니라 우리 쪽 유량 정책에 의한 것이므로 CB의 실패율 통계에 섞이면 안 된다. 이를 빠뜨리면 순간적인 유량 초과가 실제 Toss 장애처럼 집계되어 CB를 불필요하게 OPEN시킬 수 있다.

**RateLimiter 거절 시 에러코드는 기존 `PG_BUSY`(Bulkhead 포화, PAY013)와 분리한 신규 코드로 둔다.** 둘 다 HTTP 503으로 응답하지만 원인이 다르다 — `PG_BUSY`는 "동시에 처리 중인 요청 수가 상한을 넘었다"(공간 축)이고, 이번 것은 "단위 시간당 호출 빈도가 상한을 넘었다"(시간 축)이다. 운영 시 로그/메트릭에서 "동시성 문제인지 빈도 문제인지"를 바로 구분하려면 원인별 코드가 필요하다는 판단은 `PG_UNAVAILABLE`(CB OPEN)과 `PG_BUSY`(Bulkhead 포화)를 분리했던 기존 판단과 동일한 논거다. 신규 코드는 `PG_RATE_LIMITED`(`PAY014`, `HttpStatus.SERVICE_UNAVAILABLE`, "결제 승인 요청이 많아 일시적으로 제한되었습니다. 잠시 후 다시 시도해주세요.")로 추가한다.

**설정값은 `limit-for-period=30`, `limit-refresh-period=1s`, `timeout-duration=0ms`로 잠정 설정한다.** 초당 30건은 Bulkhead의 `maxConcurrentCalls=20`(응답 수백ms 가정 시 이론적 최대 처리량 초당 수십 건 수준)보다 낮게 잡아, RateLimiter가 실제로 유효한 제약으로 작동하도록 한 값이다. `timeout-duration=0ms`(대기 없이 즉시 거절)로 정한 이유는 Bulkhead가 이미 `maxWaitDuration=200ms`로 순간적인 버스트를 흡수하는 역할을 하고 있어, RateLimiter 단계에서까지 대기를 또 두면 두 종류의 "왜 실패했는지" 대기 구간이 겹쳐 원인 진단이 헷갈리기 때문이다. **실측 트래픽 데이터 없이 잡은 잠정값이므로, 배포 후 모니터링 지표를 보고 재조정이 필요하다**(#356, #490과 동일한 성격의 한계).

## 컴포넌트 구조

`infrastructure/external/toss` 패키지에 `TossRateLimiterConfig`를 신규 추가한다. 기존 `TossCircuitBreakerConfig`/`TossBulkheadConfig`와 동일한 패턴 — `resilience4j.ratelimiter.instances.toss-confirm-rate-limiter` 값을 `@Value`로 읽어 `RateLimiterConfig` 하나를 만들고, `tossConfirmRateLimiter` `@Bean` 하나만 노출한다(confirm 전용이라 refund용 인스턴스는 두지 않는다). `TaggedRateLimiterMetrics`로 Micrometer 메트릭도 등록한다.

`TossPaymentGateway`는 생성자에 `RateLimiter confirmRateLimiter`를 추가로 주입받는다. `confirm()`의 기존 `executeWithBulkhead(CircuitBreaker, Bulkhead, Supplier)` 실행 경로를 RateLimiter까지 감싸는 3단 조합으로 확장한다(`refund()`는 기존 CB-only `execute` 그대로 유지 — 두 메서드의 보호 정책이 다르므로 억지로 공통 헬퍼에 우겨넣지 않는다).

```java
private <T> T executeConfirm(
    CircuitBreaker circuitBreaker, RateLimiter rateLimiter, Bulkhead bulkhead, Supplier<T> supplier
) {
    try {
        Supplier<T> decorated = Bulkhead.decorateSupplier(bulkhead, supplier);
        decorated = RateLimiter.decorateSupplier(rateLimiter, decorated);
        return CircuitBreaker.decorateSupplier(circuitBreaker, decorated).get();
    } catch (CallNotPermittedException exception) {
        // 기존과 동일 — PG_UNAVAILABLE
    } catch (BulkheadFullException exception) {
        // 기존과 동일 — PG_BUSY
    } catch (RequestNotPermitted exception) {
        log.warn("Toss 확인 RateLimiter 거절 — 유량 상한 초과. rateLimiter={}", rateLimiter.getName());
        throw new PaymentGatewayException(
            PaymentErrorCode.PG_RATE_LIMITED, "RATE_LIMITED",
            "결제 승인 유량 상한을 초과했습니다.", null, null
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
          - io.github.resilience4j.ratelimiter.RequestNotPermitted
  ratelimiter:
    instances:
      toss-confirm-rate-limiter:
        limit-for-period: 30
        limit-refresh-period: 1s
        timeout-duration: 0ms
```

3개 yml 파일(`application-local.yml`, `application-test.yml`, `config/payment-service.yml`) 모두에 반영한다 — #356/#490 때 확립한 컨벤션(환경별 파일에 각자 명시, 기본값 없이 필수 바인딩)을 그대로 따른다.

**에러코드 추가** (`PaymentErrorCode`):

```java
PG_RATE_LIMITED(HttpStatus.SERVICE_UNAVAILABLE, "PAY014", "결제 승인 요청이 많아 일시적으로 제한되었습니다. 잠시 후 다시 시도해주세요.")
```

**의존성 추가** (`payment-service/build.gradle`):

```gradle
implementation 'io.github.resilience4j:resilience4j-ratelimiter:2.4.0'
```

기존 `resilience4j-circuitbreaker`/`resilience4j-bulkhead`와 동일 버전(2.4.0)으로 맞춘다. 루트 `build.gradle`에는 이 의존성이 없음을 확인했다.

## 테스트 계획

1. `TossPaymentGatewayRateLimiterTest`(신규) — 기존 `TossPaymentGatewayBulkheadTest`와 동일 패턴(`com.sun.net.httpserver.HttpServer` stub, 작은 값(예: `limitForPeriod=1~2`)으로 만든 테스트 전용 `RateLimiter` 직접 주입). 짧은 시간 내 연속 `confirm()` 호출 시 한도 초과분이 `PaymentGatewayException(PG_RATE_LIMITED)`을 던지는지 확인.
2. `TossRateLimiterConfigTest`(신규) — `@Value` 바인딩으로 `RateLimiter` 인스턴스가 설정값(`limitForPeriod`, `limitRefreshPeriod`, `timeoutDuration`)대로 만들어지는지 확인.
3. 기존 `TossPaymentGatewayTest`/`TossPaymentGatewayCircuitBreakerTest`/`TossPaymentGatewayBulkheadTest`는 `TossPaymentGateway` 생성자 시그니처 변경(`RateLimiter` 파라미터 추가)에 맞춰 호출부 수정.
4. `ConfirmPaymentIntegrationTest`는 `@MockitoBean PaymentGateway`라 RateLimiter 로직을 타지 않아 코드 변경 불필요. 단 `application-test.yml`에 값이 없으면 `TossRateLimiterConfig`의 `@Value` 바인딩 실패로 컨텍스트 기동 자체가 실패하므로 값을 반드시 채운다.

## 이번 범위에서 제외한 것

- **refund() RateLimiter**: "범위" 절 참고. 재검토 트리거(`replicas > 1` 스케일아웃, `OrderEventConsumer` concurrency 상향) 발생 시 재검토.
- **인바운드(IP/유저별) 유량제어**: "배경 및 목표" 절 참고. API Gateway(apigateway) 영역이라 별도 이슈로 분리한다. payment-service AI 작업 범위(CLAUDE.md)를 벗어나므로 이 문서·이 이슈에서 구현하지 않는다.
- **분산(Redis 기반) RateLimiter**: 현재 `replicas: 1`이라 로컬 상태로 충분하다고 판단(확정 사항 절 참고). 스케일아웃 시점에 별도 검토.
