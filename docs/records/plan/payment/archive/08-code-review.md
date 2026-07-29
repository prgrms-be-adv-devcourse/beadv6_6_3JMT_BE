# 코드 리뷰 — 2026-06-29

리뷰 대상 브랜치: `HEAD` (최근 5개 커밋)
리뷰 범위: 테스트 성능 개선 + Swagger/Eureka/Config 설정 변경

---

## 요약

| 순위 | 파일 | 분류 | 판정 |
|------|------|------|------|
| 1 | `application.yaml:49` | eureka 테스트 오버라이드 누락 | CONFIRMED |
| 2 | `AbstractIntegrationTest.java:31` | ddl-auto=create + BeforeEach 정리 없음 | CONFIRMED |
| 3 | `ConfirmPaymentIntegrationTest.java:109` | getSingleRecord >1 레코드 충돌 | PLAUSIBLE |
| 4 | `pre-pr-check.md:11` | git pull이 발산 숨기고 신규 브랜치 중단 | CONFIRMED |
| 5 | `build.gradle:62` | maxParallelForks → 컨테이너 공유 무력화 | CONFIRMED |
| 6 | `PaymentControllerTest.java` | PAY004→400 매핑 테스트 공백 | CONFIRMED |
| 7 | `build.gradle:51` | springdoc 3.0.0 BOM 외 하드코딩 | PLAUSIBLE |
| 8 | `create-pr.md:12` | git pull 동일 문제 전파 | CONFIRMED |
| 9 | `RefundPaymentIntegrationTest.java:101` | getBody() null 검사 누락 | PLAUSIBLE |
| 10 | `application.yaml:7` | optional:configserver 기동 지연 | PLAUSIBLE |

---

## Finding 1 — eureka.client.enabled: true, 테스트 프로파일 오버라이드 누락 ⚠️

**파일**: `src/main/resources/application.yaml:49`
**판정**: CONFIRMED

### 문제
`eureka.client.enabled`가 `false`에서 `true`로 변경됐으나 `src/test/resources/application-test.yml`에 비활성화 오버라이드가 없다.

```yaml
# application.yaml:47-51
eureka:
  client:
    enabled: true   ← 변경됨
    service-url:
      defaultZone: http://localhost:8761/eureka/
```

`application-test.yml`은 JPA 설정만 포함하며 eureka 관련 설정이 없다.

### 영향
`@ActiveProfiles("test")` 통합 테스트 4종 기동 시 Eureka 클라이언트가 `localhost:8761` 등록을 시도한다. CI/로컬 환경에 Eureka 서버가 없으면 30초 단위 재시도 루프가 반복되어 테스트 전체 실행 시간이 증가한다.

### 수정
```yaml
# src/test/resources/application-test.yml 에 추가
eureka:
  client:
    enabled: false
```

---

## Finding 2 — ddl-auto=create + @BeforeEach DB 정리 없음 ⚠️

**파일**: `src/test/java/com/prompthub/paymentservice/support/AbstractIntegrationTest.java:31`
**판정**: CONFIRMED

### 문제
`ddl-auto`가 `create-drop` → `create`로 변경되었으나, `ConfirmPaymentIntegrationTest`와 `RefundPaymentIntegrationTest`의 `@BeforeEach`는 RestTemplate 초기화만 수행하고 `deleteAll()`을 호출하지 않는다.

```java
// AbstractIntegrationTest.java:31
registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");  // drop이 없어짐

// ConfirmPaymentIntegrationTest @BeforeEach — DB 정리 없음
// RefundPaymentIntegrationTest @BeforeEach — DB 정리 없음
```

### 영향
동일 JVM 포크 내 테스트 순차 실행 시 이전 테스트가 발행한 `payment.approved` Kafka 메시지가 남아있어, `ConfirmPaymentIntegrationTest`의 `seekToBeginning()`으로 초기화된 consumer가 stale 메시지를 읽는다. → Finding 3과 결합 시 `IllegalStateException` 유발.

### 수정
두 통합 테스트 클래스에 `@BeforeEach` DB 정리 추가:
```java
@Autowired PaymentJpaRepository paymentJpaRepository;

@BeforeEach
void cleanUp() {
    paymentJpaRepository.deleteAll();
    // refundJpaRepository.deleteAll(); // 필요시
}
```

