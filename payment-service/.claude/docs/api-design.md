# API 설계 — Payment Service

**Base URL (내부)**: `http://payment-service:8084`  
**Base URL (외부)**: `https://api.prompthub.io`

---

## 공통 요청 헤더

| 헤더 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `X-Request-Id` | UUID | ✅ | 분산 추적용 요청 ID (Gateway 생성) |
| `X-User-Id` | UUID | 인증 시 ✅ | Gateway가 JWT 검증 후 주입하는 사용자 UUID |

---

## 공통 응답 형식

**성공**
```json
{ "success": true, "data": { ... }, "message": "success" }
```

**실패**
```json
{ "success": false, "data": null, "message": "에러 메시지", "code": "PAY001" }
```

---

## 엔드포인트

### POST /api/v2/payments/confirm — 결제 승인 요청

프론트엔드가 토스페이먼츠 SDK로 결제창을 호출한 후, 백엔드에 최종 승인을 요청합니다.

**금액의 진실 공급원은 주문 스냅샷**입니다. 요청 body에 `amount`를 받지 않고, `orderId`로 확보한 스냅샷(`order-events` 이벤트 또는 gRPC 폴백)의 금액을 Toss에 전달합니다.

**처리 흐름**
1. 주문 스냅샷 확보: 로컬 조회 → 없으면 gRPC 폴백(order 9083) 후 기록
   - gRPC 조회 불가/타임아웃 → `503(PAY009)`, Payment 미생성
   - 주문 없음(gRPC NOT_FOUND) → `404(PAY008)`
2. 본인 검증: 스냅샷 `buyerId != X-User-Id` → `403(PAY010)`
3. 중복 판정: 진행·완료 상태(`PAID`/`REFUNDING`/`REFUNDED`/`UNKNOWN`) 존재 시 → `409(PAY002)`
4. Payment 레코드 생성(`READY` → `REQUESTED`), `pg_tx_id`(=paymentKey) 멱등키 겸용
5. 토스페이먼츠 confirm API 동기 호출(스냅샷 금액) → `PAID` / `FAILED`
6. Payment 상태 저장 → `200` 반환

> **재결제 허용**: 중복 판정이 "존재 여부"가 아니라 "진행·완료 상태 존재 여부"이므로, 직전 시도가 `FAILED`면 재결제가 가능합니다(시도마다 새 Payment 행). 동일 `paymentKey` 재요청만 `pg_tx_id` UNIQUE로 `409` 차단됩니다.

**이후 비동기 흐름**
- 승인 시 → `payment.approved` 발행 (Order PAID 전환 + `is_download = true`)
- 실패 시 → `payment.failed` 발행 (Order `PENDING → FAILED`, 재결제 시 복귀)

#### Request Body

```json
{
  "paymentKey": "tossPayments_key_abc123",
  "orderId":    "660e8400-e29b-41d4-a716-446655440001"
}
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `paymentKey` | String | ✅ | 토스페이먼츠 SDK에서 전달받은 paymentKey |
| `orderId` | UUID | ✅ | 결제할 주문 ID |

#### Responses

| 상태 코드 | 설명 | 에러 코드 |
|---|---|---|
| `200` | 결제 승인 완료 | — |
| `400` | 입력값 오류 | `V001` |
| `403` | 본인 주문 아님 | `PAY010` |
| `404` | 주문 정보 없음 | `PAY008` |
| `409` | 이미 결제 진행·완료된 주문 | `PAY002` |
| `422` | PG사 결제 실패 | `PAY_FAILED` |
| `502` | PG사 처리 오류 | `PAY003` |
| `503` | 주문 정보 확보 불가 | `PAY009` |

**200 응답 예시**
```json
{
  "success": true,
  "data": { "paymentId": "550e8400-e29b-41d4-a716-446655440000" },
  "message": "success"
}
```

---

### 환불 — 이벤트 기반 (REST 없음)

환불은 REST 엔드포인트가 아니라 order-service가 발행하는 Kafka 이벤트로 트리거된다.
OrderProduct 단위로만 존재하며, 주문 전체를 환불하려면 order-service가 상품 수만큼 이벤트를 여러 번 발행한다.

**이벤트 계약**: `order-events` 토픽의 `ORDER_REFUND_REQUESTED` — 상세 스키마는 `events.md` 참조.

**처리 흐름**
1. `OrderEventConsumer`가 `ORDER_REFUND_REQUESTED` 수신
2. `orderId`로 `PAID`/`PARTIAL_REFUNDED` 상태 Payment 조회(락)
3. 누적 환불액이 `total_amount`를 넘으면 처리 중단(DLT)
4. PG 환불 동기 호출(단일 트랜잭션 안에서 수행 — REFUNDING 같은 진행중 마커 없음)
5. 성공: 누적액이 `total_amount`에 도달했으면 `ALL_REFUNDED`, 아니면 `PARTIAL_REFUNDED`로 전이 + `payment-events`에 `PAYMENT_REFUNDED` 발행
6. 실패: `Refund.FAILED`만 기록, Payment 상태는 그대로 + `payment-events`에 `PAYMENT_REFUND_FAILED` 발행. 재시도 장치 없음(필요 시 order-service가 이벤트 재발행)

**금액 검증**: order-service가 보낸 `refundAmount`를 그대로 신뢰한다(payment-service는 상품별 가격 정보를 갖고 있지 않음). 누적 환불액이 결제 총액을 넘지 않는지만 확인한다.

---

## PaymentStatus

| 상태 | 설명 |
|---|---|
| `READY` | Payment 레코드 생성, PG 요청 전 |
| `REQUESTED` | PG사에 결제 요청 전송 완료 |
| `PAID` | PG사 결제 승인 완료 |
| `FAILED` | PG사 결제 실패 |
| `PARTIAL_REFUNDED` | 일부 OrderProduct 환불 완료, 잔여 환불 가능액 존재 |
| `ALL_REFUNDED` | 누적 환불액이 결제 총액에 도달 |
| `UNKNOWN` | PG 응답 불명확, 수동 확인 필요 |
