# Settlement Service API

**Base:** `http://localhost:8080/api/v2`

> 정산 공개 API는 `#305 (이슈)`에서 `/api/v2`로 전환했다. 기존 `/api/v1` 경로는 제공하지 않는다.

## 공통 사항

- 인증이 필요한 엔드포인트는 클라이언트가 `Authorization: Bearer {accessToken}` 헤더로 API Gateway 를 호출
- 토큰 검증은 API Gateway에서 수행. 각 서비스는 게이트웨이가 주입하는 헤더(`X-User-Id`, `X-User-Role`)만 읽음
- 모든 응답은 공통 래퍼 `{ "success", "data", "message" }` 로 감싼다. 실패 시 `data` 는 `null`, `code`(예: `S-013`)가 함께 내려간다
- 금액(`BigDecimal`)은 JSON 숫자로 직렬화된다(예: `459000.00`)
- 정산 배치는 자동(스케줄러) 또는 관리자 수동 실행

---

## 판매자

### GET /sellers/me/settlements — 내 정산 내역 조회

- UC: UC-SETTLEMENT-02
- 인증: 필요
- 필요 역할: SELLER
- 본인(`seller_id = X-User-Id`) 정산만 조회

#### Query Parameters

| 파라미터 | 타입 | 필수 | 기본값 | 설명 |
|---------|------|------|--------|------|
| status | String | N | (전체) | 표시 상태 필터 (아래 Status Parameter) |
| period | String | N | - | 조회 기준 월 (`YYYY-MM`) |
| page | Integer | N | `0` | 0-base 페이지 번호 |
| size | Integer | N | `10` | 페이지당 항목 수 |

#### Status Parameter

`status` 는 표시 상태(`SettlementDisplayStatus`) 코드로 필터한다. 미지정 시 전체.

| 값 | 설명 |
|----|------|
| `WAITING` | 대기 |
| `APPROVAL_ON_HOLD` | 승인 보류 |
| `APPROVED` | 승인 |
| `PAYOUT_REQUESTED` | 지급 신청 |
| `PAYOUT_ON_HOLD` | 지급 보류 |
| `PAID` | 지급 완료 |
| `CANCELLED` | 취소 |

#### Request Example

```
GET /api/v2/sellers/me/settlements?status=WAITING&period=2026-06&page=0&size=10
Authorization: Bearer {accessToken}
```

