# 테스트 실행 속도 개선 계획

## 배경 및 목표

전체 테스트 실행 시간이 약 55~115초로 과도하게 길다. 원인을 분석한 결과 아래 6가지 병목이 확인되었다.
이 중 안전하게 적용 가능한 항목부터 순차 적용해 **전체 시간을 50% 이상 단축**하는 것이 목표다.

---

## 병목 요약

| 순위 | 원인 | 추정 시간 | 위험도 |
|---|---|---|---|
| 1 | Kafka 폴링 30초 하드코딩 (`RefundPaymentIntegrationTest`) | 5~30초 | 낮음 |
| 2 | `Thread.sleep(3_000)` x2 (`RefundPaymentIntegrationTest`) | 6초 | 중간 |
| 3 | 테스트 클래스마다 Testcontainers 독립 시작 | 22~36초 | 낮음 |
| 4 | Spring ApplicationContext 4회 중복 로딩 | 20~40초 | 낮음 |
| 5 | `show-sql` + `format_sql` 테스트 환경에도 적용 | 1~2초 | 매우 낮음 |
| 6 | Gradle 병렬 실행 미설정 | 3~5초 (단위 테스트) | 낮음 |

---

## 개선 단계

### Phase 1 — 즉시 적용 (위험도 없음)

**목표**: 설정 변경만으로 달성 가능한 개선 적용

#### 1-1. `application-test.yml` 신규 생성

**파일**: `src/test/resources/application-test.yml` (신규)

```yaml
spring:
  jpa:
    show-sql: false
    properties:
      hibernate:
        format_sql: false
```

`@SpringBootTest` + `@ActiveProfiles("test")` 조합 또는 `spring.profiles.active=test` 설정 시 프로덕션 SQL 로깅이 테스트에 적용되지 않도록 분리한다.

> 주의: 기존 테스트 클래스에 `@ActiveProfiles("test")` 추가 또는 각 `@DynamicPropertySource`에서 명시적 비활성화 방식으로 적용한다.

---

#### 1-2. Gradle 병렬 실행 활성화

**파일**: `build.gradle`

```gradle
tasks.named('test') {
    useJUnitPlatform()
    maxParallelForks = (Runtime.runtime.availableProcessors() / 2).coerceAtLeast(1)
}
```

MockitoExtension 기반 단위 테스트 5개(`PaymentTest`, `PaymentControllerTest`, `ConfirmPaymentServiceTest`, `RefundPaymentServiceTest`, `PaymentControllerTest`)가 병렬 실행된다. Testcontainers 테스트는 컨텍스트 공유 구조가 갖춰진 뒤 병렬화 여부를 판단한다.

---

### Phase 2 — 핵심 개선 (컨테이너·컨텍스트 공유)

**목표**: Spring ApplicationContext와 Testcontainers 인스턴스를 클래스 간 공유해 중복 시작 비용 제거

#### 2-1. 공유 기반 클래스 도입

**파일**: `src/test/java/com/prompthub/paymentservice/support/AbstractIntegrationTest.java` (신규)

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
public abstract class AbstractIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static KafkaContainer kafka =
        new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.jpa.show-sql", () -> "false");
    }
}
```

`static` 컨테이너를 부모 클래스에서 한 번만 정의하면 JUnit5가 클래스 계층을 통해 공유한다. Spring은 `@DynamicPropertySource`가 동일하면 ApplicationContext를 캐싱하므로 **컨텍스트 재사용**이 일어난다.

#### 2-2. 통합 테스트 기반 클래스 교체

**파일**: `ConfirmPaymentIntegrationTest.java`

```java
// 변경 전
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ConfirmPaymentIntegrationTest {
    @Container
    static PostgreSQLContainer<?> postgres = ...;
    @Container
    static KafkaContainer kafka = ...;
    @DynamicPropertySource
    static void properties(...) { ... }  // 중복 정의

// 변경 후
class ConfirmPaymentIntegrationTest extends AbstractIntegrationTest {
    // 컨테이너·DynamicPropertySource 제거
```

**파일**: `RefundPaymentIntegrationTest.java` — 동일 패턴 적용

#### 2-3. JPA 테스트 공유 기반 클래스 분리

JPA 테스트는 Kafka가 불필요하므로 별도 기반 클래스를 만든다.

**파일**: `src/test/java/com/prompthub/paymentservice/support/AbstractJpaTest.java` (신규)

```java
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration"
    }
)
@Testcontainers
public abstract class AbstractJpaTest {

