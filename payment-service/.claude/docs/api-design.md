# API 설계 — Payment Service

**Base URL (내부)**: `http://payment-service:8084`  
**Base URL (외부)**: `https://api.prompthub.io`

---

## 공통 요청 헤더

| 헤더 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `X-Request-Id` | UUID | ✅ | 분산 추적용 요청 ID (Gateway 생성) |
| `X-User-Id` | UUID | 인증 시 ✅ | Gateway가 JWT 검증 후 주입하는 사용자 UUID |
| `X-User-Role` | String | 인증 시 ✅ | 쉼표 구분 복합 역할 (예: `BUYER,SELLER`) |

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

### POST /api/v1/payments/confirm — 결제 승인 요청

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
| `403` | BUYER 역할 없음 | `PAY007` |
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

### POST /api/v1/payments/{paymentId}/refund — 환불 요청

구매자가 결제 건에 대해 전체 환불을 요청합니다.

**제약 조건**
- 요청 주체: `BUYER` 역할 보유자만 가능 (BUYER 없는 관리 전용 계정 불가)
- 환불 가능 상태: `PAID`만 가능
- 전체 환불만 지원 (부분 환불은 세미 MVP 범위 외)

**처리 흐름**
1. `paymentId`로 Payment 조회 → 본인 결제 확인
2. Payment 상태 `PAID` 검증 → 아니면 `400` 반환
3. Payment 상태 `PAID` → `REFUNDING` 전환 + `202` 반환
4. `@TransactionalEventListener(AFTER_COMMIT)`: PG사에 환불 요청 (`Idempotency-Key: refund-{paymentId}`)
5. PG 환불 완료 → `REFUNDING` → `REFUNDED` 저장 → `payment.refunded` 발행

> PG사 환불 실패 시 → `REFUNDING` → `PAID` 복원 (이벤트 발행 없음)

**장애 복구 (Scheduled Retry)**
- `@Scheduled` (10분 주기): `REFUNDING` 상태가 일정 시간 이상 지속된 건을 조회해 PG 환불 재요청
- 재요청 시 `Idempotency-Key: refund-{paymentId}` 를 동일하게 사용해 이중 환불 방지 (토스페이먼츠 멱등키 유효 기간 15일)

**이후 비동기 흐름**

| 구독 서비스 | 처리 내용 |
|---|---|
| Order 서비스 | Order 상태 `REFUNDED` 전환 + `is_download = false` |

#### Path Parameter

| 파라미터 | 타입 | 설명 |
|---|---|---|
| `paymentId` | UUID | 환불할 Payment ID |

#### Responses

| 상태 코드 | 설명 | 에러 코드 |
|---|---|---|
| `202` | 환불 요청 접수 완료 | — |
| `400` | 환불 불가 상태 | `PAY004` |
| `401` | 토큰 만료 | `A003` |
| `403` | BUYER 역할 없음 | `PAY007` |
| `403` | 본인 결제 건이 아님 | `PAY006` |
| `404` | 결제 건 없음 | `PAY005` |

> **PG 환불 실패는 동기 응답으로 전달되지 않습니다.**
> 202 반환 후 PG 호출이 이루어지므로, PG 실패 시 Payment 상태가 `REFUNDING → PAID`로 복원됩니다.
> 클라이언트는 Payment 상태 조회 API를 폴링하여 `REFUNDING` / `REFUNDED` / `PAID`(실패 복원) 를 구분해야 합니다.

**202 응답 예시**
```json
{ "success": true, "data": null, "message": "success" }
```

---

## PaymentStatus

| 상태 | 설명 |
|---|---|
| `READY` | Payment 레코드 생성, PG 요청 전 |
| `REQUESTED` | PG사에 결제 요청 전송 완료 |
| `PAID` | PG사 결제 승인 완료 |
| `FAILED` | PG사 결제 실패 |
| `REFUNDING` | 환불 요청 접수, PG 환불 진행 중 |
| `REFUNDED` | 환불 완료 |
| `UNKNOWN` | PG 응답 불명확, 수동 확인 필요 |
