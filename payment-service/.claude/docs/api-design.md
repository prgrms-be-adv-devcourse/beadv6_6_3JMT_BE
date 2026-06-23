# API 설계 — Payment Service

**Base URL (내부)**: `http://payment-service:8081`  
**Base URL (외부)**: `https://api.prompthub.io`

---

## 공통 요청 헤더

| 헤더 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `X-Request-Id` | UUID | ✅ | 분산 추적용 요청 ID (Gateway 생성) |
| `X-User-Id` | UUID | 인증 시 ✅ | Gateway가 JWT 검증 후 주입하는 사용자 UUID |
| `X-User-Role` | Enum | 인증 시 ✅ | `BUYER` / `SELLER` / `ADMIN` |

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

**처리 흐름**
1. `idempotency_key` 중복 확인 → 중복이면 `409` 즉시 반환
2. Payment 레코드 생성 (`READY`)
3. 토스페이먼츠 confirm API 동기 호출 → `REQUESTED` → `PAID` / `FAILED`
4. `@Transactional`: Payment 상태 저장 → COMMIT → `200` 반환

> 동일 `orderId`로 재요청 시 `409(PAY002)`를 반환합니다 (중복 결제 방지).

**이후 비동기 흐름**
- 승인 시 → `payment.approved` 발행 (Order PAID 전환 + `is_download = true`)
- 실패 시 → Order는 `PENDING` 유지 (재결제 시도 가능)

#### Request Body

```json
{
  "paymentKey": "tossPayments_key_abc123",
  "orderId":    "660e8400-e29b-41d4-a716-446655440001",
  "amount":     9900
}
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `paymentKey` | String | ✅ | 토스페이먼츠 SDK에서 전달받은 paymentKey |
| `orderId` | UUID | ✅ | 결제할 주문 ID |
| `amount` | Int | ✅ | 결제 금액 (원화) |

#### Responses

| 상태 코드 | 설명 | 에러 코드 |
|---|---|---|
| `200` | 결제 승인 완료 | — |
| `400` | 입력값 오류 | `V001` |
| `400` | PG사 결제 실패 | `PAY_FAILED` |
| `401` | 토큰 만료 | `A003` |
| `403` | 권한 없음 | `A004` |
| `409` | 이미 결제된 주문 | `PAY002` |
| `502` | PG사 처리 오류 | `PAY003` |

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
- 요청 주체: `BUYER`, `SELLER`만 가능 (관리자 불가)
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
| `403` | 권한 없음 | `A004` |

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