#### Response 200

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "settlementId": "b1e2c3d4-1111-2222-3333-abcdefabcdef",
        "period": "2026-06",
        "periodStart": "2026-06-01",
        "periodEnd": "2026-06-30",
        "salesCount": 22,
        "grossAmount": 320000.00,
        "feeAmount": 48000.00,
        "refundAmount": 0.00,
        "adjustmentAmount": 0,
        "payoutAmount": 272000.00,
        "status": "WAITING",
        "displayStatus": "대기",
        "availableActions": []
      },
      {
        "settlementId": "c2e3f4a5-1111-2222-3333-abcdefabcdef",
        "period": "2026-05",
        "periodStart": "2026-05-01",
        "periodEnd": "2026-05-31",
        "salesCount": 18,
        "grossAmount": 260000.00,
        "feeAmount": 39000.00,
        "refundAmount": 0.00,
        "adjustmentAmount": 0,
        "payoutAmount": 221000.00,
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
    "totalElements": 2,
    "page": 0,
    "size": 10
  },
  "message": "success"
}
```

#### Response Fields

| 필드 | 타입 | 설명 |
|------|------|------|
| `data.items[].settlementId` | UUID | 정산 ID |
| `data.items[].period` | String | 정산 기준 월 (`YYYY-MM`) |
| `data.items[].periodStart` | Date | 정산 기간 시작일 |
| `data.items[].periodEnd` | Date | 정산 기간 종료일 |
| `data.items[].salesCount` | Integer | 판매 건수 |
| `data.items[].grossAmount` | BigDecimal | 총 거래액 |
| `data.items[].feeAmount` | BigDecimal | 판매 수수료 |
| `data.items[].refundAmount` | BigDecimal | 환불 차감액 |
| `data.items[].adjustmentAmount` | BigDecimal | 기타 조정 금액(현재 미사용, `0` 고정) |
| `data.items[].payoutAmount` | BigDecimal | 최종 지급 예정/완료 금액 |
| `data.items[].status` | String | 표시 상태 코드 (`WAITING` 등) |
| `data.items[].displayStatus` | String | 표시 상태 라벨(한글) |
| `data.items[].availableActions` | Object[] | 현재 상태에서 수행 가능한 액션. 항목은 `{ type, label }` |
| `data.totalElements` | Long | 전체 항목 수 |
| `data.page` | Integer | 0-base 페이지 번호 |
| `data.size` | Integer | 페이지 크기 |

> `availableActions` 는 정산이 승인 완료(`displayStatus = APPROVED`)일 때만 `REQUEST_PAYOUT`(지급 신청하기) 1개가 담기고, 그 외에는 빈 배열이다.

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
| `registeredPromptCount` | Integer | 등록한 프롬프트 수. Product 서비스 gRPC 조회로 채우며, 조회 실패 시 `0` |
| `totalSalesCount` | Long | 누적 판매 건수 |
| `totalRevenueAmount` | BigDecimal | 누적 총 거래액 |
| `totalSettlementAmount` | BigDecimal | 누적 정산 지급 완료 금액 |

---

### PATCH /sellers/me/settlements/{settlementId}/payout-request — 판매자 지급 신청

- 인증: 필요
- 필요 역할: SELLER
- 본인(`seller_id = X-User-Id`) 정산만 신청 가능
- 상태 전이: `settlementStatus` 유지 (APPROVED) / `payoutStatus` READY → PAYOUT_REQUESTED

판매자가 승인 완료(지급 준비) 상태의 정산을 지급 신청한다. 신청을 거쳐야 어드민이 지급(PAID)을
처리할 수 있다. 정산 내역 조회 응답의 `availableActions`에 노출되는 `REQUEST_PAYOUT` 액션에 대응한다.

#### Path Parameters

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| `settlementId` | UUID | 정산 ID |

#### Request Example

```
PATCH /api/v2/sellers/me/settlements/b1e2c3d4-1111-2222-3333-444455556666/payout-request
Authorization: Bearer {accessToken}
```

#### Response 200

상태 변경 응답은 공통 `SettlementStatusResponse` 형태다.

```json
{
  "success": true,
  "data": {
    "settlementId": "b1e2c3d4-1111-2222-3333-444455556666",
    "settlementStatus": "APPROVED",
    "payoutStatus": "PAYOUT_REQUESTED",
    "displayStatus": "PAYOUT_REQUESTED",
    "confirmedAt": "2026-06-24T09:00:00",
    "paidAt": null,
    "payoutReference": null,
    "failureReason": null,
    "updatedAt": "2026-06-24T11:00:00"
  },
  "message": "success"
}
```

#### Response Fields

`SettlementStatusResponse` 공통 필드는 [상태 변경 공통 응답](#상태-변경-공통-응답-settlementstatusresponse)을 참고한다.

#### Error Responses

| 상태 | 코드 | 설명 |
|------|------|------|
| `403` | S-014 | 본인 정산이 아님 |
| `404` | S-010 | 정산을 찾을 수 없음 |
| `409` | S-013 | 지급 준비(READY) 상태가 아니라 신청할 수 없음 |

---

## 관리자 — 정산 관리

### GET /admin/settlements — 정산 목록 전체 조회

- 인증: 필요
- 필요 역할: ADMIN

#### Query Parameters

| 파라미터 | 타입 | 필수 | 기본값 | 설명 |
|---------|------|------|--------|------|
| status | String | N | (전체) | 표시 상태 필터 (`WAITING` \| `APPROVAL_ON_HOLD` \| `APPROVED` \| `PAYOUT_REQUESTED` \| `PAYOUT_ON_HOLD` \| `PAID` \| `CANCELLED`) |
| page | Integer | N | `0` | 0-base 페이지 번호 |
| size | Integer | N | `20` | 페이지당 항목 수 |

#### Request Example

```
GET /api/v2/admin/settlements?status=WAITING&page=0&size=20
Authorization: Bearer {accessToken}
```

#### Response 200

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "settlementId": "b1e2c3d4-1111-2222-3333-444455556666",
        "sellerId": "88aaaaaa-1111-2222-3333-444455556666",
        "sellerName": "임재현",
        "periodStart": "2026-06-01",
        "periodEnd": "2026-06-30",
        "productCount": 37,
        "totalAmount": 540000.00,
        "feeTotalAmount": 81000.00,
        "settlementTotalAmount": 459000.00,
        "displayStatus": "WAITING",
        "calculatedAt": "2026-07-01T02:00:00"
      }
    ],
    "totalElements": 16,
    "page": 0,
    "size": 20
  },
  "message": "success"
}
```

