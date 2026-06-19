# Payment Service API

**Base:** `http://localhost:xxxx/api/v1`

## 공통 사항

- 인증이 필요한 엔드포인트는 `Authorization: Bearer {accessToken}` 헤더 필요
- 토큰 검증은 API Gateway에서 수행. 각 서비스는 헤더(`X-User-Id`, `X-User-Role`)만 읽음
- 결제는 멱등키(`idempotency_key`) 기반으로 중복 처리 방지
- 에러 응답 코드는 [에러 코드 명세](../error-codes.md) 참조

---

## 공통 요청 헤더 (인증 필요 엔드포인트)

| 헤더 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `X-Request-Id` | UUID | ✅ | 분산 추적용 요청 ID (Gateway 생성) |
| `X-User-Id` | UUID | ✅ | Gateway가 AT 검증 후 주입하는 사용자 UUID |
| `X-User-Role` | Enum | ✅ | 사용자 역할: `BUYER` / `SELLER` |

---

## 결제

### POST /payments/confirm — 결제 요청

- UC: UC-PAYMENT-01
- 인증: 필요
- 필요 역할: BUYER
- 멱등키 기반. 응답 status: REQUESTED

#### Request

**Body**

```json
{
  "paymentKey": "tossPayments_key_abc123",
  "orderId": "660e8400-e29b-41d4-a716-446655440001",
  "amount": 9900
}
```

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `paymentKey` | String | ✅ | 토스페이먼츠 SDK에서 전달받은 paymentKey |
| `orderId` | UUID | ✅ | 결제할 주문 ID |
| `amount` | Integer | ✅ | 결제 금액 (원화). Order 서비스 금액과 일치 여부 서버 검증 |

#### Response

**200 OK**