    @Container
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.jpa.show-sql", () -> "false");
    }
}
```

**파일**: `PaymentJpaRepositoryTest.java`, `RefundJpaRepositoryTest.java` — `extends AbstractJpaTest`로 교체, 중복 컨테이너·프로퍼티 선언 제거

---

### Phase 3 — 비동기 대기 구조 개선

**목표**: `Thread.sleep()`과 30초 Kafka 폴링을 조건부 대기로 교체

#### 3-1. Awaitility 의존성 추가

**파일**: `build.gradle`

```gradle
dependencies {
    testImplementation 'org.awaitility:awaitility:4.2.2'
}
```

Spring Boot BOM이 awaitility를 관리하므로 버전을 생략해도 된다.

#### 3-2. Thread.sleep 교체

**파일**: `RefundPaymentIntegrationTest.java`

```java
// 변경 전
try {
    Thread.sleep(3_000);
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
}
Payment restored = paymentJpaRepository.findById(payment.getId()).orElseThrow();
assertThat(restored.getStatus()).isEqualTo(PaymentStatus.PAID);

// 변경 후
await()
    .atMost(Duration.ofSeconds(5))
    .pollInterval(Duration.ofMillis(200))
    .untilAsserted(() -> {
        Payment restored = paymentJpaRepository.findById(payment.getId()).orElseThrow();
        assertThat(restored.getStatus()).isEqualTo(PaymentStatus.PAID);
    });
```

실제 상태 변경이 일어나는 즉시 다음 단계로 넘어가므로 평균 대기 시간이 0.2~1초로 줄어든다.

> 주의: `@TransactionalEventListener(AFTER_COMMIT)`이 별도 스레드에서 실행되므로 `@Transactional` 없는 환경에서 `paymentJpaRepository`를 직접 조회해야 한다. 기존 코드 구조 확인 후 적용.

#### 3-3. Kafka 폴링 타임아웃 단축

**파일**: `RefundPaymentIntegrationTest.java` (L138)

```java
// 변경 전
long deadline = System.currentTimeMillis() + 30_000;

// 변경 후
long deadline = System.currentTimeMillis() + 10_000;
```

Testcontainers Kafka에서 초기 연결에 3~5초가 걸리므로 10초가 안전한 최솟값이다. 충분히 안정된 것을 확인한 뒤 5초로 추가 단축을 검토한다.

---

## 수정 대상 파일 목록

### 신규 생성

| 파일 | 역할 |
|---|---|
| `src/test/resources/application-test.yml` | 테스트 전용 프로퍼티 (show-sql 비활성화) |
| `src/test/java/com/prompthub/paymentservice/support/AbstractIntegrationTest.java` | 통합 테스트 공유 기반 클래스 |
| `src/test/java/com/prompthub/paymentservice/support/AbstractJpaTest.java` | JPA 테스트 공유 기반 클래스 |

### 수정

| 파일 | 변경 내용 |
|---|---|
| `build.gradle` | `maxParallelForks` 추가, Awaitility 의존성 추가 |
| `ConfirmPaymentIntegrationTest.java` | `extends AbstractIntegrationTest`, 중복 컨테이너·프로퍼티 제거 |
| `RefundPaymentIntegrationTest.java` | `extends AbstractIntegrationTest`, `Thread.sleep` → Awaitility, Kafka 폴링 타임아웃 단축 |
| `PaymentJpaRepositoryTest.java` | `extends AbstractJpaTest`, 중복 컨테이너·프로퍼티 제거 |
| `RefundJpaRepositoryTest.java` | `extends AbstractJpaTest`, 중복 컨테이너·프로퍼티 제거 |

---

## 예상 개선 효과

| 항목 | 개선 전 | 개선 후 |
|---|---|---|
| Kafka 폴링 대기 | 5~30초 | 1~5초 |
| Thread.sleep() | 6초 | 0.2~1초 |
| 컨테이너 시작 횟수 | 6회 | 2회 (통합 1 + JPA 1) |
| Spring Context 로딩 | 4회 | 2회 |
| **전체 예상 시간** | **55~115초** | **20~35초** |

---

## 검증 방법

```bash
# 전체 테스트 실행 후 소요 시간 확인
./gradlew test --rerun-tasks

# 개별 테스트 클래스 실행
./gradlew test --tests "com.prompthub.paymentservice.ConfirmPaymentIntegrationTest"
./gradlew test --tests "com.prompthub.paymentservice.RefundPaymentIntegrationTest"

# 컨테이너 시작 횟수 확인 (로그에서 container start 횟수 카운트)
./gradlew test --info 2>&1 | grep -c "Starting container"
```

**합격 기준:**
- 전체 테스트 GREEN (기존 통과 테스트 모두 통과)
- 컨테이너 시작 로그가 2회 이하
- 전체 실행 시간 35초 이하

---

## 적용 순서 권장

```
Phase 1-2 (application-test.yml) → Phase 1-3 (Gradle 병렬) → 테스트 실행 확인
→ Phase 2-1/2-2 (AbstractIntegrationTest) → 테스트 실행 확인
→ Phase 2-3 (AbstractJpaTest) → 테스트 실행 확인
→ Phase 3-1/3-2 (Awaitility) → Phase 3-3 (Kafka 타임아웃) → 테스트 실행 확인
```

각 단계 후 테스트를 실행해 회귀를 조기에 발견한다.