> 판매자명(`sellerName`)은 User 서비스 gRPC 동기 조회로 채운다. 조회에 실패하면 `null` 로 내려가며 목록 응답 자체는 정상(200)이다.

#### Response Fields

| 필드 | 타입 | 설명 |
|------|------|------|
| `data.items[].settlementId` | UUID | 정산 ID |
| `data.items[].sellerId` | UUID | 판매자 ID |
| `data.items[].sellerName` | String | 판매자명(상점명). User 서비스 동기 조회로 채우며, 조회 실패 시 `null` |
| `data.items[].periodStart` | Date | 정산 기간 시작일 |
| `data.items[].periodEnd` | Date | 정산 기간 종료일 |
| `data.items[].productCount` | Integer | 정산 기간 내 판매 건수 |
| `data.items[].totalAmount` | BigDecimal | 총 거래액 |
| `data.items[].feeTotalAmount` | BigDecimal | 판매 수수료 |
| `data.items[].settlementTotalAmount` | BigDecimal | 지급액(최종 정산 금액) |
| `data.items[].displayStatus` | String | 표시 상태 (`WAITING` \| `APPROVAL_ON_HOLD` \| `APPROVED` \| `PAYOUT_REQUESTED` \| `PAYOUT_ON_HOLD` \| `PAID` \| `CANCELLED`) |
| `data.items[].calculatedAt` | DateTime | 정산 산출(배치 실행) 시각 |
| `data.totalElements` | Long | 전체 항목 수 |
| `data.page` | Integer | 0-base 페이지 번호 |
| `data.size` | Integer | 페이지 크기 |

---

### GET /admin/settlements/summary — 정산 요약 카드 조회

- 인증: 필요
- 필요 역할: ADMIN

정산 관리 화면 상단 요약 카드(상태별 지급액 합계·건수)를 조회한다. 카드는 `WAITING`,
`APPROVED`, `PAYOUT_ON_HOLD`, `PAID` 4종이 고정 순서로 내려간다.

#### Response 200

```json
{
  "success": true,
  "data": {
    "cards": [
      { "status": "WAITING",        "totalAmount": 1135500.00, "count": 4 },
      { "status": "APPROVED",       "totalAmount": 1067000.00, "count": 2 },
      { "status": "PAYOUT_ON_HOLD", "totalAmount": 233000.00,  "count": 1 },
      { "status": "PAID",           "totalAmount": 1920000.00, "count": 3 }
    ]
  },
  "message": "success"
}
```

#### Response Fields

| 필드 | 타입 | 설명 |
|------|------|------|
| `data.cards[].status` | String | 표시 상태 코드 (`WAITING` \| `APPROVED` \| `PAYOUT_ON_HOLD` \| `PAID`) |
| `data.cards[].totalAmount` | BigDecimal | 해당 상태의 지급액 합계 |
| `data.cards[].count` | Long | 해당 상태의 정산 건수 |

