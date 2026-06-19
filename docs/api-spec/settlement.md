# Settlement Service API

**Base:** `http://localhost:xxxx/api/v1`

## 공통 사항

- 인증이 필요한 엔드포인트는 `Authorization: Bearer {accessToken}` 헤더 필요
- 토큰 검증은 API Gateway에서 수행. 각 서비스는 헤더(`X-User-Id`, `X-User-Role`)만 읽음
- 정산 배치는 자동(스케줄러) 또는 관리자 수동 실행

---

## 판매자

### GET /sellers/me/settlements — 내 정산 내역 조회

- UC: UC-SETTLEMENT-02
- 인증: 필요
- 필요 역할: SELLER

#### Query Parameters

| 파라미터 | 타입 | 필수 | 기본값 | 설명 |
|---------|------|------|--------|------|
| status | String | N | `ALL` | 정산 상태 필터 (`ALL` \| `PENDING_APPROVAL` \| `SETTLEMENT_ON_HOLD` \| `APPROVED` \| `PAYOUT_REQUESTED` \| `PAYOUT_ON_HOLD` \| `PAID` \| `CANCELLED`) |
| period | String | N | - | 조회 기준 월 (`YYYY-MM`) |
| page | Integer | N | `0` | 페이지 번호 (0부터 시작) |
| size | Integer | N | `10` | 페이지당 항목 수 |
| sort | String | N | `periodStart,desc` | 정렬 조건 |

#### Status Parameter

| 값 | 설명 |
|----|------|
| `ALL` | 전체 |
| `PENDING_APPROVAL` | 대기 |
| `SETTLEMENT_ON_HOLD` | 승인 보류 |
| `APPROVED` | 승인 |
| `PAYOUT_REQUESTED` | 지급 신청 |
| `PAYOUT_ON_HOLD` | 지급 보류 |
| `PAID` | 지급 완료 |
| `CANCELLED` | 취소 |

#### Request Example

```
GET /api/v1/sellers/me/settlements?status=ALL&page=0&size=10&sort=periodStart,desc
Authorization: Bearer {accessToken}
```

#### Response 200

```json
{
  "success": true,
  "data": {
    "content": [
      {
        "settlementId": "b1e2c3d4-1111-2222-3333-abcdefabcdef",
        "period": "2026-06",
        "periodStart": "2026-06-01",
        "periodEnd": "2026-06-30",
        "salesCount": 22,
        "grossAmount": 320000,
        "feeAmount": 48000,
        "refundAmount": 0,
        "adjustmentAmount": 12000,
        "payoutAmount": 260000,
        "status": "PENDING_APPROVAL",
        "displayStatus": "대기",
        "availableActions": []
      },
      {
        "settlementId": "c2e3f4a5-1111-2222-3333-abcdefabcdef",
        "period": "2026-05",
        "periodStart": "2026-05-01",
        "periodEnd": "2026-05-31",
        "salesCount": 18,
        "grossAmount": 260000,
        "feeAmount": 39000,
        "refundAmount": 0,
        "adjustmentAmount": 1000,
        "payoutAmount": 220000,
        "status": "APPROVED",
        "displayStatus": "승인",
        "availableActions": [
          {
            "type": "REQUEST_PAYOUT",
            "label": "지급 신청하기"
          }
        ]
      }
    ],
    "page": {
      "number": 0,
      "size": 10,
      "totalElements": 2,
      "totalPages": 1
    }
  },
  "message": "success"
}
```

#### Response Fields

`payoutAmount = grossAmount - feeAmount - refundAmount - adjustmentAmount`

| 필드 | 설명 |
|------|------|
| `grossAmount` | 정산 기간 내 총 거래액 |
| `feeAmount` | 판매 수수료 |
| `refundAmount` | 환불 차감액 |
| `adjustmentAmount` | 기타 조정 금액 |
| `payoutAmount` | 최종 지급 예정 또는 지급 완료 금액 |

---

### GET /sellers/me/settlements/summary — 정산 요약 및 기타 정보

- 인증: 필요
- 필요 역할: SELLER

#### Response 200

```json
{
  "success": true,
  "data": {
    "registeredPromptCount": 3,
    "totalSalesCount": 1342,
    "totalRevenueAmount": 10449800,
    "totalSettlementAmount": 170000
  },
  "message": "success"
}
```

