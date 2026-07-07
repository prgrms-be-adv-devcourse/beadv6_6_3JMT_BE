# 작업 계획: 결제 감사 로그

> **2026-07-05 조정 (00-execution-order.md)**: 실행 순서 **작업 6 (마지막)** — 작업 2(flow-redesign)·3(partial-refund)·4(circuit-breaker) 완료 후의 최종 코드에 계측한다. 이에 따라 계측 지점을 재산정: `RefundPaymentService.startRefunding()` 지점은 신 환불 모델에서 경로 자체가 소멸하여 삭제, 환불 전이 지점은 부분 환불 모델(`completePartialRefund`, Refund 단위 전이) 기준으로 갱신, `doConfirm()`/`doRefund()`는 작업 4가 생성한 뒤 계측. 총 계측 지점 16개 → **11개**.

## 결정 배경

| 항목 | 결정 |
|---|---|
| 목적 | 운영/개발팀의 장애 대응, 디버깅, 고객 문의 처리 시 결제 흐름 추적 |
| 추적 대상 | Payment/Refund 상태 전환 이력 + Toss PG API 호출 원문 |
| 저장 방식 | 별도 DB 테이블 없음 — 구조화 JSON 로그로 외부 시스템(ELK/CloudWatch 등)에 위임 |
| 구현 방식 | 서비스 계층 직접 로깅 (`AuditLogger` 빈 주입) |
| 로그 형식 | `logstash-logback-encoder` JSON |

---

## 이벤트 유형

| eventType | 발생 시점 | 핵심 필드 |
|---|---|---|
| `PAYMENT_STATUS_CHANGED` | Payment 상태 전환마다 | `paymentId`, `orderId`, `userId`, `prevStatus`, `nextStatus`, `source`, `occurredAt` |
| `REFUND_STATUS_CHANGED` | Refund 상태 전환마다 | `refundId`, `paymentId`, `userId`, `prevStatus`, `nextStatus`, `source`, `occurredAt` |
| `PG_CALL` | Toss API 호출 완료 후 (성공/실패 무관) | `operation`, `paymentId`, `pgTxId`, `success`, `failureCode`, `durationMs`, `requestPayload`, `responsePayload` |

### JSON 출력 예시 — PAYMENT_STATUS_CHANGED

```json
{
  "timestamp": "2026-07-05T12:00:00.123Z",
  "level": "INFO",
  "logger": "PAYMENT_AUDIT",
  "event": "PAYMENT_STATUS_CHANGED",
  "paymentId": "550e8400-e29b-41d4-a716-446655440000",
  "orderId": "660e8400-e29b-41d4-a716-446655440001",
  "userId": "770e8400-e29b-41d4-a716-446655440002",
  "prevStatus": "REQUESTED",
  "nextStatus": "PAID",
  "source": "ConfirmPaymentService",
  "occurredAt": "2026-07-05T12:00:00.120+09:00"
}
```

### JSON 출력 예시 — PG_CALL

```json
{
  "timestamp": "2026-07-05T12:00:00.500Z",
  "level": "INFO",
  "logger": "PAYMENT_AUDIT",
  "event": "PG_CALL",
  "operation": "CONFIRM",
  "paymentId": "550e8400-e29b-41d4-a716-446655440000",
  "pgTxId": "tgen_20260705_...",
  "success": false,
  "failureCode": "EXCEED_MAX_DAILY_PAYMENT_COUNT",
  "durationMs": 312,
  "requestPayload": "{...}",
  "responsePayload": "{...}"
}
```

---

## 신규 파일 2개

### 1. `infrastructure/audit/AuditLogger.java`

```
infrastructure
└── audit
    └── AuditLogger.java
```

- Logger name `"PAYMENT_AUDIT"` 고정 — 일반 애플리케이션 로그와 분리
- `logStatusChanged(Payment payment, PaymentStatus prevStatus, String source)` — PAYMENT_STATUS_CHANGED 로그
- `logRefundStatusChanged(Refund refund, RefundStatus prevStatus, String source)` — Refund 전환 로그 (같은 이벤트 유형, source로 구분)
- `logPgCall(String operation, UUID paymentId, String pgTxId, boolean success, String failureCode, long durationMs, String requestPayload, String responsePayload)` — PG_CALL 로그
- `@Component` 빈으로 등록

### 2. `src/main/resources/logback-spring.xml`

