# Outbox Event DLQ 1차 도입 기획서

## 1. 개요

현재 `order-service`는 주문 결제 완료, 환불, 취소 등 주요 도메인 상태 변경 이후 후속 서비스에 이벤트를 전달하기 위해 Transactional Outbox Pattern을 사용한다.

주문 상태 변경과 `outbox_event` 저장은 같은 트랜잭션 안에서 처리되며, 이후 `OutboxRelay`가 `PENDING` 상태의 이벤트를 조회하여 Kafka Topic으로 발행한다.

하지만 Kafka 장애, 네트워크 장애, 브로커 타임아웃 등으로 이벤트 발행이 반복 실패하면 `outbox_event`는 `FAILED` 상태가 되고, 현재 구조에서는 해당 이벤트를 운영자가 쉽게 인지하거나 안전하게 재처리할 수 있는 체계가 부족하다.

본 기획서는 기존 `outbox_event` 테이블의 `FAILED` 상태를 1차 DLQ로 간주하고, 실패 이벤트 조회, 수동 재발행, 알림, 테스트 체계를 도입하는 것을 목표로 한다.

---

## 2. 목표

### 2.1 핵심 목표

1. Kafka 발행 최종 실패 이벤트를 `FAILED` 상태로 격리한다.
2. 운영자가 실패 이벤트를 조회할 수 있도록 관리자 API를 제공한다.
3. 운영자가 판단 후 실패 이벤트를 수동 재발행할 수 있도록 한다.
4. 실패 원인과 발생 시각을 기록하여 장애 추적성을 높인다.
5. `FAILED` 이벤트 발생 시 로그와 모니터링 지표를 통해 빠르게 인지할 수 있도록 한다.

### 2.2 1차 범위

1차 도입 범위는 `order-service`의 Outbox 발행 실패 관리에 한정한다.

대상 이벤트는 `order-service`가 발행하는 주문 도메인 이벤트다.

예시:

```text
ORDER_PAID
ORDER_REFUND
ORDER_CANCELED
ORDER_FAILED
```

Kafka Topic은 기존 코드 규칙에 맞춰 아래 형식을 사용한다.

```text
order.events
```

---

## 3. 비범위

1차 도입에서는 아래 항목을 제외한다.

1. 별도 `outbox_event_dlq` 테이블 생성
2. 자동 DLQ 재처리 스케줄러
3. payload 수정 후 재발행
4. Kafka DLT Topic으로 Producer 실패 이벤트 전송
5. 전체 서비스 공통 Outbox DLQ 프레임워크화
6. 운영 대시보드 화면 개발
7. 장애 티켓 자동 생성

위 항목은 2차 고도화 범위에서 다룬다.

---

## 4. 현재 구조

### 4.1 이벤트 저장

도메인 상태 변경이 발생하면 `OutboxEventAppender`가 `outbox_event` 테이블에 이벤트를 저장한다.

예시 흐름:

```text
PAYMENT_APPROVED 수신
→ processed_event 기준 중복 여부 확인
→ Order 상태 PAID 변경
→ OrderProduct 상태 PAID 변경
→ ORDER_PAID OutboxEvent 저장
→ processed_event 저장
→ 트랜잭션 커밋
```

이때 `outbox_event`의 초기 상태는 `PENDING`이다.

### 4.2 이벤트 발행

`OutboxRelay`는 `@Scheduled` 방식으로 주기적으로 실행된다.

기본 흐름은 아래와 같다.

```text
PENDING 이벤트 조회
→ KafkaTemplate으로 order.events 발행
→ 발행 성공 시 PUBLISHED 처리
→ 발행 실패 시 retry_count 증가
→ 최대 재시도 초과 시 FAILED 처리
```

### 4.3 현재 문제

`FAILED` 상태가 된 이벤트는 더 이상 `OutboxRelay`의 일반 발행 대상이 되지 않는다.

이로 인해 아래 문제가 발생한다.

1. 주문 상태는 정상 변경되었으나 후속 서비스가 이벤트를 받지 못한다.
2. 정산 서비스가 `ORDER_PAID` 또는 `ORDER_REFUND` 이벤트를 받지 못해 정산 대상 데이터가 누락될 수 있다.
3. 상품 서비스가 판매 수 증가 같은 후속 처리를 수행하지 못할 수 있다.
4. 운영자는 DB를 직접 조회하거나 상태를 강제로 수정해야 한다.
5. 실패 원인이 DB에 남지 않아 장애 원인 분석이 어렵다.