#### Response Fields

| 필드 | 타입 | 설명 |
|------|------|------|
| `registeredPromptCount` | Integer | 등록한 프롬프트 수 |
| `totalSalesCount` | Integer | 누적 판매 건수 |
| `totalRevenueAmount` | Long | 누적 총 거래액 |
| `totalSettlementAmount` | Long | 누적 정산 지급 완료 금액 |

---

## 관리자 — 정산 관리

### GET /admin/settlements — 정산 목록 전체 조회

- 인증: 필요
- 필요 역할: ADMIN

#### Query Parameters

| 파라미터 | 타입 | 필수 | 기본값 | 설명 |
|---------|------|------|--------|------|
| period | String | N | - | 정산 월 (`YYYY-MM`) |
| status | String | N | `ALL` | 정산 상태 필터 (`ALL` \| `PENDING_APPROVAL` \| `APPROVED` \| `SETTLEMENT_ON_HOLD` \| `PAYOUT_ON_HOLD` \| `PAID`) |
| sellerName | String | N | - | 판매자명 검색 |
| page | Integer | N | `1` | 페이지 번호 |
| size | Integer | N | `20` | 페이지당 항목 수 |

#### Request Example

```
GET /api/v1/admin/settlements?period=2026-05&status=ALL&page=1&size=20
Authorization: Bearer {accessToken}
```

#### Response 200

```json
{
  "success": true,
  "data": [
    {
      "settlementBatchId": "9f1caaaa-1111-2222-3333-444455556666",
      "settlementId": "b1e2c3d4-1111-2222-3333-444455556666",
      "sellerId": "88aaaaaa-1111-2222-3333-444455556666",
      "sellerName": "임재현",
      "storeName": "데브플로우",
      "periodStart": "2026-05-01",
      "periodEnd": "2026-05-31",
      "productCount": 142,
      "totalAmount": 11240000,
      "refundAmount": 0,
      "feeAmount": 1686000,
      "settlementTotalAmount": 9554000,
      "settlementStatus": "PENDING_APPROVAL",
      "payoutStatus": "NOT_READY",
      "calculatedAt": "2026-06-01T02:00:00Z",
      "confirmedAt": null,
      "paidAt": null,
      "availableActions": [
        "APPROVE",
        "HOLD"
      ]
    }
  ],
  "message": "success",
  "meta": {
    "page": 1,
    "size": 20,
    "total": 8,
    "hasNext": false
  }
}
```

#### Response Fields

| 필드 | 타입 | 설명 |
|------|------|------|
| `settlementBatchId` | UUID | 정산 배치 ID. 배치 기간 + 단위별로 생성 |
| `settlementId` | UUID | 정산 ID |
| `sellerId` | UUID | 판매자 ID |
| `sellerName` | String | 판매자명 |
| `storeName` | String | 스토어명 |
| `periodStart` | Date | 정산 기간 시작일 |
| `periodEnd` | Date | 정산 기간 종료일 |
| `productCount` | Integer | 정산 기간 내 판매 건수 |
| `totalAmount` | Long | 총 거래액 |
| `refundAmount` | Long | 환불 차감액 |
| `feeAmount` | Long | 판매 수수료 |
| `settlementTotalAmount` | Long | 최종 정산 금액 (`totalAmount - refundAmount - feeAmount`) |
| `settlementStatus` | String | 정산 승인 상태 |
| `payoutStatus` | String | 지급 처리 상태 |
| `calculatedAt` | DateTime | 정산 계산 시각 |
| `confirmedAt` | DateTime | 정산 승인 시각. 미승인 시 `null` |
| `paidAt` | DateTime | 지급 완료 시각. 미지급 시 `null` |
| `availableActions` | String[] | 현재 상태에서 수행 가능한 액션 목록 |

---

### GET /admin/settlements/summary — 정산 요약 조회

- 인증: 필요
- 필요 역할: ADMIN

#### Response 200

```json
{
  "success": true,
  "data": {
    "period": "2026-05",
    "items": [
      {
        "status": "PENDING_APPROVAL",
        "totalAmount": 16200000,
        "count": 2
      },
      {
        "status": "APPROVED",
        "totalAmount": 10670000,
        "count": 2
      },
      {
        "status": "PAYOUT_ON_HOLD",
        "totalAmount": 2330000,
        "count": 1
      },
      {
        "status": "PAID",
        "totalAmount": 19200000,
        "count": 3
      }
    ]
  },
  "message": "success"
}
```