---

## Finding 3 — KafkaTestUtils.getSingleRecord() 레코드 수 충돌 ⚠️

**파일**: `src/test/java/com/prompthub/paymentservice/ConfirmPaymentIntegrationTest.java:109`
**판정**: PLAUSIBLE

### 문제
```java
// ConfirmPaymentIntegrationTest.java:109
ConsumerRecord<String, String> record = KafkaTestUtils.getSingleRecord(
    consumer, PaymentTopic.PAYMENT_APPROVED, Duration.ofSeconds(30)
);
```

`getSingleRecord()`는 레코드가 **정확히 1개**일 때만 성공하며, 2개 이상이면 `IllegalStateException("Expected single record but got N")`을 던진다.

`RefundPaymentIntegrationTest`의 세 `@Test` 메서드가 모두 내부적으로 `승인_요청()`을 호출하며 `payment.approved` 토픽에 메시지를 발행한다. `seekToBeginning()`이 적용된 상태에서 이전 테스트의 메시지가 잔류하면 N개 레코드가 조회된다.

### 영향
Finding 2(ddl-auto=create, DB 미정리)와 결합 시 테스트 실행 순서에 따라 간헐적 실패(flaky test).

### 수정
`KafkaTestUtils.getSingleRecord()` 대신 `orderId` key 기반 직접 폴링으로 교체하거나, `@BeforeEach`에서 Kafka consumer offset을 현재 끝으로 seekToEnd 후 메시지 발행하는 방식으로 변경.

---

## Finding 4 — git pull이 pre-pr-check 흐름을 깸 ⚠️

**파일**: `payment-service/.claude/commands/pre-pr-check.md:11`
**판정**: CONFIRMED

### 문제
```bash
# 변경 전 (안전)
git fetch origin

# 변경 후 (위험)
git pull
```

`git pull`은 `git fetch + git merge`이므로 두 가지 문제가 있다:

1. **upstream 없는 신규 브랜치**: `There is no tracking information for the current branch` 오류로 워크플로 1단계에서 중단됨
2. **발산 은폐**: fast-forward pull 성공 시 `git log origin/develop..HEAD` 카운트가 왜곡되어 실제 발산 여부를 은폐함
3. **merge commit 생성**: merge 전략 사용 시 의도치 않은 merge commit이 로컬 브랜치에 생성됨

`--ff-only` 등 보호 옵션도 없다.

### 수정
```bash
# pre-pr-check.md, create-pr.md 양쪽 모두
git fetch origin  # ← 원복
```

---

## Finding 5 — maxParallelForks가 static 컨테이너 공유를 무력화 ⚠️

**파일**: `build.gradle:62`
**판정**: CONFIRMED

### 문제
```groovy
// build.gradle:62
maxParallelForks = Math.max(1, Runtime.runtime.availableProcessors() / 2 as int)
```

Gradle `maxParallelForks`는 **별도 JVM 프로세스**를 생성한다. `static` 필드는 JVM 프로세스 경계를 넘지 않는다.

4코어 이상 머신에서 `maxParallelForks=2+`이면 `AbstractIntegrationTest.postgres` / `kafka`가 포크마다 독립 기동된다. 컨테이너 공유로 절약하려 했던 기동 비용이 포크 수만큼 다시 증가한다.

### 영향
효율 저하 (테스트 정확성 버그는 아님). 4코어 머신에서 PostgreSQL + Kafka 컨테이너가 2벌 기동.

### 수정 옵션
- `maxParallelForks = 1` (통합 테스트는 직렬 실행, 단위 테스트만 병렬화)
- 또는 통합 테스트 클래스를 별도 Gradle task로 분리하여 단위 테스트만 `maxParallelForks` 적용

---

## Finding 6 — PAY004→HTTP 400 ExceptionHandler 매핑 테스트 공백

**파일**: `src/test/java/com/prompthub/paymentservice/presentation/PaymentControllerTest.java`
**판정**: CONFIRMED

### 문제
제거된 `RefundPaymentIntegrationTest.PAID_아닌_상태_환불_요청_시_400()`이 유일하게 검증하던 경로:

```
REFUND_NOT_ALLOWED 예외 → ExceptionHandler → HTTP 400 + "code": "PAY004"
```