---

## 5. 설계 방향

1차에서는 기존 `outbox_event` 테이블을 유지한다.

별도 DLQ 테이블을 만들지 않고, `status = FAILED`인 레코드를 논리적 DLQ로 간주한다.

```text
PENDING    : 발행 대기
PUBLISHED  : 발행 성공
FAILED     : 최대 재시도 초과로 운영자 확인 필요
```

단, 운영 추적을 위해 `outbox_event` 테이블에 실패 관련 메타데이터 컬럼을 추가한다.

---

## 6. 상태 전이

1차 상태 전이는 아래와 같다.

```text
PENDING → PUBLISHED
PENDING → FAILED
FAILED  → PENDING
```

### 6.1 PENDING → PUBLISHED

Kafka 발행에 성공한 경우다.

처리 내용:

```text
status = PUBLISHED
published_at = now()
updated_at = now()
```

### 6.2 PENDING → FAILED

Kafka 발행 실패가 최대 재시도 횟수를 초과한 경우다.

처리 내용:

```text
status = FAILED
retry_count 유지
failure_reason 기록
last_failed_at 기록
updated_at = now()
```

### 6.3 FAILED → PENDING

관리자가 수동 재발행을 요청한 경우다.

처리 내용:

```text
status = PENDING
retry_count = 0
next_retry_at = now()
manual_retry_count 증가
updated_at = now()
```

단, 기존 실패 원인인 `failure_reason`, `last_failed_at`은 삭제하지 않는다.

---

## 7. DB 변경 사항

### 7.1 기존 테이블 유지

기존 `outbox_event` 테이블을 유지한다.

### 7.2 추가 컬럼

1차에서 필요한 최소 컬럼은 아래와 같다.

```sql
ALTER TABLE outbox_event
    ADD COLUMN IF NOT EXISTS failure_reason text,
    ADD COLUMN IF NOT EXISTS last_failed_at timestamp(6),
    ADD COLUMN IF NOT EXISTS next_retry_at timestamp(6),
    ADD COLUMN IF NOT EXISTS manual_retry_count integer NOT NULL DEFAULT 0;
```

### 7.3 인덱스 추가

실패 이벤트 조회와 재시도 대상 조회를 위해 인덱스를 추가한다.

```sql
CREATE INDEX IF NOT EXISTS idx_outbox_event_failed_created_at
    ON outbox_event (status, created_at)
    WHERE status = 'FAILED';

CREATE INDEX IF NOT EXISTS idx_outbox_event_pending_retry
    ON outbox_event (status, next_retry_at, retry_count)
    WHERE status = 'PENDING';
```

### 7.4 상태값

1차에서는 상태값을 추가하지 않는다.

기존 상태를 그대로 사용한다.

```text
PENDING
PUBLISHED
FAILED
```

---

## 8. 재시도 정책

### 8.1 일반 발행 재시도

`OutboxRelay`는 `PENDING` 상태 이벤트를 발행 대상으로 조회한다.

재시도 횟수는 기존 `retry_count`를 사용한다.

기본 정책:

```text
maxRetryCount = 3
scheduler interval = 5초
```

발행 실패 시:

```text
retry_count 증가
failure_reason 기록
last_failed_at 기록
```

최대 재시도 초과 시:

```text
status = FAILED
```

### 8.2 FAILED 이벤트 자동 재시도

1차에서는 `FAILED` 이벤트를 자동으로 재시도하지 않는다.

이유:

1. `FAILED`는 이미 최대 재시도를 초과한 이벤트다.
2. 직렬화 오류, 잘못된 Topic, payload 오류처럼 자동 재시도해도 해결되지 않는 문제가 있을 수 있다.
3. 무분별한 자동 재시도는 Kafka와 로그 시스템에 추가 부하를 줄 수 있다.
4. 장애 상황에서 알림 폭탄이 발생할 수 있다.

따라서 1차에서는 운영자 판단 후 수동 재발행만 허용한다.

---

## 9. 실패 유형 분류

### 9.1 재시도 가능 실패

아래 실패는 일반 재시도 대상이다.

```text
Kafka broker 일시 장애
네트워크 타임아웃
connection refused
metadata fetch timeout
일시적인 broker unavailable
```

### 9.2 운영자 확인 필요 실패

아래 실패는 반복 재시도 후 `FAILED`로 격리한다.

