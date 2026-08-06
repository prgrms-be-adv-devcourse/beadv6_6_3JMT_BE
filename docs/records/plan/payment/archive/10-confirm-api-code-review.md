# 결제 승인 API 코드 리뷰 반영 계획

PR #41 junhee-ko 리뷰 반영. 현재 코드 기준으로 적용 가능한 항목만 선별.

---

## 배경

- 대상 PR: `feat: 결제 승인 API 구현 (#29)` (PR #41)
- 리뷰어: junhee-ko (총 11개 코멘트)
- 제외 항목: race condition 중복 저장(이미 `saveAndFlush + DataIntegrityViolationException`으로 해결), 53라인 save 불필요(이미 `saveAndFlush`로 교체됨), partitions/replicas 학습 권고(코드 변경 없음)

---

## 변경 범위

| # | 항목 | 파일 | 난이도 |
|---|---|---|---|
| 1 | `PAYMENT_FAILED` HTTP 400 → 422 | `PaymentErrorCode` | 낮음 |
| 2 | `TossConfirmResult` → `ConfirmResult` 이름 변경 | 5개 파일 | 낮음 |
| 3 | `TossRefundResult` → `RefundResult` 이름 변경 | 3개 파일 | 낮음 |
| 4 | `TossPaymentGateway` 방어 로직 추가 | `TossPaymentGateway` | 중간 |
| 5 | `ConfirmPaymentService` 트랜잭션 분리 | `ConfirmPaymentService`, 테스트 | 높음 |
| 6 | `ConfirmPaymentService` PG 실패 로깅 추가 | `ConfirmPaymentService` | 낮음 |
| 7 | `KafkaPaymentEventPublisher` 승인 발행 결과 로깅 | `KafkaPaymentEventPublisher` | 낮음 |
| 8 | Toss 서버 오류성 4xx를 `PAYMENT_FAILED` 대신 `PG_ERROR`로 분류 | `TossPaymentGateway` | 낮음 |

---

## 상세 구현

### 1. `PAYMENT_FAILED` HTTP 상태 코드 수정

**파일**: `application/exception/PaymentErrorCode.java`

**이유**: Toss 4xx 응답은 클라이언트 요청 파라미터 오류가 아닌 PG사의 결제 거절(카드 한도, 잔액 부족 등)이 원인인 경우도 포함된다. "입력값이 잘못된" 의미의 400보다 "요청은 유효하나 처리 불가"인 422가 더 정확하다.

```java
// 변경 전
PAYMENT_FAILED(HttpStatus.BAD_REQUEST, "PAY_FAILED", "PG사 결제가 실패했습니다."),

// 변경 후
PAYMENT_FAILED(HttpStatus.UNPROCESSABLE_ENTITY, "PAY_FAILED", "PG사 결제가 실패했습니다."),
```

---

### 2 & 3. Result 타입 이름 변경

**이유**: `PaymentGateway` interface가 `TossConfirmResult`, `TossRefundResult`를 반환하면 interface가 사실상 "Toss 전용 게이트웨이"가 된다. Toss 외 PG사 추가 계획은 없지만 추상화 원칙 준수.

**변경 파일 목록**:

| 구 이름 | 신 이름 | 위치 |
|---|---|---|
| `TossConfirmResult.java` | `ConfirmResult.java` | `application/gateway/external/` |
| `TossRefundResult.java` | `RefundResult.java` | `application/gateway/external/` |
| `PaymentGateway.java` | 반환 타입 참조 변경 | `application/gateway/external/` |
| `TossPaymentGateway.java` | 반환 타입 참조 변경 | `infrastructure/external/toss/` |
| `ConfirmPaymentService.java` | 참조 변경 | `application/service/` |
| `RefundPaymentService.java` | 참조 변경 | `application/service/` |
| `ConfirmPaymentServiceTest.java` | import 변경 | `test/.../application/service/` |

**변경 내용** (예시 — `PaymentGateway`):
```java
// 변경 전
TossConfirmResult confirm(String paymentKey, UUID orderId, int amount);
TossRefundResult refund(String pgTxId, UUID paymentId, int amount);

// 변경 후
ConfirmResult confirm(String paymentKey, UUID orderId, int amount);
RefundResult refund(String pgTxId, UUID paymentId, int amount);
```

---

### 4. `TossPaymentGateway` 방어 로직 추가

#### 4-a. connection/read timeout 설정

**파일**: `infrastructure/external/toss/TossPaymentGateway.java` 생성자

```java
// 추가: import
import java.time.Duration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

// 변경 전
this.restClient = RestClient.builder()
    .baseUrl(baseUrl)
    .defaultHeader("Authorization", "Basic " + credentials)
    .build();

// 변경 후
SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
factory.setConnectTimeout(Duration.ofSeconds(5));
factory.setReadTimeout(Duration.ofSeconds(10));
this.restClient = RestClient.builder()
    .baseUrl(baseUrl)
    .defaultHeader("Authorization", "Basic " + credentials)
    .requestFactory(factory)
    .build();
```

#### 4-b. `confirm()` response null 체크

```java
// 변경 전
TossConfirmResponse response = restClient.post()
    ...
    .body(TossConfirmResponse.class);

return new ConfirmResult(
    response.method(),  // ← NPE 가능
    ...
);

// 변경 후
TossConfirmResponse response = restClient.post()
    ...
    .body(TossConfirmResponse.class);

if (response == null) {
    throw new PaymentGatewayException(
        PaymentErrorCode.PG_ERROR, "NULL_RESPONSE", "PG사 응답이 없습니다.", requestJson, null
    );
}
return new ConfirmResult(response.method(), ...);
```

#### 4-c. `parseError()` IOException 로깅

```java
// 변경 전
} catch (IOException e) {
    return new TossErrorResponse("UNKNOWN", "PG사 응답 파싱 실패");
}

// 변경 후
} catch (IOException e) {
    log.warn("Toss 에러 응답 파싱 실패 — cause={}", e.getMessage(), e);
    return new TossErrorResponse("UNKNOWN", "PG사 응답 파싱 실패");
}
```

---

### 5. `ConfirmPaymentService` 트랜잭션 분리

**이유**: `@Transactional` 메서드 내 Toss API 호출로 인해 외부 API 응답 대기 시간 동안 DB 커넥션이 점유된다.

**전략**: `TransactionTemplate` 프로그래매틱 트랜잭션 관리. 별도 클래스 추가 없음.

**제약 사항 (3-b 결정)**: TX1 커밋 후 프로세스 장애 시 `REQUESTED` 상태 레코드가 잔존할 수 있다. 별도 스케줄러 없이 운영 정책으로 관리한다.

#### 구조 변경

```
AS-IS:
@Transactional confirm() {
    중복 체크 → save → markRequested → [Toss API] → approve/fail → 이벤트
    (전체가 하나의 TX, Toss API 호출 중 DB 커넥션 점유)
}

TO-BE:
confirm() {   ← @Transactional 제거
    TX1 (transactionTemplate): 중복 체크 → saveAndFlush → markRequested → save → 커밋
                               (DB 커넥션 반납)
    Toss API 호출              (TX 밖)
    TX2 (transactionTemplate): approve → save → 이벤트 발행 → 커밋
    (실패 시) TX3 (transactionTemplate): fail → save → 커밋
}
```

#### 생성자 변경

```java
// 추가 import
import org.springframework.transaction.support.TransactionTemplate;

// 생성자에 TransactionTemplate 추가
public ConfirmPaymentService(
    PaymentRepository paymentRepository,
    PaymentGateway paymentGateway,
    ApplicationEventPublisher applicationEventPublisher,
    TransactionTemplate transactionTemplate,
    @Value("${payment.toss.test-mode:false}") boolean testMode
)
```

#### `confirm()` 메서드 구조

```java
@Slf4j
@Service  // @Transactional 제거
public class ConfirmPaymentService implements ConfirmPaymentUseCase {

    @Override
    public PaymentResult confirm(ConfirmPaymentCommand command) {
        String idempotencyKey = "pay-" + command.orderId();

        // TX1: 결제 초기화 (커밋 후 DB 커넥션 반납)
        UUID paymentId;
        try {
            paymentId = transactionTemplate.execute(status -> {
                paymentRepository.findByIdempotencyKey(idempotencyKey)
                    .ifPresent(p -> { throw new BusinessException(PaymentErrorCode.DUPLICATE_PAYMENT); });

                Payment payment = Payment.create(
                    command.orderId(), command.userId(),
                    command.paymentKey(), PgProvider.TOSS_PAYMENTS, PaymentMethodType.CARD, testMode,
                    command.amount(), 0
                );
                paymentRepository.saveAndFlush(payment);  // unique constraint 즉시 반영
                payment.markRequested(OffsetDateTime.now());
                paymentRepository.save(payment);
                return payment.getId();
            });
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(PaymentErrorCode.DUPLICATE_PAYMENT);
        }

        // Toss API 호출 (트랜잭션 밖 — DB 커넥션 미점유)
        try {
            ConfirmResult result = paymentGateway.confirm(
                command.paymentKey(), command.orderId(), command.amount()
            );

            // TX2: 승인 결과 반영
            return transactionTemplate.execute(status -> {
                Payment payment = paymentRepository.findById(paymentId).orElseThrow();
                payment.approve(result.approvedAmount(), result.paymentMethod(),
                    result.responsePayload(), result.approvedAt());
                paymentRepository.save(payment);
                applicationEventPublisher.publishEvent(new PaymentApprovedEvent(payment));
                return new PaymentResult(payment.getId());
            });

        } catch (PaymentGatewayException e) {
            log.error("PG사 결제 실패 — paymentKey={}, tossCode={}, reason={}",
                command.paymentKey(), e.getFailureCode(), e.getFailureReason(), e);

            // TX3: 실패 반영 (3-b: REQUESTED 잔존 가능성 허용, 스케줄러 없음)
            transactionTemplate.execute(status -> {
                Payment payment = paymentRepository.findById(paymentId).orElseThrow();
                payment.fail(e.getFailureCode(), e.getFailureReason(),
                    e.getRequestPayload(), e.getResponsePayload(), OffsetDateTime.now());
                paymentRepository.save(payment);
                return null;
            });

            throw new BusinessException(e.getErrorCode(), e.getFailureReason());
        }
    }
}
```

#### 상수 추출 (리뷰 #5)

TX1 내부의 `"TOSS_PAYMENTS"`, `"CARD"` 리터럴을 상수로 추출한다.

```java
// ConfirmPaymentService 내부 상수 (또는 별도 enum/상수 클래스)
private static final String PG_PROVIDER = "TOSS_PAYMENTS";
private static final String PAYMENT_METHOD = "CARD";
```

---

### 6. `ConfirmPaymentService` PG 실패 로깅

위 5번 구현 시 TX3 catch 블록에 이미 포함됨 (`log.error(..., e)`).

---

### 7. `KafkaPaymentEventPublisher.onPaymentApproved` 결과 로깅

**이유**: `publishRefunded()`는 `whenComplete` 핸들러로 발행 결과를 로깅하지만, `onPaymentApproved()`는 fire-and-forget이다. 동일한 패턴으로 일관성 확보.

```java
// 변경 전
kafkaTemplate.send(PaymentTopic.PAYMENT_APPROVED, payment.getOrderId().toString(), message);

// 변경 후
kafkaTemplate.send(PaymentTopic.PAYMENT_APPROVED, payment.getOrderId().toString(), message)
    .whenComplete((result, ex) -> {
        if (ex != null) {
            log.error("결제 승인 Kafka 메시지 발행 실패 — paymentId={}, cause={}",
                payment.getId(), ex.getMessage());
        } else {
            log.info("결제 승인 Kafka 메시지 발행 성공 — paymentId={}, partition={}, offset={}",
                payment.getId(),
                result.getRecordMetadata().partition(),
                result.getRecordMetadata().offset());
        }
    });
```

---

### 8. Toss 4xx 에러 코드 분류

**파일**: `infrastructure/external/toss/TossPaymentGateway.java`

**이유**: Toss 4xx 응답 안에는 성격이 다른 두 종류가 존재한다.

| 종류 | 예시 코드 | 원인 |
|---|---|---|
| 결제 거절 | `REJECT_CARD_PAYMENT`, `EXCEED_MAX_DAILY_PAYMENT_COUNT` | 카드사/은행이 거절 — 정상 비즈니스 실패 |
| 서버 오류성 | `INVALID_REQUEST`, `INVALID_API_KEY`, `NOT_FOUND_PAYMENT` | 우리 서버가 잘못된 요청을 전송 |

서버 오류성 4xx를 `PAYMENT_FAILED`(422)로 클라이언트에 내리면 "요청이 처리 불가"라는 잘못된 시그널을 준다. 서버 오류성 코드는 `PG_ERROR`(502)로 올려 운영 알람 기준을 맞춘다.

**전략**: 서버 오류성 Toss 에러 코드 목록(`TOSS_SERVER_ERROR_CODES`)을 상수 Set으로 관리. 목록에 포함된 코드는 `PG_ERROR`, 나머지는 기존대로 `PAYMENT_FAILED`.

```java
private static final Set<String> TOSS_SERVER_ERROR_CODES = Set.of(
    "INVALID_REQUEST",
    "INVALID_API_KEY",
    "UNAUTHORIZED_KEY",
    "FORBIDDEN_REQUEST",
    "NOT_FOUND_PAYMENT",
    "NOT_FOUND_PAYMENT_SESSION",
    "ALREADY_PROCESSED_PAYMENT"
);
```

```java
// confirm() 4xx 핸들러 변경
.onStatus(HttpStatusCode::is4xxClientError, (req, resp) -> {
    TossErrorResponse error = parseError(resp);
    PaymentErrorCode errorCode = TOSS_SERVER_ERROR_CODES.contains(error.code())
        ? PaymentErrorCode.PG_ERROR
        : PaymentErrorCode.PAYMENT_FAILED;
    throw new PaymentGatewayException(
        errorCode, error.code(), error.message(), requestJson, null
    );
})
```

**테스트**:
- `ConfirmPaymentServiceTest` — 게이트웨이가 `PG_ERROR`를 던졌을 때 서비스가 `BusinessException(PG_ERROR)`를 재발생시키는지 확인
- `PaymentControllerTest` — `PG_ERROR` 예외 시 HTTP 502 응답 확인

---

## 테스트 영향 분석

### `ConfirmPaymentServiceTest` — 수정 필요

`TransactionTemplate` 주입이 추가되므로 `setUp()`을 수정한다.

```java
// 추가 import
import org.springframework.transaction.support.ResourcelessTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@BeforeEach
void setUp() {
    // ResourcelessTransactionManager: 실제 TX 없이 콜백만 실행 (단위 테스트용)
    TransactionTemplate transactionTemplate =
        new TransactionTemplate(new ResourcelessTransactionManager());
    service = new ConfirmPaymentService(
        paymentRepository, paymentGateway, applicationEventPublisher, transactionTemplate, false
    );
}
```

TX2/TX3에서 `paymentRepository.findById(paymentId)` 호출이 추가되므로 각 테스트에 stub 추가:

```java
// 성공 케이스 테스트에 추가
when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
when(paymentRepository.findById(any(UUID.class))).thenAnswer(inv -> {
    Payment p = Payment.create(orderId, userId, "toss-key", "TOSS_PAYMENTS", "CARD", false, 10_000, 0);
    p.markRequested(OffsetDateTime.now());
    return Optional.of(p);
});
```

- `TossConfirmResult` → `ConfirmResult` import 변경 필요

### `ConfirmPaymentIntegrationTest` — 영향 없음

Testcontainers 기반 통합 테스트는 실제 `PlatformTransactionManager`가 주입되므로 수정 불필요.

### `PaymentControllerTest` — 영향 없음

`ConfirmPaymentUseCase` interface를 mock하므로 서비스 내부 변경 영향 없음.

---

## 구현 순서

낮은 위험도 → 높은 위험도 순서로 진행한다.

```
1. PaymentErrorCode PAYMENT_FAILED 400 → 422
2. TossConfirmResult → ConfirmResult 이름 변경
3. TossRefundResult → RefundResult 이름 변경
4. TossPaymentGateway timeout / null 체크 / 로깅
5. KafkaPaymentEventPublisher whenComplete 추가
6. ConfirmPaymentService 트랜잭션 분리 + 상수 추출 + 로깅
7. ConfirmPaymentServiceTest 수정
8. ./gradlew test 실행 및 결과 확인
```

---

## 트레이드오프

### T1. `PAYMENT_FAILED` HTTP 400 → 422

| | 내용 |
|---|---|
| **선택** | 422 UNPROCESSABLE_ENTITY |
| **얻는 것** | "요청 형식은 맞지만 PG사가 처리를 거부"라는 의미가 명확해짐. 클라이언트가 4xx를 받아도 "내 요청 파라미터 오류"와 "결제 자체 거절"을 구분 가능 |
| **잃는 것** | Toss 4xx 안에는 `INVALID_REQUEST`처럼 진짜 우리 서버가 잘못 보낸 케이스도 포함된다. 422로 통일하면 그 케이스에도 "처리 불가" 시그널을 주게 됨 |
| **대안** | Toss 에러 코드별로 400/422를 분기 — 정확하지만 Toss 에러 코드 전체 목록 파악 후 매핑 테이블 관리 필요. 이번 범위 초과로 보류 |
| **전제** | Toss 4xx 중 우리 서버 버그(잘못된 파라미터 전송)는 운영 중 로그로 식별 가능하다고 가정 |
| **후속 결정 (#8)** | 서버 오류성 4xx를 `PAYMENT_FAILED`(422) 대신 `PG_ERROR`(502)로 분류하는 로직을 추가하여 해소. 새 `ErrorCode` 추가 없이 기존 `PG_ERROR` 재활용 (변경 범위 최소화). |

---

### T2. `TossConfirmResult` → `ConfirmResult` 이름 변경

| | 내용 |
|---|---|
| **선택** | 이름 변경 (추상화 유지) |
| **얻는 것** | `PaymentGateway` interface가 특정 PG사에 종속되지 않음. 코드를 읽는 사람이 "이 gateway는 Toss 전용이다"라고 오해하지 않음 |
| **잃는 것** | 실제로 Toss 외 다른 PG사 추가 계획이 없는 상황에서 이름 변경만으로는 추상화 효과가 제한적. 필드 구조(`responsePayload`, `approvedAt`)는 여전히 Toss 응답 구조에 맞춰져 있음 |
| **대안** | 이름 유지 — Toss 전용임을 명시적으로 드러내는 방식. 추가 PG사가 없다면 오히려 의도가 명확 |
| **전제** | 추상화는 당장의 이점보다 미래 변경 비용을 낮추는 데 의미가 있다. 다른 PG사 추가 시 Result 타입 재설계는 어차피 필요하므로 이름 변경의 실질적 이득은 낮음 |

---

### T3. `TransactionTemplate` 프로그래매틱 트랜잭션 분리

| | 내용 |
|---|---|
| **선택** | `@Transactional` 제거 + `TransactionTemplate` 명시적 TX 블록 |
| **얻는 것** | Toss API 호출 중 DB 커넥션 미점유. TX 경계가 코드에 명시적으로 드러나 가독성 향상 |
| **잃는 것** | 코드 복잡도 증가. 선언적 `@Transactional`보다 람다 블록이 많아 가독성 저하 가능. 단위 테스트에 `TransactionTemplate` 초기화 코드 추가 필요 |
| **대안 A** | 현상 유지(`@Transactional` 유지) — Toss API 응답이 통상 300ms~1s이고 DB 커넥션 풀이 충분하면 실제 문제가 발생하지 않을 수 있음. 코드 변경 없음 |
| **대안 B** | 별도 `ConfirmPaymentTransactor` 클래스 분리 — self-invocation 없이 `@Transactional` 사용 가능. 클래스 1개 추가 비용으로 선언적 TX 유지 |
| **전제** | 결제 서비스는 동시 요청이 많을 수 있고, Toss 네트워크 지연이 길어질 경우 DB 커넥션 풀 고갈로 전체 서비스 지연이 발생할 수 있음 |

---

### T4. REQUESTED 잔존 허용 (3-b)

| | 내용 |
|---|---|
| **선택** | TX1 커밋 후 장애 시 REQUESTED 상태 레코드 잔존 허용. 스케줄러 미추가 |
| **얻는 것** | 이번 PR 범위를 결제 흐름 개선에 집중. 스케줄러 설계·테스트 비용 없음 |
| **잃는 것** | TX1(초기화 커밋) 성공 후 프로세스 크래시 시, REQUESTED 레코드가 영구적으로 남음. 동일 orderId로 재결제 시도 시 `DUPLICATE_PAYMENT` 오류 반환 — 사용자가 결제를 다시 시도할 수 없음 |
| **대안** | REQUESTED 상태가 N분 이상 지속되는 레코드를 FAILED로 처리하는 스케줄러 추가 (3-a). 운영 안정성은 높아지지만 설계 복잡도 증가 |
| **완화 방법** | 운영 모니터링에서 REQUESTED 상태 레코드를 주기적으로 확인. 발생 시 DB에서 수동 처리 또는 후속 이슈로 스케줄러 구현 |

---

### T5. `BusinessException` cause 미포함

| | 내용 |
|---|---|
| **선택** | `common-module` 수정 없이 `ConfirmPaymentService`에서 `log.error(..., e)`로 cause 보존 |
| **얻는 것** | `common-module` 변경으로 인한 팀 전체 영향 없음. 이번 PR 범위 내에서 해결 가능 |
| **잃는 것** | `BusinessException`을 catch한 코드(다른 서비스, 공통 모듈)에서 원인 예외를 꺼낼 수 없음. 스택트레이스가 `PaymentExceptionHandler`에 전달되지 않음 |
| **대안** | `common-module`에 `BusinessException(ErrorCode, String, Throwable)` 생성자 추가 — 근본적 해결이나 팀 협의 필요 |
| **전제** | PG 실패 원인은 `ConfirmPaymentService`의 로그에서 충분히 추적 가능하다고 가정 |

---

## 비고

- `common-module`의 `BusinessException`에는 cause 생성자가 없다. 수정 시 팀 전체 영향이므로 이번 범위에서 제외한다. PG 실패 디버깅 정보는 `ConfirmPaymentService`의 `log.error(..., e)`로 보존한다.
- REQUESTED 상태 잔존 처리(3-b): 스케줄러 미추가. 운영 중 REQUESTED 상태가 일정 시간(예: 10분) 이상 유지되는 레코드는 수동 또는 후속 이슈로 관리한다.

---

## 멘토링 질문

### 배경

결제 승인 흐름을 아래와 같이 트랜잭션 3개로 분리했습니다.

```
TX1 커밋: Payment 생성 + REQUESTED 상태 저장
          ↓ (DB 커넥션 반납)
          Toss API 호출  ← 이 시점에 프로세스가 죽으면 TX2/TX3 미실행
          ↓
TX2 커밋: approve + PAID 상태 저장 + Kafka 이벤트 발행
TX3 커밋: fail  + FAILED 상태 저장        (PG사 실패 시)
```

TX1과 TX2/TX3 사이에서 프로세스가 죽으면, REQUESTED 상태 레코드가 DB에 영구적으로 남습니다. 동일 orderId로 재결제를 시도하면 `DUPLICATE_PAYMENT(409)` 오류가 반환되어 사용자가 결제를 재시도할 수 없게 됩니다.

### 질문

**Q1. 이 구조 자체가 안티패턴인가요?**

"TX1 커밋 → 외부 API 호출 → TX2 커밋" 구조는 두 TX 사이에 원자성을 보장할 수 없습니다. 결제 서비스에서 이 구조가 일반적으로 사용되는지, 아니면 더 나은 설계 방향이 있는지 궁금합니다.

**Q2. orphan 레코드를 처리하는 표준적인 방법이 있나요?**

현재 고려 중인 방법은 "REQUESTED 상태가 N분 이상 지속된 레코드를 스케줄러가 FAILED로 처리"입니다. 이 접근의 문제점은:

- N분 기준을 어떻게 정해야 하는가? (Toss API의 결제 유효 시간과 맞춰야 하는가?)
- FAILED로 변경한 후에도 Toss 측에서 실제로 결제가 승인됐을 가능성이 있는가? 이 경우 어떻게 처리해야 하는가?

**Q3. Outbox 패턴이 이 문제에 적용 가능한가요?**

"Toss에 결제 승인을 요청해야 한다"는 의도 자체를 TX1 안에서 DB에 기록하고, 별도 워커가 해당 레코드를 읽어 Toss API를 호출하는 방식(Outbox 패턴)을 생각해봤습니다. 이 경우:

- Toss의 멱등성 키(`paymentKey`)를 활용해 중복 호출을 방지할 수 있는가?
- 결제 서비스처럼 PG사 호출 결과가 즉시 응답에 포함되어야 하는 동기 API에 Outbox 패턴을 적용하는 것이 현실적인가? (사용자에게 "처리 중" 응답 후 polling 방식으로 전환해야 하는가?)

**Q4. 이 문제를 해결하지 않고 운영하는 것이 현실적으로 허용되는 수준인가요?**

프로세스 크래시는 드문 이벤트이고, 발생 시 운영자가 수동으로 REQUESTED 레코드를 처리하는 방식이 소규모 서비스에서 실용적인 선택이 될 수 있는지 궁금합니다. 또는 이 수준의 일관성 보장은 결제 서비스의 필수 요건인지요?
