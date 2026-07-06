# 테스트 성능 개선 실행 결과

`07-test-performance-improvement.md` 계획의 전체 실행 기록 및 추가 발견 수정 내역.

---

## 실행 결과 요약

| 커밋 | 작업 | 단축 효과 |
|---|---|---|
| `c14e768` | Phase 4: 불필요 통합 테스트 2개 제거 | Thread.sleep 3초 즉시 제거 |
| `bd129d7` | Phase 1: 테스트 프로파일 + 병렬 실행 | SQL 로깅 제거, 단위 테스트 병렬화 |
| `7cfc800` | Phase 2: 공유 기반 클래스 도입 | 컨테이너 6→2회, 컨텍스트 4→2회 |
| `9bd02e9` | Phase 3: Awaitility + Kafka 폴링 단축 | 가변 대기 전환, 최대 대기 30→10초 |
| `58f8487` | 추가 발견: 종료 지연 제거 | 테스트 후 47초 대기 제거 |

**전체 실행 시간: 약 55~115초 → 43초**

---

## Phase 4 — 불필요 통합 테스트 제거

### 배경

단위 테스트(`@ExtendWith(MockitoExtension.class)`)로 이미 커버되는 케이스가 통합 테스트로도 존재.
통합 테스트는 컨테이너 기동 비용이 크므로 중복 제거가 우선.

### 제거된 테스트

| 파일 | 메서드 | 제거 이유 |
|---|---|---|
| `ConfirmPaymentIntegrationTest` | `중복_결제_요청_시_409()` | 단위 테스트에서 검증됨 |
| `RefundPaymentIntegrationTest` | `PAID_아닌_상태_환불_요청_시_400()` | 단위 테스트에서 검증됨 + `Thread.sleep(3_000)` 포함 |

---

## Phase 1 — 테스트 환경 설정 개선

### 신규 파일

**`src/test/resources/application-test.yml`**

```yaml
spring:
  jpa:
    show-sql: false
    properties:
      hibernate:
        format_sql: false
```

프로덕션 `application.yaml`의 `show-sql=true`·`format_sql=true`가 테스트에도 적용되어
모든 SQL이 포맷팅 출력되던 I/O 오버헤드 제거.

### 변경 파일

**`build.gradle`** — 병렬 실행 활성화

```gradle
tasks.named('test') {
    useJUnitPlatform()
    maxParallelForks = Math.max(1, Runtime.runtime.availableProcessors() / 2 as int)
}
```

Groovy DSL에서 Kotlin의 `coerceAtLeast`는 사용 불가. `Math.max`로 대체.

### @ActiveProfiles("test") 추가 대상

`ConfirmPaymentIntegrationTest`, `RefundPaymentIntegrationTest`,
`PaymentJpaRepositoryTest`, `RefundJpaRepositoryTest` 4개 클래스.

---

## Phase 2 — 공유 기반 클래스 도입

### 도입 배경

테스트 클래스마다 Testcontainers 컨테이너를 독립적으로 기동하면 클래스 스코프(`static`)라도
**클래스 간 공유가 안 됨** → `@DynamicPropertySource`가 서로 다른 인스턴스를 등록 →
Spring이 컨텍스트를 별개로 판단해 각각 새로 로드.

### AbstractIntegrationTest

```
src/test/java/com/prompthub/paymentservice/support/AbstractIntegrationTest.java
```

- `@SpringBootTest(webEnvironment = RANDOM_PORT)`
- `@Testcontainers` + `@ActiveProfiles("test")`
- `public static PostgreSQLContainer<?> postgres` — **public** 필수
  - 하위 클래스(`ConfirmPaymentIntegrationTest`, `RefundPaymentIntegrationTest`)가
    다른 패키지에서 `kafka.getBootstrapServers()` 직접 참조하므로 `public` 아니면 컴파일 에러
- `public static KafkaContainer kafka`