#### Response Fields

| 필드 | 타입 | 설명 |
|------|------|------|
| `period` | String | 조회 기준 월 (`YYYY-MM`) |
| `items[].status` | String | 정산 상태 |
| `items[].totalAmount` | Long | 해당 상태의 정산 금액 합계 |
| `items[].count` | Integer | 해당 상태의 정산 건수 |

---

### GET /admin/settlements/{settlementId} — 정산 상세 조회

- 인증: 필요
- 필요 역할: ADMIN

#### Path Parameters

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| `settlementId` | UUID | 정산 ID |

#### Request Example

```
GET /api/v1/admin/settlements/b1e2c3d4-1111-2222-3333-444455556666
Authorization: Bearer {accessToken}
```

#### Response 200

```json
{
  "success": true,
  "data": {
    "settlementId": "b1e2c3d4-1111-2222-3333-444455556666",
    "settlementBatchId": "9f1caaaa-1111-2222-3333-444455556666",
    "sellerId": "88aaaaaa-1111-2222-3333-444455556666",
    "sellerName": "임재현",
    "storeName": "데브플로우",
    "periodStart": "2026-05-01",
    "periodEnd": "2026-05-31",
    "productCount": 142,
    "totalAmount": 11240000,
    "refundAmount": 0,
    "feeAmount": 1686000,
    "settlementTotalAmount": 9554000,
    "settlementStatus": "PENDING_APPROVAL",
    "payoutStatus": "NOT_READY",
    "failureReason": null,
    "calculatedAt": "2026-06-01T02:00:00Z",
    "confirmedAt": null,
    "paidAt": null,
    "payoutReference": null,
    "createdAt": "2026-06-01T02:00:00Z",
    "updatedAt": "2026-06-01T02:00:00Z"
  },
  "message": "success"
}
```

#### Response Fields

목록 조회(`GET /admin/settlements`) 응답 필드와 동일하며, 아래 필드가 추가된다.

| 필드 | 타입 | 설명 |
|------|------|------|
| `failureReason` | String | 정산 실패 사유. 정상 처리 시 `null` |
| `payoutReference` | String | 지급 참조 번호. 지급 전 `null` |
| `createdAt` | DateTime | 정산 레코드 생성 시각 |
| `updatedAt` | DateTime | 정산 레코드 최종 수정 시각 |

---

### GET /admin/settlements/{settlementId}/details — 정산 상세 라인 전체 조회

- 인증: 필요
- 필요 역할: ADMIN

#### Path Parameters

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| `settlementId` | UUID | 정산 ID |

#### Query Parameters

| 파라미터 | 타입 | 필수 | 기본값 | 설명 |
|---------|------|------|--------|------|
| page | Integer | N | `1` | 페이지 번호 |
| size | Integer | N | `20` | 페이지당 항목 수 |
| lineType | String | N | - | 라인 유형 필터 (`SALE` \| `REFUND` \| `ADJUSTMENT`) |

#### Request Example

```
GET /api/v1/admin/settlements/b1e2c3d4-1111-2222-3333-444455556666/details?page=1&size=20
Authorization: Bearer {accessToken}
```

#### Response 200

```json
{
  "success": true,
  "data": [
    {
      "settlementLineId": "c2f3aaaa-1111-2222-3333-444455556666",
      "settlementId": "b1e2c3d4-1111-2222-3333-444455556666",
      "orderProductId": "5c6daaaa-1111-2222-3333-444455556666",
      "lineType": "SALE",
      "lineAmount": 4900,
      "feeRate": 0.15,
      "feeAmount": 735,
      "lineSettlementAmount": 4165,
      "occurredAt": "2026-05-12T10:00:12Z",
      "createdAt": "2026-06-01T02:00:00Z"
    }
  ],
  "message": "success",
  "meta": {
    "page": 1,
    "size": 20,
    "total": 142,
    "hasNext": true
  }
}
```

#### Response Fields