---

## 관리자 — 정산 상태 관리

상태 변경 엔드포인트(승인/보류/해제/지급/지급보류/지급보류해제/판매자 지급신청)는 모두 공통
`SettlementStatusResponse` 를 반환한다. 취소만 별도(`SettlementResponse`)다.

<a id="상태-변경-공통-응답-settlementstatusresponse"></a>

#### 상태 변경 공통 응답 (SettlementStatusResponse)

| 필드 | 타입 | 설명 |
|------|------|------|
| `settlementId` | UUID | 정산 ID |
| `settlementStatus` | String | 정산 승인 상태 (`PENDING_APPROVAL` \| `SETTLEMENT_ON_HOLD` \| `APPROVED` \| `CANCELLED`) |
| `payoutStatus` | String | 지급 처리 상태 (`NOT_READY` \| `READY` \| `PAYOUT_REQUESTED` \| `PAYOUT_ON_HOLD` \| `PAID`) |
| `displayStatus` | String | 표시 상태 (`WAITING` 등) |
| `confirmedAt` | DateTime | 정산 승인 시각. 미승인 시 `null` |
| `paidAt` | DateTime | 지급 완료 시각. 미지급 시 `null` |
| `payoutReference` | String | 지급 참조 번호. 현재 채우는 로직이 없어 항상 `null` |
| `failureReason` | String | 정산 실패 사유. 미설정 시 `null` |
| `updatedAt` | DateTime | 최종 수정 시각 |

> 상태 변경 PATCH 는 요청 바디가 없다. 경로의 `settlementId` 와 헤더(`X-User-Id`, `X-User-Role: ADMIN`)만 받는다.

---

### PATCH /admin/settlements/{settlementId}/approve — 정산 승인

- UC: UC-SETTLEMENT-04
- 인증: 필요 / 역할: ADMIN
- 상태 전이: `settlementStatus` PENDING_APPROVAL → APPROVED / `payoutStatus` NOT_READY → READY

#### Response 200

```json
{
  "success": true,
  "data": {
    "settlementId": "b1e2c3d4-1111-2222-3333-444455556666",
    "settlementStatus": "APPROVED",
    "payoutStatus": "READY",
    "displayStatus": "APPROVED",
    "confirmedAt": "2026-06-02T09:00:00",
    "paidAt": null,
    "payoutReference": null,
    "failureReason": null,
    "updatedAt": "2026-06-02T09:00:00"
  },
  "message": "success"
}
```

#### Error Responses

| 상태 | 코드 | 설명 |
|------|------|------|
| `404` | S-010 | 정산을 찾을 수 없음 |
| `409` | S-013 | 승인 대기(PENDING_APPROVAL) 상태가 아님 |

---

### PATCH /admin/settlements/{settlementId}/hold — 정산 승인 보류

- UC: UC-SETTLEMENT-04
- 인증: 필요 / 역할: ADMIN
- 상태 전이: `settlementStatus` PENDING_APPROVAL → SETTLEMENT_ON_HOLD / `payoutStatus` 유지 (NOT_READY)

#### Response 200

```json
{
  "success": true,
  "data": {
    "settlementId": "b1e2c3d4-1111-2222-3333-444455556666",
    "settlementStatus": "SETTLEMENT_ON_HOLD",
    "payoutStatus": "NOT_READY",
    "displayStatus": "APPROVAL_ON_HOLD",
    "confirmedAt": null,
    "paidAt": null,
    "payoutReference": null,
    "failureReason": null,
    "updatedAt": "2026-06-02T09:10:00"
  },
  "message": "success"
}
```

#### Error Responses

| 상태 | 코드 | 설명 |
|------|------|------|
| `404` | S-010 | 정산을 찾을 수 없음 |
| `409` | S-013 | 승인 대기(PENDING_APPROVAL) 상태가 아님 |

---