### AbstractJpaTest

```
src/test/java/com/prompthub/paymentservice/support/AbstractJpaTest.java
```

- `@SpringBootTest(webEnvironment = NONE, properties = {"spring.autoconfigure.exclude=...KafkaAutoConfiguration"})`
- Kafka 자동 구성 제외 → `KafkaTemplate` 빈 없음 → `KafkaPaymentEventPublisher` 주입 실패
- **`@MockitoBean KafkaTemplate kafkaTemplate`** 추가 필수 (raw 타입으로 선언, `@SuppressWarnings("rawtypes")`)
- `static PostgreSQLContainer<?> postgres` — JPA 테스트끼리만 공유하므로 `public` 불필요

---

## Phase 3 — 비동기 대기 구조 개선

### Awaitility 의존성 추가

```gradle
testImplementation 'org.awaitility:awaitility'
```

### Thread.sleep 제거

**`RefundPaymentIntegrationTest.PG_환불_실패_시_PAID_복원()`**

```java
// 변경 전
Thread.sleep(3_000);

// 변경 후 — AFTER_COMMIT 처리 완료를 조건부 대기
await()
    .atMost(Duration.ofSeconds(5))
    .pollInterval(Duration.ofMillis(200))
    .untilAsserted(() -> {
        Payment restored = paymentJpaRepository.findById(payment.getId()).orElseThrow();
        assertThat(restored.getStatus()).isEqualTo(PaymentStatus.PAID);
    });
```

### Kafka 폴링 타임아웃 단축

`환불_정상_플로우` 테스트의 Kafka 메시지 수신 대기: 30초 → 10초

```java
long deadline = System.currentTimeMillis() + 10_000;
// ...
assertThat(found).withFailMessage("10초 내 payment.refunded Kafka 메시지 수신 실패").isTrue();
```

---

## 추가 발견 — 테스트 종료 후 47초 지연

### 증상

테스트 자체는 빠르게 완료되었으나 빌드 종료까지 약 47초 추가 대기.

```
10:22:21 → > Task :test   ← 테스트 완료
10:22:21 → HikariPool-1 - request timed out after 30007ms
10:22:29 → HikariPool-1 - request timed out after 30006ms
10:22:34 → HikariPool-1 - request timed out after 30002ms
10:23:04 → HikariPool-2 - request timed out after 30004ms
10:23:09 → BUILD SUCCESSFUL
```

### 원인

`ddl-auto=create-drop` 전략은 Spring `ApplicationContext` 종료 시 `entityManagerFactory.destroy()`에서 `DROP TABLE`을 실행한다.
그런데 Testcontainers(Ryuk)가 먼저 PostgreSQL 컨테이너를 중지하므로, Hibernate가 DROP을 시도하는 시점엔 DB가 이미 없다.
HikariCP 기본 연결 타임아웃(30초)이 만료될 때까지 대기 후 포기.

JVM 프로세스 3개가 병렬 실행 중이고, 하나는 컨텍스트 2개(HikariPool-1·2)를 가져 30초 대기가 두 번 발생.

### 해결

`AbstractIntegrationTest`·`AbstractJpaTest` 양쪽에서 `create-drop` → `create`로 변경.

Testcontainers는 컨테이너 자체를 삭제하므로 DROP TABLE 실행이 불필요하다.
`create` 전략은 종료 시 DDL을 실행하지 않아 즉시 shutdown 완료.

```java
registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");  // create-drop 에서 변경
```

**추가**: `application-test.yml`에 `open-in-view: false` 추가 (WARN 로그 제거).

```yaml
spring:
  jpa:
    open-in-view: false
    show-sql: false
    properties:
      hibernate:
        format_sql: false
```

### 결과

| | 이전 | 이후 |
|---|---|---|
| 전체 실행 시간 | 1분 31초 | 43초 |
| 테스트 후 종료 대기 | ~47초 | ~0초 |