| 필드 | 타입 | 설명 |
|------|------|------|
| `settlementLineId` | UUID | 정산 라인 ID |
| `settlementId` | UUID | 정산 ID |
| `orderProductId` | UUID | 주문 상품 ID |
| `lineType` | String | 라인 유형. `SALE` / `REFUND` / `ADJUSTMENT` |
| `lineAmount` | Long | 라인 거래 금액 |
| `feeRate` | Double | 수수료율 |
| `feeAmount` | Long | 수수료 금액 |
| `lineSettlementAmount` | Long | 라인 정산 금액 (`lineAmount - feeAmount`) |
| `occurredAt` | DateTime | 거래 발생 시각 |
| `createdAt` | DateTime | 라인 레코드 생성 시각 |

---

## 관리자 — 정산 상태 관리

### PATCH /admin/settlements/{settlementId}/approve — 정산 승인

- UC: UC-SETTLEMENT-04
- 인증: 필요
- 필요 역할: ADMIN
- 상태 전이: `settlementStatus` PENDING_APPROVAL → APPROVED / `payoutStatus` NOT_READY → READY

#### Path Parameters

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| `settlementId` | UUID | 정산 ID |

#### Response 200

```json
{
  "success": true,
  "data": {
    "settlementId": "b1e2c3d4-1111-2222-3333-444455556666",
    "settlementStatus": "APPROVED",
    "payoutStatus": "READY",
    "confirmedAt": "2026-06-02T09:00:00Z",
    "updatedAt": "2026-06-02T09:00:00Z"
  },
  "message": "success"
}
```

#### Response Fields

| 필드 | 타입 | 설명 |
|------|------|------|
| `settlementId` | UUID | 정산 ID |
| `settlementStatus` | String | 변경된 정산 승인 상태 |
| `payoutStatus` | String | 변경된 지급 처리 상태 |
| `confirmedAt` | DateTime | 정산 승인 시각 |
| `updatedAt` | DateTime | 최종 수정 시각 |

---

### PATCH /admin/settlements/{settlementId}/hold — 정산 승인 보류

- UC: UC-SETTLEMENT-04
- 인증: 필요
- 필요 역할: ADMIN
- 상태 전이: `settlementStatus` PENDING_APPROVAL → SETTLEMENT_ON_HOLD / `payoutStatus` 유지 (NOT_READY)

#### Path Parameters

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| `settlementId` | UUID | 정산 ID |

#### Response 200

```json
{
  "success": true,
  "data": {
    "settlementId": "b1e2c3d4-1111-2222-3333-444455556666",
    "settlementStatus": "SETTLEMENT_ON_HOLD",
    "payoutStatus": "NOT_READY",
    "failureReason": "판매자 지급 정보 확인 필요",
    "updatedAt": "2026-06-02T09:10:00Z"
  },
  "message": "success"
}
```

#### Response Fields

| 필드 | 타입 | 설명 |
|------|------|------|
| `settlementId` | UUID | 정산 ID |
| `settlementStatus` | String | 변경된 정산 승인 상태 |
| `payoutStatus` | String | 변경된 지급 처리 상태 |
| `failureReason` | String | 보류 사유 |
| `updatedAt` | DateTime | 최종 수정 시각 |

---

### PATCH /admin/settlements/{settlementId}/release-hold — 정산 승인 보류 해제

- UC: UC-SETTLEMENT-04
- 인증: 필요
- 필요 역할: ADMIN
- 상태 전이: `settlementStatus` SETTLEMENT_ON_HOLD → PENDING_APPROVAL / `payoutStatus` 유지 (NOT_READY)

#### Path Parameters

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| `settlementId` | UUID | 정산 ID |

#### Response 200

```json
{
  "success": true,
  "data": {
    "settlementId": "b1e2c3d4-1111-2222-3333-444455556666",
    "settlementStatus": "PENDING_APPROVAL",
    "payoutStatus": "NOT_READY",
    "failureReason": null,
    "updatedAt": "2026-06-02T09:20:00Z"
  },
  "message": "success"
}
```

#### Response Fields

| 필드 | 타입 | 설명 |
|------|------|------|
| `settlementId` | UUID | 정산 ID |
| `settlementStatus` | String | 변경된 정산 승인 상태 |
| `payoutStatus` | String | 변경된 지급 처리 상태 |
| `failureReason` | String | 보류 해제 시 `null`로 초기화 |
| `updatedAt` | DateTime | 최종 수정 시각 |

