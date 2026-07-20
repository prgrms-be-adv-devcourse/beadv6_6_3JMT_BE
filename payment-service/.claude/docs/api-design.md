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

**금액의 진실 공급원은 주문 정보(gRPC)**입니다. 요청 body의 `amount`는 방어적 검증용으로만 쓰이고, 실제 Toss에 전달·저장되는 금액은 매 요청 order-service gRPC(9083)로 직접 조회한 값입니다.

**처리 흐름**
1. paymentKey 중복 확인 → 존재 시 `409(PAY002)`
2. 주문·상태 중복 확인(`PAID`/`FAILED`/`PARTIAL_REFUNDED`/`ALL_REFUNDED`/`UNKNOWN` 존재) → `409(PAY002)`
3. 주문 정보 gRPC 조회(order 9083, 매 요청 직접 호출) — 조회 불가/타임아웃 → `503(PAY009)`, 주문 없음 → `404(PAY008)`
4. 본인 검증: 주문 정보 `buyerId != X-User-Id` → `403(PAY010)`
5. 금액 검증: 요청 `amount != 주문 totalAmount` → `400(PAY012)`. Toss를 호출한 적 없는 순수 입력 검증 실패라 Payment 레코드를 생성하지 않고, `PAYMENT_FAILED`도 발행하지 않는다 — 같은 orderId로 올바른 금액으로 즉시 재시도 가능
6. Payment 레코드 생성(`READY` → `REQUESTED`), `pg_tx_id`(=paymentKey) 멱등키 겸용
7. 토스페이먼츠 confirm API 동기 호출(주문 정보 금액) → `PAID` / `FAILED`
8. Payment 상태 저장 → `200` 반환

> **재결제 차단**: 실제로 Toss confirm까지 시도했다가 `FAILED`로 끝난 주문은 같은 orderId로 다시 결제할 수 없습니다(새 주문으로만 재시도 가능). 중복 판정이 `PAID`/`FAILED`/`PARTIAL_REFUNDED`/`ALL_REFUNDED`/`UNKNOWN` 상태 존재 여부이기 때문입니다. 금액 불일치(`PAY012`)는 Payment 자체가 생성되지 않으므로 이 차단 대상이 아닙니다.

**이후 비동기 흐름**
- 승인 시 → `PAYMENT_APPROVED` 발행 (Order PAID 전환 + `is_download = true`)
- 실패 시 → `PAYMENT_FAILED` 발행 (Order `PENDING → FAILED`, 재결제로 복귀하지 않는 영구 상태)

#### Request Body

```json
{
  "paymentKey": "tossPayments_key_abc123",
  "orderId":    "660e8400-e29b-41d4-a716-446655440001",
  "amount":     50000
}
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `paymentKey` | String | ✅ | 토스페이먼츠 SDK에서 전달받은 paymentKey |
| `orderId` | UUID | ✅ | 결제할 주문 ID |
| `amount` | Int | ✅ | 결제 요청 금액 — 주문 실제 금액(gRPC 조회)과 다르면 `400(PAY012)` |

#### Responses

| 상태 코드 | 설명 | 에러 코드 |
|---|---|---|
| `200` | 결제 승인 완료 | — |
| `400` | 입력값 오류 | `V001` |
| `400` | 요청 금액과 주문 금액 불일치 | `PAY012` |
| `403` | 본인 주문 아님 | `PAY010` |
| `404` | 주문 정보 없음 | `PAY008` |
| `409` | 이미 결제 진행·완료·실패된 주문(재결제 차단) | `PAY002` |
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
2. `refundRequestId`로 이미 처리된 요청인지 조회 — 이미 처리됐으면 정상 종료(중복 이벤트, dedup)
3. 신규 요청이면 `Refund` 생성(`refundRequestId` 저장) 후 `orderId`로 `PAID`/`PARTIAL_REFUNDED` 상태 Payment 조회(락)
4. 누적 환불액이 `total_amount`를 넘으면 `Refund.FAILED` 기록 + `PAYMENT_REFUND_FAILED` 발행(예외 아님, 정상 흐름 — DLT로 가지 않는다)
5. PG 환불 동기 호출(단일 트랜잭션 안에서 수행 — 중간 상태 커밋 없음)
6. 성공: 누적액이 `total_amount`에 도달했으면 `ALL_REFUNDED`, 아니면 `PARTIAL_REFUNDED`로 전이 + `payment-events`에 `PAYMENT_REFUNDED` 발행
7. 실패(PG 오류): `Refund.FAILED`만 기록, Payment 상태는 그대로 + `payment-events`에 `PAYMENT_REFUND_FAILED` 발행. 재시도 장치 없음(필요 시 order-service가 새 `refundRequestId`로 이벤트 재발행)

**동일 상품 재환불**: dedup 키가 `refundRequestId`이므로 같은 OrderProduct에 대해 여러 차례(예: 부분 하자 추가 발견) 환불을 요청할 수 있다. 같은 `refundRequestId`가 재전송되는 경우(Kafka redelivery)만 중복 처리를 막는다.

**금액 검증**: order-service가 보낸 `refundAmount`를 그대로 신뢰한다(payment-service는 상품별 가격 정보를 갖고 있지 않음). 누적 환불액이 결제 총액을 넘지 않는지만 확인한다.

**Kafka 유실 시 폴백**: 없음. `GetRefund` gRPC 폴백 조회는 제거되었다(#398) — order-service가 Kafka 자체 재조회로 대응한다.

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