```text
Kafka Topic 설정 오류
payload 직렬화 실패
필수 필드 누락
메시지 크기 초과
지원하지 않는 eventType
알 수 없는 직렬화 예외
```

### 9.3 실패 원인 기록

실패 시 아래 정보를 기록한다.

```text
eventId
aggregateId
aggregateType
eventType
topic
retryCount
failureReason
lastFailedAt
```

---

## 10. 관리자 API 설계

관리자 API는 `ADMIN` 권한만 접근할 수 있다.

API 응답은 기존 공통 응답 포맷을 따른다.

### 10.1 실패 이벤트 목록 조회

```http
GET /api/v1/admin/outbox-events/failed
```

#### Query Parameters

| 파라미터          | 타입      | 필수 | 기본값 | 설명              |
| ------------- | ------- | -: | --- | --------------- |
| page          | Integer |  N | 1   | 페이지 번호          |
| size          | Integer |  N | 20  | 페이지 크기          |
| eventType     | String  |  N | -   | 이벤트 타입 필터       |
| aggregateType | String  |  N | -   | Aggregate 타입 필터 |
| from          | Date    |  N | -   | 조회 시작일          |
| to            | Date    |  N | -   | 조회 종료일          |

#### Response

```json
{
  "success": true,
  "data": [
    {
      "eventId": "uuid",
      "aggregateId": "uuid",
      "aggregateType": "ORDER",
      "eventType": "ORDER_PAID",
      "topic": "order.events",
      "status": "FAILED",
      "retryCount": 3,
      "manualRetryCount": 0,
      "failureReason": "Kafka broker unavailable",
      "occurredAt": "2026-07-07T10:00:00",
      "lastFailedAt": "2026-07-07T10:01:00",
      "createdAt": "2026-07-07T10:00:00"
    }
  ],
  "message": "success",
  "meta": {
    "page": 1,
    "size": 20,
    "total": 1,
    "hasNext": false
  }
}
```

---

### 10.2 실패 이벤트 상세 조회

```http
GET /api/v1/admin/outbox-events/{eventId}
```

#### Response

```json
{
  "success": true,
  "data": {
    "eventId": "uuid",
    "aggregateId": "uuid",
    "aggregateType": "ORDER",
    "eventType": "ORDER_PAID",
    "topic": "order.events",
    "payload": "{...}",
    "status": "FAILED",
    "retryCount": 3,
    "manualRetryCount": 0,
    "failureReason": "Kafka broker unavailable",
    "occurredAt": "2026-07-07T10:00:00",
    "publishedAt": null,
    "lastFailedAt": "2026-07-07T10:01:00",
    "createdAt": "2026-07-07T10:00:00",
    "updatedAt": "2026-07-07T10:01:00"
  },
  "message": "success"
}
```

---

### 10.3 실패 이벤트 수동 재발행 요청

```http
POST /api/v1/admin/outbox-events/{eventId}/retry
```

#### 처리 방식

```text
1. eventId 기준 outbox_event 조회
2. status가 FAILED인지 검증
3. status를 PENDING으로 변경
4. retry_count를 0으로 초기화
5. manual_retry_count 증가
6. next_retry_at을 now()로 설정
7. OutboxRelay가 다음 주기에 재발행
```

#### Response

```json
{
  "success": true,
  "data": {
    "eventId": "uuid",
    "status": "PENDING",
    "manualRetryCount": 1
  },
  "message": "success"
}
```

---

## 11. 재발행 정책

### 11.1 eventId 유지

수동 재발행 시 기존 `eventId`를 유지한다.

이유:

```text
Consumer는 eventId 기준으로 멱등 처리를 수행해야 한다.
재발행 이벤트가 새로운 eventId를 가지면 Consumer 입장에서는 새로운 이벤트로 인식될 수 있다.
```

### 11.2 payload 수정 금지

1차에서는 payload 수정 후 재발행을 허용하지 않는다.

이유:

```text
운영자가 payload를 직접 수정하면 도메인 정합성이 깨질 수 있다.
감사 추적이 어려워진다.
잘못된 payload 수정으로 더 큰 장애가 발생할 수 있다.
```

### 11.3 topic 변경 금지

1차에서는 topic 변경 후 재발행을 허용하지 않는다.

---

## 12. 알림 및 모니터링

### 12.1 로그

`FAILED` 상태 전환 시 반드시 error 로그를 남긴다.

필수 로그 필드:

