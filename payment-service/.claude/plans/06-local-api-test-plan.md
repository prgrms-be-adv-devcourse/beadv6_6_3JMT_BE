# 결제·환불 API 로컬 수동 테스트 계획

## 목적

payment-service의 결제 승인·환불 엔드포인트를 로컬 환경에서 직접 호출하여 기능을 검증한다.

## 범위

| 검증 대상 | 포함 여부 |
|---|---|
| 결제 승인 정상 흐름 (`POST /confirm` → 200) | O |
| 결제 승인 예외 흐름 (중복·권한·입력 오류) | O |
| 환불 요청 정상 흐름 (`POST /refund` → 202) | O |
| 환불 요청 예외 흐름 (상태·권한·존재 오류) | O |
| DB Payment 상태 전이 확인 | O |
| API Gateway 인증 레이어 | X (payment-service 직접 호출로 우회) |
| Kafka downstream (order-service Order 상태 전이) | X (payment-service 범위만) |

## 전제 조건

### 기동 순서

```
1. docker-compose up -d     (PostgreSQL + Kafka)
2. ./gradlew bootRun        (payment-service, port 8084)
3. 프론트엔드 실행
```

### 프론트엔드 임시 설정 변경

프론트엔드가 현재 `http://localhost:8080`(Gateway)로 confirm을 호출하므로, 테스트 동안 아래 두 가지를 임시 수정한다.

1. confirm API URL → `http://localhost:8084/api/v1/payments/confirm`
2. 요청 헤더에 아래 테스트값 추가:

```
X-User-Id:   <테스트용 UUID, 예: 770e8400-e29b-41d4-a716-446655440002>
X-User-Role: BUYER
X-Request-Id: <임의 UUID>
```

> 테스트 완료 후 원복 필수.

---

## 환경 준비

### 1. `.env` 파일 작성 (payment-service 루트)

```properties
DB_URL=jdbc:postgresql://localhost:5433/prompthub_payment_dev
DB_USERNAME=anjinpyo
DB_PASSWORD=1234
TOSS_SECRET_KEY=<Toss 테스트 시크릿 키 (test_sk_...)>
```

> `TOSS_SECRET_KEY`를 세팅하지 않으면 기본값 `test-dummy-key`가 적용되어 Toss API가 401을 반환한다.

### 2. docker-compose.yml에 Kafka 추가

`application.yaml`의 `bootstrap-servers: localhost:9092`에 맞게 KRaft 단일 브로커를 추가한다.

```yaml
  payment-kafka:
    image: apache/kafka:3.7.0
    container_name: payment-kafka-dev
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@localhost:9093
      KAFKA_LISTENERS: PLAINTEXT://:9092,CONTROLLER://:9093
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT
      KAFKA_INTER_BROKER_LISTENER_NAME: PLAINTEXT
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1
      KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS: 0
    ports:
      - "9092:9092"
```

> Kafka 없이도 서비스는 기동되지만, 결제 승인 후 이벤트 발행 실패 에러 로그가 지속 출력된다.

### 3. 기동 확인

```bash
# PostgreSQL 연결 확인
docker exec payment-db-dev psql -U anjinpyo -d prompthub_payment_dev -c "\dt"

# Kafka 브로커 확인
docker exec payment-kafka-dev kafka-topics.sh --bootstrap-server localhost:9092 --list

# payment-service Swagger 접근
open http://localhost:8084/swagger-ui.html
```

---

## 테스트 케이스

### 결제 승인 — `POST /api/v1/payments/confirm`

#### TC-PAY-01 정상 결제 승인

**방법**: 프론트엔드에서 Toss 결제창 → 테스트 카드 결제 완료 → confirm 자동 호출

**확인 사항**:
- HTTP 200
- 응답 본문에 `paymentId` 포함
- DB `payment` 테이블 → 상태 `PAID`

```sql
SELECT id, order_id, status, amount FROM payment ORDER BY created_at DESC LIMIT 1;
```

> **이 paymentId를 기록해둔다. 환불 테스트에서 사용한다.**

---

#### TC-PAY-02 중복 결제 방지 (409)

동일 `orderId`로 confirm을 재요청한다.

```bash
curl -s -X POST http://localhost:8084/api/v1/payments/confirm \
  -H "Content-Type: application/json" \
  -H "X-User-Id: 770e8400-e29b-41d4-a716-446655440002" \
  -H "X-User-Role: BUYER" \
  -H "X-Request-Id: $(uuidgen)" \
  -d '{
    "paymentKey": "<TC-PAY-01에서 사용한 paymentKey>",
    "orderId":    "<TC-PAY-01에서 사용한 orderId>",
    "amount":     9900
  }'
```

**기대**: HTTP 409, `"code": "PAY002"`

---

#### TC-PAY-03 BUYER 역할 없음 (403)

`X-User-Role`에서 `BUYER`를 제거한다.

```bash
curl -s -X POST http://localhost:8084/api/v1/payments/confirm \
  -H "Content-Type: application/json" \
  -H "X-User-Id: 770e8400-e29b-41d4-a716-446655440002" \
  -H "X-User-Role: SELLER" \
  -H "X-Request-Id: $(uuidgen)" \
  -d '{
    "paymentKey": "dummy-key",
    "orderId":    "'$(uuidgen)'",
    "amount":     9900
  }'
```