```json
{
  "success": true,
  "data": {
    "paymentId": "550e8400-e29b-41d4-a716-446655440000"
  },
  "message": "success"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| `paymentId` | UUID | 생성된 Payment 식별자 (이후 조회·환불 요청 시 사용) |

---

### POST /payments/{paymentId}/refund — 환불 요청

- UC: UC-PAYMENT-04
- 인증: 필요
- 필요 역할: BUYER
- 전체 취소만 지원 (부분 취소 미지원)

#### Path Parameters

| 파라미터 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `paymentId` | UUID | ✅ | 환불할 Payment ID |

#### Request

Body 없음

#### Response

**202 Accepted**

```json
{
  "success": true,
  "data": null,
  "message": "success"
}
```

---

## Kafka 이벤트

| 이벤트 토픽 | 발행 시점 | 설명 |
|------------|---------|------|
| `payment.approved` | PG 승인 콜백 처리 완료 후 | 결제 승인 이벤트 |
| `payment.canceled` | PG 취소 성공 후 | 결제 취소 이벤트 |
| `payment.cancel_failed` | PG 취소 실패 후 | 결제 취소 실패 이벤트 |
| `payment.refunded` | 구매자 환불 요청 후 PG 환불 성공 시 | 환불 완료 이벤트 |
| `payment.refund_failed` | 구매자 환불 요청 후 PG 환불 실패 시 | 환불 실패 이벤트 |

### 이벤트 Payload

#### payment.approved

- 토픽: `payment.approved`
- 발행 주체: Payment 서비스
- 발행 시점: 토스페이먼츠 confirm API 동기 호출 성공 후

```json
{
  "eventType": "payment.approved",
  "paymentId": "550e8400-e29b-41d4-a716-446655440000",
  "orderId": "660e8400-e29b-41d4-a716-446655440001",
  "userId": "770e8400-e29b-41d4-a716-446655440002",
  "amount": 9900,
  "approvedAt": "2026-06-15T10:01:00Z"
}
```

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `eventType` | String | ✅ | `payment.approved` 고정 |
| `paymentId` | UUID | ✅ | Payment 식별자 |
| `orderId` | UUID | ✅ | 연결된 주문 ID |
| `userId` | UUID | ✅ | 결제 사용자 ID |
| `amount` | Integer | ✅ | 승인 금액 (원화, 소수점 없음) |
| `approvedAt` | DateTime | ✅ | PG 승인 일시 |

#### payment.canceled

- 토픽: `payment.canceled`
- 발행 주체: Payment 서비스
- 발행 시점: `order.cancel_requested` 이벤트 수신 후 PG 취소 성공 시

```json
{
  "eventType": "payment.canceled",
  "paymentId": "550e8400-e29b-41d4-a716-446655440000",
  "orderId": "660e8400-e29b-41d4-a716-446655440001",
  "canceledAt": "2026-06-15T10:05:00Z"
}
```

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `eventType` | String | ✅ | `payment.canceled` 고정 |
| `paymentId` | UUID | ✅ | Payment 식별자 |
| `orderId` | UUID | ✅ | 연결된 주문 ID |
| `canceledAt` | DateTime | ✅ | PG 취소 완료 일시 |

#### payment.cancel_failed

- 토픽: `payment.cancel_failed`
- 발행 주체: Payment 서비스
- 발행 시점: `order.cancel_requested` 이벤트 수신 후 PG 취소 실패 시

```json
{
  "eventType": "payment.cancel_failed",
  "paymentId": "550e8400-e29b-41d4-a716-446655440000",
  "orderId": "660e8400-e29b-41d4-a716-446655440001",
  "reason": "PG 취소 요청 타임아웃",
  "failedAt": "2026-06-15T10:05:00Z"
}
```

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `eventType` | String | ✅ | `payment.cancel_failed` 고정 |
| `paymentId` | UUID | ✅ | Payment 식별자 |
| `orderId` | UUID | ✅ | 연결된 주문 ID |
| `reason` | String | ✅ | PG 취소 실패 사유 |
| `failedAt` | DateTime | ✅ | 취소 실패 일시 |

#### payment.refunded

- 토픽: `payment.refunded`
- 발행 주체: Payment 서비스
- 발행 시점: `POST /payments/{paymentId}/refund` 처리 후 PG 환불 성공 시

```json
{
  "eventType": "payment.refunded",
  "paymentId": "550e8400-e29b-41d4-a716-446655440000",
  "orderId": "660e8400-e29b-41d4-a716-446655440001",
  "userId": "770e8400-e29b-41d4-a716-446655440002",
  "amount": 9900,
  "refundedAt": "2026-06-15T11:00:00Z"
}
```

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `eventType` | String | ✅ | `payment.refunded` 고정 |
| `paymentId` | UUID | ✅ | Payment 식별자 |
| `orderId` | UUID | ✅ | 연결된 주문 ID |
| `userId` | UUID | ✅ | 결제 사용자 ID |
| `amount` | Integer | ✅ | 환불 금액 (전체 환불이므로 원래 결제 금액과 동일, 원화) |
| `refundedAt` | DateTime | ✅ | 환불 완료 일시 |

#### payment.refund_failed

- 토픽: `payment.refund_failed`
- 발행 주체: Payment 서비스
- 발행 시점: `POST /payments/{paymentId}/refund` 처리 후 PG 환불 실패 시

```json
{
  "eventType": "payment.refund_failed",
  "paymentId": "550e8400-e29b-41d4-a716-446655440000",
  "orderId": "660e8400-e29b-41d4-a716-446655440001",
  "userId": "770e8400-e29b-41d4-a716-446655440002",
  "reason": "PG 환불 요청 타임아웃",
  "failedAt": "2026-06-15T11:00:30Z"
}
```

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `eventType` | String | ✅ | `payment.refund_failed` 고정 |
| `paymentId` | UUID | ✅ | Payment 식별자 |
| `orderId` | UUID | ✅ | 연결된 주문 ID |
| `userId` | UUID | ✅ | 결제 사용자 ID |
| `reason` | String | ✅ | 환불 실패 사유 |
| `failedAt` | DateTime | ✅ | 환불 실패 일시 |