### PATCH /admin/settlements/{settlementId}/release-hold — 정산 승인 보류 해제

- UC: UC-SETTLEMENT-04
- 인증: 필요 / 역할: ADMIN
- 상태 전이: `settlementStatus` SETTLEMENT_ON_HOLD → PENDING_APPROVAL / `payoutStatus` 유지 (NOT_READY)

#### Response 200

```json
{
  "success": true,
  "data": {
    "settlementId": "b1e2c3d4-1111-2222-3333-444455556666",
    "settlementStatus": "PENDING_APPROVAL",
    "payoutStatus": "NOT_READY",
    "displayStatus": "WAITING",
    "confirmedAt": null,
    "paidAt": null,
    "payoutReference": null,
    "failureReason": null,
    "updatedAt": "2026-06-02T09:20:00"
  },
  "message": "success"
}
```

#### Error Responses

| 상태 | 코드 | 설명 |
|------|------|------|
| `404` | S-010 | 정산을 찾을 수 없음 |
| `409` | S-013 | 승인 보류(SETTLEMENT_ON_HOLD) 상태가 아님 |

---

### PATCH /admin/settlements/{settlementId}/cancel — 정산 취소

- UC: UC-SETTLEMENT-03
- 인증: 필요 / 역할: ADMIN
- 상태 전이: 지급 완료(PAID)·이미 취소(CANCELLED)가 아닌 정산 → CANCELLED. 묶인 소스 라인은 풀려 재정산 대상이 된다.
- 반환 타입이 다른 상태 변경과 다르다(`SettlementResponse`).

#### Response 200

```json
{
  "success": true,
  "data": {
    "settlementId": "b1e2c3d4-1111-2222-3333-444455556666",
    "sellerId": "88aaaaaa-1111-2222-3333-444455556666",
    "displayStatus": "CANCELLED",
    "canceledAt": "2026-06-02T09:30:00"
  },
  "message": "success"
}
```

#### Response Fields

| 필드 | 타입 | 설명 |
|------|------|------|
| `settlementId` | UUID | 정산 ID |
| `sellerId` | UUID | 판매자 ID |
| `displayStatus` | String | 표시 상태 (`CANCELLED`) |
| `canceledAt` | DateTime | 취소 시각 |

#### Error Responses

| 상태 | 코드 | 설명 |
|------|------|------|
| `404` | S-010 | 정산을 찾을 수 없음 |
| `409` | S-011 | 이미 지급 완료된 정산은 취소 불가 |
| `409` | S-012 | 이미 취소된 정산 |

---

## 관리자 — 지급 관리

> 지급은 판매자 지급 신청을 선행으로 한다. 어드민의 지급·지급 보류는 판매자가 지급 신청한 정산
> (`payoutStatus = PAYOUT_REQUESTED`)에 대해서만 수행한다. (판매자 지급 신청: `PATCH /sellers/me/settlements/{id}/payout-request`)

모든 지급 관리 응답은 공통 `SettlementStatusResponse` 형태다.

### PATCH /admin/settlements/{settlementId}/payout — 정산 지급

- UC: UC-SETTLEMENT-03
- 인증: 필요 / 역할: ADMIN
- 상태 전이: `settlementStatus` 유지 (APPROVED) / `payoutStatus` PAYOUT_REQUESTED → PAID

#### Response 200

```json
{
  "success": true,
  "data": {
    "settlementId": "b1e2c3d4-1111-2222-3333-444455556666",
    "settlementStatus": "APPROVED",
    "payoutStatus": "PAID",
    "displayStatus": "PAID",
    "confirmedAt": "2026-06-02T09:00:00",
    "paidAt": "2026-06-02T15:00:00",
    "payoutReference": null,
    "failureReason": null,
    "updatedAt": "2026-06-02T15:00:00"
  },
  "message": "success"
}
```

#### Error Responses

| 상태 | 코드 | 설명 |
|------|------|------|
| `404` | S-010 | 정산을 찾을 수 없음 |
| `409` | S-013 | 지급 신청(PAYOUT_REQUESTED) 상태가 아님 |