---

### PATCH /admin/settlements/{settlementId}/cancel — 정산 취소

- UC: UC-SETTLEMENT-03
- 인증: 필요
- 필요 역할: ADMIN
- 상태 전이: `settlementStatus` PENDING_APPROVAL / APPROVED / SETTLEMENT_ON_HOLD → CANCELLED

#### Path Parameters

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| `settlementId` | UUID | 정산 ID |

#### Response 200

```json
{
  "success": true,
  "data": {
    "settlementId": "b1e2c3d4-1111-2222-3333-444455556666",
    "settlementStatus": "CANCELLED",
    "payoutStatus": "NOT_READY",
    "failureReason": "정산 대상 데이터 오류로 인한 취소",
    "updatedAt": "2026-06-02T09:30:00Z"
  },
  "message": "success"
}
```

#### Response Fields

| 필드 | 타입 | 설명 |
|------|------|------|
| `settlementId` | UUID | 정산 ID |
| `settlementStatus` | String | 변경된 정산 승인 상태 |
| `payoutStatus` | String | 변경된 지급 처리 상태 |
| `failureReason` | String | 취소 사유 |
| `updatedAt` | DateTime | 최종 수정 시각 |

---

## 관리자 — 지급 관리

### PATCH /admin/settlements/{settlementId}/payout — 정산 지급

- UC: UC-SETTLEMENT-03
- 인증: 필요
- 필요 역할: ADMIN
- 상태 전이: `settlementStatus` 유지 (APPROVED) / `payoutStatus` READY → PAID

#### Path Parameters

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| `settlementId` | UUID | 정산 ID |

#### Response 200

```json
{
  "success": true,
  "data": {
    "settlementId": "b1e2c3d4-1111-2222-3333-444455556666",
    "settlementStatus": "APPROVED",
    "payoutStatus": "PAID",
    "payoutReference": "PAYOUT-2026-06-000088",
    "paidAt": "2026-06-02T15:00:00Z",
    "updatedAt": "2026-06-02T15:00:00Z"
  },
  "message": "success"
}
```

#### Response Fields

| 필드 | 타입 | 설명 |
|------|------|------|
| `settlementId` | UUID | 정산 ID |
| `settlementStatus` | String | 정산 승인 상태 |
| `payoutStatus` | String | 변경된 지급 처리 상태 |
| `payoutReference` | String | 지급 참조 번호 |
| `paidAt` | DateTime | 지급 완료 시각 |
| `updatedAt` | DateTime | 최종 수정 시각 |

---

### PATCH /admin/settlements/{settlementId}/payout-hold — 지급 보류

- UC: UC-SETTLEMENT-03
- 인증: 필요
- 필요 역할: ADMIN
- 상태 전이: `settlementStatus` 유지 (APPROVED) / `payoutStatus` READY → PAYOUT_ON_HOLD

#### Path Parameters

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| `settlementId` | UUID | 정산 ID |

#### Response 200

```json
{
  "success": true,
  "data": {
    "settlementId": "b1e2c3d4-1111-2222-3333-444455556666",
    "settlementStatus": "APPROVED",
    "payoutStatus": "PAYOUT_ON_HOLD",
    "failureReason": "판매자 계좌 검증 실패",
    "updatedAt": "2026-06-02T15:10:00Z"
  },
  "message": "success"
}
```

#### Response Fields

| 필드 | 타입 | 설명 |
|------|------|------|
| `settlementId` | UUID | 정산 ID |
| `settlementStatus` | String | 정산 승인 상태 |
| `payoutStatus` | String | 변경된 지급 처리 상태 |
| `failureReason` | String | 지급 보류 사유 |
| `updatedAt` | DateTime | 최종 수정 시각 |

---

### PATCH /admin/settlements/{settlementId}/payout-hold/release — 지급 보류 해제

- UC: UC-SETTLEMENT-03
- 인증: 필요
- 필요 역할: ADMIN
- 상태 전이: `settlementStatus` 유지 (APPROVED) / `payoutStatus` PAYOUT_ON_HOLD → READY

#### Path Parameters

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| `settlementId` | UUID | 정산 ID |

#### Response 200

