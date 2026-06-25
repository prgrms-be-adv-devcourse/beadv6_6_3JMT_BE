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
| `X-User-Role` | String | ✅ | 쉼표 구분 복합 역할 (예: `BUYER,SELLER`) |

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

| 상태 코드 | 설명 | 에러 코드 |
| --- | --- | --- |
| `200` | 결제 승인 완료 | — |
| `400` | 입력값 오류 | `V001` |
| `400` | PG사 결제 실패 | `PAY_FAILED` |
| `401` | 토큰 만료 | `A003` |
| `403` | BUYER 역할 없음 | `PAY007` |
| `409` | 이미 결제된 주문 | `PAY002` |
| `502` | PG사 처리 오류 | `PAY003` |

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
- 필요 역할: BUYER 역할 보유자 (BUYER 없는 관리 전용 계정 불가)
- 전체 취소만 지원 (부분 취소 미지원)

#### Path Parameters

| 파라미터 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `paymentId` | UUID | ✅ | 환불할 Payment ID |

#### Request

Body 없음

#### Response

| 상태 코드 | 설명 | 에러 코드 |
| --- | --- | --- |
| `202` | 환불 요청 접수 완료 | — |
| `400` | 환불 불가 상태 | `PAY004` |
| `401` | 토큰 만료 | `A003` |
| `403` | BUYER 역할 없음 | `PAY007` |
| `403` | 본인 결제 건이 아님 | `PAY006` |
| `404` | 결제 건 없음 | `PAY005` |

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

| 이벤트 토픽 | 발행 시점 | 구독자 | 구독자 처리 내용 |
|------------|---------|--------|----------------|
| `payment.approved` | Toss confirm 성공 | Order | Order PAID 전환 + `is_download = true` |
| `payment.refunded` | PG 환불 성공 | Order | Order REFUNDED 전환 + `is_download = false` |

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

---

## PaymentStatus

| 상태 | 설명 |
| --- | --- |
| `READY` | Payment 레코드 생성, PG 요청 전 |
| `REQUESTED` | PG사에 결제 요청 전송 완료 |
| `PAID` | PG사 결제 승인 완료 |
| `FAILED` | PG사 결제 실패 |
| `REFUNDING` | 환불 요청 접수, PG 환불 진행 중 |
| `REFUNDED` | 환불 완료 |
| `UNKNOWN` | PG 응답 불명확, 수동 확인 필요 |