```xml
<configuration>
  <!-- 일반 콘솔 (기존 동작 유지) -->
  <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
    <encoder>
      <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
    </encoder>
  </appender>

  <!-- 감사 로그 전용 JSON appender -->
  <appender name="AUDIT_JSON" class="ch.qos.logback.core.ConsoleAppender">
    <encoder class="net.logstash.logback.encoder.LogstashEncoder">
      <includeCallerData>false</includeCallerData>
    </encoder>
  </appender>

  <!-- PAYMENT_AUDIT → JSON only (additivity=false: 루트 콘솔 중복 출력 차단) -->
  <logger name="PAYMENT_AUDIT" level="INFO" additivity="false">
    <appender-ref ref="AUDIT_JSON"/>
  </logger>

  <root level="INFO">
    <appender-ref ref="CONSOLE"/>
  </root>
</configuration>
```

---

## 변경 파일 5개

### 3. `build.gradle` — 의존성 추가

```groovy
// 변경 후 (dependencies 블록)
implementation 'net.logstash.logback:logstash-logback-encoder:8.0'
```

Spring Boot BOM 미포함 — 버전 명시 필수.

---

### 4. `ConfirmPaymentService.java` — 3개 지점

생성자에 `AuditLogger` 추가. (작업 2에서 재작성된 confirm 코드 기준 — 지점 위치는 동일)

| 위치 | 이벤트 | prevStatus → nextStatus |
|---|---|---|
| `payment.markRequested()` 직후 | `PAYMENT_STATUS_CHANGED` | `READY → REQUESTED` |
| `payment.approve()` 직후 | `PAYMENT_STATUS_CHANGED` | `REQUESTED → PAID` |
| `payment.fail()` 직후 | `PAYMENT_STATUS_CHANGED` | `REQUESTED → FAILED` (서킷 OPEN의 `CIRCUIT_OPEN` 실패 포함) |

```java
// markRequested 직후
PaymentStatus prev = PaymentStatus.READY;
payment.markRequested(OffsetDateTime.now());
auditLogger.logStatusChanged(payment, prev, "ConfirmPaymentService");

// approve 직후
prev = PaymentStatus.REQUESTED;
payment.approve(...);
auditLogger.logStatusChanged(payment, prev, "ConfirmPaymentService");

// fail 직후
prev = PaymentStatus.REQUESTED;
payment.fail(...);
auditLogger.logStatusChanged(payment, prev, "ConfirmPaymentService");
```

---

### 5. ~~`RefundPaymentService.java`~~ — 지점 삭제 (신 환불 모델)

> 원안의 `startRefunding()` 직후 계측(`PAID → REFUNDING`)은 partial-refund(작업 3)에서
> `startRefunding()` 경로의 신규 유입이 소멸하므로 **삭제**. 재작성된 `RefundPaymentService`는
> Refund 생성(REQUESTED)만 수행하며 상태 *전환*이 아니므로 계측 대상 아님.

---

### 6. 환불 실행 흐름 (order-events 환불 컨슈머) — 3개 지점

생성자에 `AuditLogger` 추가. (작업 3 이후의 부분 환불 실행 코드 기준)

| 위치 | 이벤트 | 전환 |
|---|---|---|
| `refund.complete()` 직후 | `REFUND_STATUS_CHANGED` | `REQUESTED → COMPLETED` |
| `payment.completePartialRefund()`로 REFUNDED 전이 시 | `PAYMENT_STATUS_CHANGED` | `PAID → REFUNDED` (누적 환불액 == 승인액인 마지막 건에서만 발생) |
| `refund.fail()` 직후 | `REFUND_STATUS_CHANGED` | `REQUESTED → FAILED` |

> 신모델에서 Payment는 부분 환불 진행 중 PAID를 유지하므로 `REFUNDING` 관련 전이(`restoreToRefundFailed` 포함) 계측은 없다.
> 서킷 OPEN(작업 4)으로 트랜잭션이 롤백되는 경우 상태 전환이 없으므로 계측 대상 아님(PG_CALL 로그는 남지 않음 — Toss 호출 자체가 차단됨).

---

### 7. `PaymentRefundRetryScheduler.java` — 3개 지점 (신모델 기준)

생성자에 `AuditLogger` 추가. 조회 기준은 `Refund.REQUESTED + stale`(작업 3에서 교체됨).

| 위치 | 이벤트 | 전환 / source |
|---|---|---|
| `refund.complete()` 직후 | `REFUND_STATUS_CHANGED` | `REQUESTED → COMPLETED` / `"PaymentRefundRetryScheduler"` |
| `payment.completePartialRefund()`로 REFUNDED 전이 시 | `PAYMENT_STATUS_CHANGED` | `PAID → REFUNDED` / `"PaymentRefundRetryScheduler"` |
| `refund.fail()` 직후 | `REFUND_STATUS_CHANGED` | `REQUESTED → FAILED` / `"PaymentRefundRetryScheduler"` |

---

### 8. `TossPaymentGateway.java` — 2개 지점