**기대**: HTTP 403, `"code": "PAY007"`

---

#### TC-PAY-04 입력값 오류 (400)

`amount`를 0으로 전송한다.

```bash
curl -s -X POST http://localhost:8084/api/v1/payments/confirm \
  -H "Content-Type: application/json" \
  -H "X-User-Id: 770e8400-e29b-41d4-a716-446655440002" \
  -H "X-User-Role: BUYER" \
  -H "X-Request-Id: $(uuidgen)" \
  -d '{
    "paymentKey": "dummy-key",
    "orderId":    "'$(uuidgen)'",
    "amount":     0
  }'
```

**기대**: HTTP 400, `"code": "V001"`

---

### 환불 — `POST /api/v1/payments/{paymentId}/refund`

> TC-PAY-01에서 기록한 `paymentId`와 `X-User-Id`를 동일하게 사용한다.

#### TC-REF-01 정상 환불 요청

```bash
PAY_ID=<TC-PAY-01의 paymentId>
USER_ID=770e8400-e29b-41d4-a716-446655440002

curl -s -X POST "http://localhost:8084/api/v1/payments/${PAY_ID}/refund" \
  -H "X-User-Id: ${USER_ID}" \
  -H "X-User-Role: BUYER" \
  -H "X-Request-Id: $(uuidgen)"
```

**확인 사항**:
- HTTP 202
- DB 상태 `REFUNDING` (환불 처리 중)
- 잠시 후 DB 상태 `REFUNDED` 전환 확인

```sql
SELECT id, status, updated_at FROM payment WHERE id = '<paymentId>';
```

---

#### TC-REF-02 환불 불가 상태 (400)

이미 환불된 paymentId(상태 `REFUNDED`)로 재요청한다.

```bash
curl -s -X POST "http://localhost:8084/api/v1/payments/${PAY_ID}/refund" \
  -H "X-User-Id: ${USER_ID}" \
  -H "X-User-Role: BUYER" \
  -H "X-Request-Id: $(uuidgen)"
```

**기대**: HTTP 400, `"code": "PAY004"`

---

#### TC-REF-03 타인 결제 건 환불 시도 (403)

`X-User-Id`를 결제자가 아닌 다른 UUID로 변경한다.

```bash
curl -s -X POST "http://localhost:8084/api/v1/payments/${PAY_ID}/refund" \
  -H "X-User-Id: aaaaaaaa-0000-0000-0000-000000000000" \
  -H "X-User-Role: BUYER" \
  -H "X-Request-Id: $(uuidgen)"
```

**기대**: HTTP 403, `"code": "PAY006"`

---

#### TC-REF-04 존재하지 않는 paymentId (404)

```bash
curl -s -X POST "http://localhost:8084/api/v1/payments/00000000-0000-0000-0000-000000000000/refund" \
  -H "X-User-Id: ${USER_ID}" \
  -H "X-User-Role: BUYER" \
  -H "X-Request-Id: $(uuidgen)"
```

**기대**: HTTP 404, `"code": "PAY005"`

---

#### TC-REF-05 BUYER 역할 없음 (403)

```bash
curl -s -X POST "http://localhost:8084/api/v1/payments/${PAY_ID}/refund" \
  -H "X-User-Id: ${USER_ID}" \
  -H "X-User-Role: SELLER" \
  -H "X-Request-Id: $(uuidgen)"
```

**기대**: HTTP 403, `"code": "PAY007"`

---

## 테스트 순서 요약

```
TC-PAY-01 (정상 결제) → paymentId 기록
  → TC-PAY-02 (중복 결제)
  → TC-PAY-03 (권한 오류)
  → TC-PAY-04 (입력값 오류)
  → TC-REF-01 (정상 환불) → REFUNDED 대기
      → TC-REF-02 (재환불 시도)
      → TC-REF-03 (타인 환불)
      → TC-REF-04 (없는 ID)
      → TC-REF-05 (권한 오류)
```

---

## 실패 시 체크리스트

| 증상 | 원인 후보 | 조치 |
|---|---|---|
| 서비스 기동 실패 | `.env` 없거나 DB 미기동 | `docker ps`로 PostgreSQL 확인, `.env` 내용 검토 |
| confirm → Toss 401 | `TOSS_SECRET_KEY` 기본값(`test-dummy-key`) 사용 중 | `.env`에 실제 테스트 시크릿 키 세팅 후 재기동 |
| confirm → 403 PAY007 | `X-User-Role: BUYER` 헤더 누락 | 프론트 헤더 설정 확인 |
| confirm → 400 V001 | 금액·필드 검증 실패 | 요청 본문 확인 |
| 결제 성공 후 Kafka 에러 로그 | Kafka 미기동 | docker-compose에 Kafka 추가 후 재기동 |
| 환불 후 상태가 REFUNDED로 안 바뀜 | PG 환불 실패 → `PAID`로 복원 | 로그에서 Toss 환불 응답 코드 확인 |
| DB 스키마 없음 | `ddl-auto: create-drop`으로 미생성 | 서비스 정상 기동 여부 확인 (JPA가 기동 시 생성) |
