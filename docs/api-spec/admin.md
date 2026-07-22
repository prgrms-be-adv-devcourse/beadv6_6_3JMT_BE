# Admin Service API

**Base:** `http://localhost:8080/api/v2`

## GET /admin/home — 어드민 홈 통합 조회

- 인증: 필요
- 필요 역할: `ADMIN`
- 기준 시간대: `Asia/Seoul`
- 최근 7일은 오늘을 포함하며, 데이터가 없는 날짜도 `0`으로 반환
- 검수 대기 상품은 전체 건수와 오래된 순 최대 4건을 반환

기존에 화면에서 각각 조회하던 회원, 주문, 정산, 상품 데이터를 한 요청으로 조회한다.

### Response 200

```json
{
  "success": true,
  "data": {
    "generatedAt": "2026-07-22T15:30:00+09:00",
    "users": {
      "totalUsers": 1200,
      "todayNewUsers": 18
    },
    "transactions": {
      "monthlyTransactionAmount": 8530000,
      "recent7Days": {
        "totalTransactionCount": 74,
        "totalTransactionAmount": 2140000,
        "period": {
          "startDate": "2026-07-16",
          "endDate": "2026-07-22"
        },
        "dailyTransactions": [
          {
            "date": "2026-07-16",
            "transactionCount": 8,
            "transactionAmount": 230000
          }
        ]
      }
    },
    "settlements": {
      "pendingApprovalAmount": 1275000.00,
      "pendingApprovalCount": 9
    },
    "pendingProducts": {
      "totalCount": 12,
      "items": [
        {
          "productId": "8cb888c3-bcdc-4458-885b-ec7281ec3ef0",
          "title": "상품명",
          "sellerNickname": "판매자",
          "productType": "PROMPT",
          "model": "GPT",
          "amount": 10000,
          "status": "PENDING_REVIEW",
          "createdAt": "2026-07-20T10:00:00"
        }
      ]
    }
  },
  "message": "success"
}
```

`monthlyTransactionAmount`와 최근 7일 거래액은 완료 주문액에서 같은 기간에 환불된 상품 금액을 차감한 값이다. `pendingApprovalAmount`와 `pendingApprovalCount`는 `WAITING`, `APPROVAL_ON_HOLD` 상태를 합산한다.