```text
eventId
aggregateId
aggregateType
eventType
topic
retryCount
failureReason
```

### 12.2 Metric

Micrometer 기반 지표를 추가한다.

```text
outbox_event_publish_success_total
outbox_event_publish_failure_total
outbox_event_failed_total
outbox_event_pending_count
outbox_event_failed_count
outbox_event_oldest_pending_age_seconds
```

### 12.3 알림 조건

1차 알림 기준은 아래와 같다.

```text
FAILED 이벤트가 1건 이상 발생
PENDING 이벤트가 10분 이상 발행되지 못함
동일 eventType에서 5분 내 실패가 5건 이상 발생
```

### 12.4 알림 방식

1차에서는 아래 중 하나를 선택한다.

```text
Grafana Alert
Slack Webhook
```

Slack 직접 연동이 부담되면 1차에서는 Metric만 추가하고 Grafana Alert로 처리한다.

---

## 13. 보안 및 권한

### 13.1 접근 권한

관리자 API는 `ADMIN` 권한만 접근 가능하다.

일반 사용자와 판매자는 Outbox 실패 이벤트를 조회할 수 없다.

### 13.2 민감 정보

payload에는 구매자 ID, 판매자 ID, 결제 ID, 주문 ID 등이 포함될 수 있다.

1차에서는 관리자만 조회 가능하게 제한하고, payload 마스킹은 2차 고도화에서 검토한다.

### 13.3 감사 로그

1차에서는 별도 감사 로그 테이블을 만들지 않는다.

단, 수동 재발행 요청 시 application log에는 아래 정보를 반드시 남긴다.

```text
adminUserId
eventId
previousStatus
newStatus
manualRetryCount
requestedAt
```

---

## 14. 테스트 계획

### 14.1 단위 테스트

#### OutboxRelay 테스트

```text
PENDING 이벤트 발행 성공 시 PUBLISHED로 변경된다.
Kafka 발행 실패 시 retry_count가 증가한다.
retry_count가 maxRetryCount 미만이면 PENDING 상태를 유지한다.
retry_count가 maxRetryCount 이상이면 FAILED 상태가 된다.
실패 시 failure_reason과 last_failed_at이 기록된다.
```

#### Admin Service 테스트

```text
FAILED 이벤트 목록을 조회할 수 있다.
FAILED 이벤트 상세를 조회할 수 있다.
FAILED 이벤트를 PENDING으로 수동 재발행 요청할 수 있다.
PUBLISHED 이벤트는 재발행 요청할 수 없다.
존재하지 않는 eventId는 예외 처리한다.
```

### 14.2 Controller 테스트

```text
ADMIN 권한으로 실패 이벤트 목록 조회 성공
ADMIN 권한으로 실패 이벤트 상세 조회 성공
ADMIN 권한으로 수동 재발행 요청 성공
USER 권한으로 접근 시 403
인증 없이 접근 시 401
공통 응답 포맷 검증
페이지네이션 응답 검증
```

### 14.3 통합 테스트

가능하면 Testcontainers Kafka를 사용한다.

필수 통합 테스트:

```text
Kafka broker 장애 시 outbox_event가 FAILED로 전환된다.
수동 재발행 요청 후 Kafka 복구 시 PUBLISHED로 전환된다.
동일 eventId 재발행 시 eventId가 유지된다.
```

---

## 15. 기대 효과

1. Kafka 발행 최종 실패 이벤트를 운영자가 인지할 수 있다.
2. DB 직접 수정 없이 안전하게 수동 재발행할 수 있다.
3. 주문, 정산, 상품 등 서비스 간 데이터 불일치 복구 가능성이 높아진다.
4. 장애 분석에 필요한 실패 원인을 DB와 로그에서 확인할 수 있다.
5. 향후 자동 재처리, DLQ 테이블 분리, 운영 대시보드로 확장할 수 있는 기반이 생긴다.

---

## 16. 1차 구현 요약

```text
1. outbox_event 실패 메타데이터 컬럼 추가
2. OutboxRelay 실패 기록 로직 보강
3. maxRetryCount 초과 시 FAILED 전환 및 Metric 기록
4. FAILED 이벤트 목록 조회 API 추가
5. FAILED 이벤트 상세 조회 API 추가
6. FAILED 이벤트 수동 재발행 API 추가
7. 관리자 권한 검증
8. 로그 및 Metric 추가
9. 단위 테스트, Controller 테스트, 통합 테스트 작성
```