- `RefundPaymentServiceTest.PAID_아닌_상태_환불_시_PAY004_예외()` — 서비스 레이어 예외 발생은 검증
- `PaymentControllerTest` — PAY004 관련 환불 테스트 없음 (403/PAY007만 존재)

### 영향
`PaymentErrorCode.REFUND_NOT_ALLOWED`의 HTTP 상태 코드가 변경되어도 어떤 테스트도 감지하지 못한다.

### 수정
`PaymentControllerTest`에 추가:
```java
@Test
void PAID_아닌_상태_환불_시_400_PAY004() {
    when(refundPaymentUseCase.refund(any()))
        .thenThrow(new BusinessException(PaymentErrorCode.REFUND_NOT_ALLOWED));
    // ... HTTP 400, code=PAY004 검증
}
```

---

## Finding 7 — springdoc 3.0.0 하드코딩, BOM 미관리

**파일**: `build.gradle:51`
**판정**: PLAUSIBLE

### 문제
```groovy
// 변경 전
implementation 'org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.9'

// 변경 후
implementation 'org.springdoc:springdoc-openapi-starter-webmvc-api:3.0.0'
```

- 버전 `3.0.0`이 Spring Cloud BOM에 포함되지 않아 **수동 고정**
- `3.0.0`이 Spring Boot 4.0.x 기준으로 빌드되었다면 4.1.x의 내부 API 변경으로 `BeanCreationException` / `NoSuchMethodError` 발생 가능
- Spring Boot 버전 업그레이드 시 자동 조율 안 됨

### 수정
springdoc 최신 안정 버전 확인 후 Spring Boot 4.1.x 호환 버전으로 명시. 가능하다면 BOM 관리 추가.

---

## Finding 8 — create-pr.md도 git pull로 변경됨

**파일**: `payment-service/.claude/commands/create-pr.md:12`
**판정**: CONFIRMED

Finding 4와 동일한 원인. PR 생성 흐름 전체에 전파.

```bash
# create-pr.md 0단계
git pull  ← git fetch origin 으로 원복 필요
```

---

## Finding 9 — getBody() null 검사 누락

**파일**: `src/test/java/com/prompthub/paymentservice/RefundPaymentIntegrationTest.java:101`
**판정**: PLAUSIBLE

### 문제
```java
// RefundPaymentIntegrationTest.java:101
assertThat((Boolean) refundResponse.getBody().get("success")).isTrue();

// ConfirmPaymentIntegrationTest.java:101 — 동일 패턴
assertThat((Boolean) response.getBody().get("success")).isTrue();
```

API가 빈 바디(5xx, Content-Type 불일치)를 반환하면 `getBody()`가 `null`을 반환하고 `NullPointerException`이 발생한다. 실제 실패 원인 대신 NPE 스택트레이스만 노출되어 디버깅을 방해한다.

### 수정
```java
assertThat(response.getBody()).isNotNull();
assertThat((Boolean) response.getBody().get("success")).isTrue();
```

---

## Finding 10 — optional:configserver 테스트 기동 지연

**파일**: `src/main/resources/application.yaml:7`
**판정**: PLAUSIBLE

### 문제
```yaml
# 변경 전 — 연결 시도 차단
cloud:
  config:
    enabled: false

# 변경 후 — 연결 시도 발생
config:
  import:
    - optional:configserver:http://localhost:8888
```

`optional:` prefix는 실패 시 무시하지만 **연결 시도 자체는 발생**한다. `spring-cloud-starter-config`가 classpath에 있으면 Bootstrap 컨텍스트가 Config Server에 TCP 연결을 시도하고 타임아웃을 기다린다. 테스트 컨텍스트 2~4회 로딩 시 타임아웃이 중첩된다.

### 수정
`application-test.yml`에 추가:
```yaml
spring:
  cloud:
    config:
      enabled: false
```

---

## 즉시 수정 권고 (1~4번)

```
1. application-test.yml에 eureka.client.enabled: false 추가
2. 통합 테스트 @BeforeEach에 deleteAll() 추가
3. ConfirmPaymentIntegrationTest seekToBeginning + getSingleRecord 방식 개선
4. pre-pr-check.md / create-pr.md에서 git pull → git fetch origin 원복
```