---

### PATCH /admin/settlements/{settlementId}/payout-hold — 지급 보류

- UC: UC-SETTLEMENT-03
- 인증: 필요 / 역할: ADMIN
- 상태 전이: `settlementStatus` 유지 (APPROVED) / `payoutStatus` PAYOUT_REQUESTED → PAYOUT_ON_HOLD

#### Response 200

```json
{
  "success": true,
  "data": {
    "settlementId": "b1e2c3d4-1111-2222-3333-444455556666",
    "settlementStatus": "APPROVED",
    "payoutStatus": "PAYOUT_ON_HOLD",
    "displayStatus": "PAYOUT_ON_HOLD",
    "confirmedAt": "2026-06-02T09:00:00",
    "paidAt": null,
    "payoutReference": null,
    "failureReason": null,
    "updatedAt": "2026-06-02T15:10:00"
  },
  "message": "success"
}
```

#### Error Responses

| 상태 | 코드 | 설명 |
|------|------|------|
| `404` | S-010 | 정산을 찾을 수 없음 |
| `409` | S-013 | 지급 신청(PAYOUT_REQUESTED) 상태가 아님 |

---

### PATCH /admin/settlements/{settlementId}/payout-hold/release — 지급 보류 해제

- UC: UC-SETTLEMENT-03
- 인증: 필요 / 역할: ADMIN
- 상태 전이: `settlementStatus` 유지 (APPROVED) / `payoutStatus` PAYOUT_ON_HOLD → PAYOUT_REQUESTED

#### Response 200

```json
{
  "success": true,
  "data": {
    "settlementId": "b1e2c3d4-1111-2222-3333-444455556666",
    "settlementStatus": "APPROVED",
    "payoutStatus": "PAYOUT_REQUESTED",
    "displayStatus": "PAYOUT_REQUESTED",
    "confirmedAt": "2026-06-02T09:00:00",
    "paidAt": null,
    "payoutReference": null,
    "failureReason": null,
    "updatedAt": "2026-06-02T15:20:00"
  },
  "message": "success"
}
```

#### Error Responses

| 상태 | 코드 | 설명 |
|------|------|------|
| `404` | S-010 | 정산을 찾을 수 없음 |
| `409` | S-013 | 지급 보류(PAYOUT_ON_HOLD) 상태가 아님 |

---

## 관리자 — 정산 배치

> 정산 배치(수동 실행)는 **비동기**다. POST 는 잡을 실행 접수만 하고 즉시 응답하며,
> 완료 여부는 상태 조회 API 를 폴링해서 확인한다. 잡이 완료(`COMPLETED`)되면 정산 목록
> (`GET /admin/settlements`)을 다시 조회해 새로 생성된 정산 건을 표시한다.

### POST /admin/settlements/batch — 정산 배치 수동 실행(비동기)

- 인증: 필요
- 필요 역할: ADMIN
- triggerType: MANUAL (수동 실행은 비동기로 접수)

#### Request

**Headers**

| 헤더 | 필수 | 설명 |
|------|------|------|
| `X-User-Id` | Y | 게이트웨이가 주입하는 관리자 ID. 실행자(actorId)로 기록 |
| `X-User-Role` | Y | 게이트웨이가 주입하는 사용자 역할. `ADMIN` 필요 |

**Body**