`doConfirm()` / `doRefund()` 안에서 호출 전 타이머 시작, 완료(성공/실패) 후 `logPgCall()`.

> `circuit-breaker.md` 계획에서 `doConfirm()`/`doRefund()`로 메서드가 분리될 예정이므로, 해당 private 메서드 내부에 계측을 위치시키면 서킷브레이커 도입 후에도 계측 위치가 유지됩니다.

```java
// doConfirm() 내부 패턴
long start = System.currentTimeMillis();
try {
    // Toss HTTP 호출
    auditLogger.logPgCall("CONFIRM", paymentId, pgTxId, true, null,
        System.currentTimeMillis() - start, requestJson, responseJson);
    return result;
} catch (PaymentGatewayException e) {
    auditLogger.logPgCall("CONFIRM", paymentId, pgTxId, false, e.getFailureCode(),
        System.currentTimeMillis() - start, requestJson, e.getResponsePayload());
    throw e;
}
```

---

## 계측 지점 전체 요약 (11개)

| 클래스 | 지점 수 | 이벤트 유형 |
|---|---|---|
| `ConfirmPaymentService` | 3 | `PAYMENT_STATUS_CHANGED` |
| 환불 실행 흐름 (order-events 환불 컨슈머) | 3 | `PAYMENT_STATUS_CHANGED` × 1, `REFUND_STATUS_CHANGED` × 2 |
| `PaymentRefundRetryScheduler` | 3 | `PAYMENT_STATUS_CHANGED` × 1, `REFUND_STATUS_CHANGED` × 2 |
| `TossPaymentGateway` | 2 | `PG_CALL` (confirm / refund) |

---

## 테스트

### 단위 테스트 — `AuditLoggerTest`

`ListAppender<ILoggingEvent>`를 `PAYMENT_AUDIT` logger에 붙여 로그 이벤트를 메모리에서 검증.

| 케이스 | 검증 항목 |
|---|---|
| `PAYMENT_STATUS_CHANGED` | `paymentId`, `prevStatus`, `nextStatus`, `source` 포함 |
| `PG_CALL` 실패 | `success=false`, `failureCode` 포함 |
| `PG_CALL` 성공 | `success=true`, `failureCode` null |

### 통합 테스트 — 기존 클래스에 케이스 추가

| 클래스 | 추가 케이스 |
|---|---|
| `ConfirmPaymentIntegrationTest` | 결제 승인 성공 → `REQUESTED`, `PAID` 전환 로그 2건 기록 |
| `ConfirmPaymentIntegrationTest` | PG 실패 → `PG_CALL(success=false)` + `FAILED` 전환 로그 기록 |
| 환불 통합 테스트 (작업 3 기준) | 부분 환불 성공 → `REQUESTED→COMPLETED` + `PG_CALL(success=true)` 기록 (Payment 전이 로그 없음 — PAID 유지) |
| 환불 통합 테스트 (작업 3 기준) | 마지막 상품 환불 → `PAID→REFUNDED` 전환 로그 기록 |
| 환불 통합 테스트 (작업 3 기준) | PG 환불 실패 → `REQUESTED→FAILED` + `PG_CALL(success=false)` 기록 |

---

## 기존 계획과의 교차점

| 계획 | 영향 |
|---|---|
| `circuit-breaker.md` (작업 4, 선행) | `doConfirm()`/`doRefund()`가 이미 추출된 상태에서 계측 — PG_CALL 계측을 해당 private 메서드 내부에 배치 |
| 실패 이벤트 발행 (작업 2·3에 흡수, D5) | 감사 로그와 독립. 실패 이벤트(Kafka)와 감사 로그(Slf4j)는 별개 경로 |
| `partial-refund-api.md` (작업 3, 선행) | 본 문서의 §5~7이 이미 신모델 기준으로 재산정됨 — 착수 시 실제 코드와 지점 대조만 수행 |
| `order-payment-flow-redesign.md` (작업 2, 선행) | 재작성된 confirm 코드에 계측 — 지점 3개 동일 (payment.failed 발행 경로 포함) |

---

## 작업 순서

1. `build.gradle` 의존성 추가 (`logstash-logback-encoder:8.0`)
2. `logback-spring.xml` 신규 작성
3. `AuditLogger.java` 신규 작성 + 단위 테스트
4. `ConfirmPaymentService` 계측 + 통합 테스트 케이스 추가
5. 환불 실행 흐름(order-events 환불 컨슈머) 계측 + 통합 테스트 케이스 추가
6. `PaymentRefundRetryScheduler` 계측
7. `TossPaymentGateway` 계측 (`doConfirm()`/`doRefund()` 내부)
8. 전체 테스트 실행 확인