```json
{
  "success": true,
  "data": {
    "settlementId": "b1e2c3d4-1111-2222-3333-444455556666",
    "settlementStatus": "APPROVED",
    "payoutStatus": "READY",
    "failureReason": null,
    "updatedAt": "2026-06-02T15:20:00Z"
  },
  "message": "success"
}
```

#### Response Fields

| 필드 | 타입 | 설명 |
|------|------|------|
| `settlementId` | UUID | 정산 ID |
| `settlementStatus` | String | 정산 승인 상태 |
| `payoutStatus` | String | 변경된 지급 처리 상태 |
| `failureReason` | String | 보류 해제 시 `null`로 초기화 |
| `updatedAt` | DateTime | 최종 수정 시각 |

---

## 관리자 — 정산 배치

### POST /admin/settlement-batches — 정산 배치 수동 실행

- 인증: 필요
- 필요 역할: ADMIN
- trigger_type: MANUAL

#### Request

**Body**

```json
{
  "periodStart": "2026-05-01",
  "periodEnd": "2026-05-31",
  "recalculate": false
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `periodStart` | Date | Y | 정산 기간 시작일 |
| `periodEnd` | Date | Y | 정산 기간 종료일 |
| `recalculate` | Boolean | N | 기존 정산 재산정 여부. 기본값 `false` |

#### Response 200

```json
{
  "success": true,
  "data": {
    "settlementBatchId": "9f1caaaa-1111-2222-3333-444455556666",
    "batchNo": "SETTLE-202605-MANUAL-001",
    "periodStart": "2026-05-01",
    "periodEnd": "2026-05-31",
    "status": "COMPLETED",
    "triggerType": "MANUAL",
    "createdSettlementCount": 8,
    "totalAmount": 57000000,
    "refundAmount": 1200000,
    "feeAmount": 8370000,
    "settlementTotalAmount": 47430000,
    "failureReason": null,
    "executedAt": "2026-06-01T10:00:00Z"
  },
  "message": "success"
}
```

#### Response Fields

| 필드 | 타입 | 설명 |
|------|------|------|
| `settlementBatchId` | UUID | 정산 배치 ID |
| `batchNo` | String | 배치 식별 번호. 형식: `SETTLE-{YYYYMM}-{triggerType}-{순번}` |
| `periodStart` | Date | 정산 기간 시작일 |
| `periodEnd` | Date | 정산 기간 종료일 |
| `status` | String | 배치 실행 상태. `COMPLETED` / `FAILED` |
| `triggerType` | String | 배치 실행 유형. `MANUAL` / `SCHEDULED` |
| `createdSettlementCount` | Integer | 생성된 정산 건수 |
| `totalAmount` | Long | 총 거래액 합계 |
| `refundAmount` | Long | 환불 차감액 합계 |
| `feeAmount` | Long | 수수료 합계 |
| `settlementTotalAmount` | Long | 최종 정산 금액 합계 |
| `failureReason` | String | 실패 사유. 정상 처리 시 `null` |
| `executedAt` | DateTime | 배치 실행 시각 |

---

## 내부 API (Internal)

### 정산 배치 자동 실행 (잡 스케줄링)

- 호출 대상: Settlement Service 내부 스케줄러
- trigger_type: SCHEDULED

#### Request

**Body**

```json
{
  "periodStart": "2026-05-01",
  "periodEnd": "2026-05-31"
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `periodStart` | Date | Y | 정산 기간 시작일 |
| `periodEnd` | Date | Y | 정산 기간 종료일 |

#### Response 200

```json
{
  "success": true,
  "data": {
    "settlementBatchId": "9f1caaaa-1111-2222-3333-444455556666",
    "batchNo": "SETTLE-202605-SCHEDULED-001",
    "periodStart": "2026-05-01",
    "periodEnd": "2026-05-31",
    "status": "COMPLETED",
    "triggerType": "SCHEDULED",
    "createdSettlementCount": 8,
    "totalAmount": 57000000,
    "refundAmount": 1200000,
    "feeAmount": 8370000,
    "settlementTotalAmount": 47430000,
    "failureReason": null,
    "executedAt": "2026-06-01T01:00:00Z"
  },
  "message": "success"
}
```

응답 필드는 `POST /admin/settlement-batches` 와 동일. `triggerType`은 `SCHEDULED`로 고정.