```json
{
  "period": "2026-06"
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `period` | String | Y | 정산 대상 월 (`YYYY-MM`) |

#### Response 202

비동기 실행을 접수했음을 의미한다. 응답은 잡 실행 식별자와 시작 시점 상태만 담는다.
정산 건수·금액 합계 같은 결과 정보는 이 응답에 없으며, 완료 후 정산 목록 조회로 얻는다.

```json
{
  "success": true,
  "data": {
    "jobExecutionId": 1024,
    "jobName": "settlementJob",
    "status": "STARTING",
    "startTime": null
  },
  "message": "success"
}
```

#### Response Fields

| 필드 | 타입 | 설명 |
|------|------|------|
| `jobExecutionId` | Long | 잡 실행 식별자. 상태 조회 API 의 path 로 사용 |
| `jobName` | String | 잡 이름. `settlementJob` 고정 |
| `status` | String | 접수 시점 실행 상태. 비동기라 보통 `STARTING` / `STARTED` |
| `startTime` | DateTime | 시작 시각. 접수 직후에는 아직 시작 전이라 `null` 일 수 있음 |

#### Error Responses

| 상태 | 코드 | 설명 |
|------|------|------|
| `400` | S-003 | 요청 값 오류(`period` 누락 등) |
| `500` | S-002 | 정산 배치 잡 실행 실패 |

---

### GET /admin/settlements/batch/{jobExecutionId} — 정산 배치 잡 상태 조회

비동기로 실행한 배치 잡의 진행/완료 상태를 조회한다. 프론트는 이 API 를 폴링(예: 2~5초 간격)해
`status` 가 `COMPLETED`(또는 `FAILED`)가 되면 폴링을 멈추고 정산 목록을 재조회한다.

- 인증: 필요
- 필요 역할: ADMIN

#### Path Parameters

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| `jobExecutionId` | Long | 수동 실행 응답으로 받은 잡 실행 식별자 |

#### Request Example

```
GET /api/v2/admin/settlements/batch/1024
X-User-Id: 88aaaaaa-1111-2222-3333-444455556666
X-User-Role: ADMIN
```

#### Response 200

```json
{
  "success": true,
  "data": {
    "jobExecutionId": 1024,
    "jobName": "settlementJob",
    "status": "COMPLETED",
    "exitCode": "COMPLETED",
    "startTime": "2026-06-03T02:00:00",
    "endTime": "2026-06-03T02:00:12",
    "failureMessage": null
  },
  "message": "success"
}
```

#### Response Fields

| 필드 | 타입 | 설명 |
|------|------|------|
| `jobExecutionId` | Long | 잡 실행 식별자 |
| `jobName` | String | 잡 이름. `settlementJob` 고정 |
| `status` | String | 실행 상태. `STARTING` / `STARTED` / `COMPLETED` / `FAILED` / `STOPPED` 등 |
| `exitCode` | String | 종료 코드. `COMPLETED` / `FAILED` 등. 실행 중에는 `UNKNOWN` |
| `startTime` | DateTime | 시작 시각 |
| `endTime` | DateTime | 종료 시각. 실행 중이면 `null` |
| `failureMessage` | String | 실패 사유. 실패가 아니면 `null` |

#### 폴링 플로우

```
POST  /admin/settlements/batch        → 202 { jobExecutionId, status: STARTING }
GET   /admin/settlements/batch/{id}   → 200 { status: STARTED }      ┐ 반복
GET   /admin/settlements/batch/{id}   → 200 { status: STARTED }      ┘
GET   /admin/settlements/batch/{id}   → 200 { status: COMPLETED }    → 폴링 종료 → 정산 목록 재조회
```

#### Error Responses

| 상태 | 코드 | 설명 |
|------|------|------|
| `404` | S-008 | 해당 `jobExecutionId` 의 잡 실행 이력이 없음 |

---

## 내부 API (Internal)

### 정산 배치 자동 실행 (잡 스케줄링)

- 호출 대상: Settlement Service 내부 스케줄러 (`@Scheduled` cron)
- triggerType: SCHEDULED
- HTTP 엔드포인트가 아니다. 스케줄러가 유스케이스를 직접 호출한다.
- 정산 대상 월: 실행 시점의 직전 월 (`now - 1개월`)

실행 결과(성공/실패)는 별도 응답 없이 `settlement_batch` 레코드의 상태로 남는다.
실패 시 잡 리스너가 해당 배치를 `FAILED` 로 마감하고 사유를 기록한다.
