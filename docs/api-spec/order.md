# Order Service API

**Base:** `http://localhost:xxxx/api/v1`

## 공통 사항

- 인증이 필요한 엔드포인트는 `Authorization: Bearer {accessToken}` 헤더 필요
- 토큰 검증은 API Gateway에서 수행. 각 서비스는 헤더(`X-User-Id`, `X-User-Role`)만 읽음

---

## 주문

### POST /orders — 주문 생성

- UC: UC-ORDER-01
- 인증: 필요
- 필요 역할: BUYER
- `productIds` (복수) 또는 `productId` (단건) 중 하나를 전송
- 생성 직후 주문 상태: `PENDING`

#### Request

**장바구니 상품 목록 결제 (복수)**

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| productIds | UUID[] | O | 결제할 상품 ID 목록 |

```json
{
  "productIds": [
    "p1b55b60-5e84-4f3f-b4f1-6c10e1a22222",
    "p2b55b60-5e84-4f3f-b4f1-6c10e1a33333"
  ]
}
```

**단건 결제**

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| productId | UUID | O | 결제할 상품 ID |

```json
{
  "productId": "p1b55b60-5e84-4f3f-b4f1-6c10e1a22222"
}
```

#### Response

`201 Created`

| 필드 | 타입 | 설명 |
|------|------|------|
| orderId | UUID | 생성된 주문 ID |

```json
{
  "success": true,
  "data": {
    "orderId": "9f1c2a7e-4b8d-4e2a-9c11-2d3e4f5a1111"
  },
  "message": "success"
}
```

---

### GET /orders — 내 주문 목록 조회

- 인증: 필요
- 필요 역할: BUYER

#### Query Parameters

| 파라미터 | 타입 | 필수 | 기본값 | 설명 |
|---------|------|------|--------|------|
| page | Integer | N | 1 | 페이지 번호 |
| size | Integer | N | 20 | 페이지 크기. 최대 100 |
| status | Enum | N | - | `PENDING` / `PAID` / `FAILED` / `CANCELED` / `REFUNDED` |
| from | Date | N | - | 조회 시작일 (`yyyy-MM-dd`) |
| to | Date | N | - | 조회 종료일 (`yyyy-MM-dd`) |

#### Response

`200 OK`

| 필드 | 타입 | 설명 |
|------|------|------|
| data[].orderId | UUID | 주문 ID |
| data[].orderProductId | UUID | 주문 항목 ID |
| data[].orderStatus | Enum | 주문 상태 |
| data[].isRefund | Boolean | 환불 여부 |
| data[].productType | Enum | 상품 유형 (`PROMPT` / `TEMPLATE` / `DATASET` / `IMAGE_ASSET`) |
| data[].title | String | 상품명 |
| data[].model | String \| null | AI 모델명. `productType`이 `PROMPT`일 때만 값 존재, 그 외 `null` |
| data[].rating | Float \| null | 리뷰 평점 (1.0 ~ 5.0). 미작성 시 `null` |
| data[].paidAt | DateTime | 결제 완료 시각 |
| data[].createdAt | DateTime | 주문 생성 시각 |
| meta.page | Integer | 현재 페이지 번호 |
| meta.size | Integer | 페이지 크기 |
| meta.total | Integer | 전체 항목 수 |
| meta.hasNext | Boolean | 다음 페이지 존재 여부 |

```json
{
  "success": true,
  "data": [
    {
      "orderId": "9f1c2a7e-4b8d-4e2a-9c11-2d3e4f5a1111",
      "orderProductId": "9f1c2a7e-4b8d-4e2a-9c11-2d3e4f5a1234",
      "orderStatus": "PAID",
      "isRefund": true,
      "productType": "PROMPT",
      "title": "면접 답변 프롬프트",
      "model": "gpt-4o",
      "rating": 4.5,
      "paidAt": "2026-06-18T10:45:00",
      "createdAt": "2026-06-18T10:40:00"
    }
  ],
  "message": "success",
  "meta": {
    "page": 1,
    "size": 20,
    "total": 15,
    "hasNext": false
  }
}
```

---

### GET /orders/{orderId} — 주문 상세 조회

- UC: UC-ORDER-02
- 인증: 필요
- 필요 역할: ADMIN / SELLER / USER
- 역할별 접근 범위 상이

#### Path Parameters

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| orderId | UUID | 주문 ID |

#### Response

`200 OK`

| 필드 | 타입 | 설명 |
|------|------|------|
| orderId | UUID | 주문 ID |
| orderNumber | String | 주문 번호 |
| buyerId | UUID | 구매자 ID |
| orderStatus | Enum | 주문 상태 (`PENDING` / `PAID` / `FAILED` / `CANCELED` / `REFUNDED`) |
| totalOrderAmount | Integer | 총 주문 금액 |
| totalItemCount | Integer | 총 상품 수 |
| paidAt | DateTime \| null | 결제 완료 시각 |
| canceledAt | DateTime \| null | 취소 시각 |
| refundedAt | DateTime \| null | 환불 시각 |
| createdAt | DateTime | 주문 생성 시각 |
| canCancel | Boolean | 취소 가능 여부 |
| cancelUnavailableReason | String \| null | 취소 불가 사유. 취소 가능 시 `null` |
| hasDownloadedProduct | Boolean | 주문 내 하나 이상의 상품을 열람한 경우 `true` |
| orderProducts[].orderProductId | UUID | 주문 항목 ID |
| orderProducts[].productId | UUID | 상품 ID |
| orderProducts[].sellerId | UUID | 판매자 ID |
| orderProducts[].productTitle | String | 상품명 (구매 당시 스냅샷) |
| orderProducts[].productType | Enum | 상품 유형 (`PROMPT` / `TEMPLATE` / `DATASET` / `IMAGE_ASSET`) |
| orderProducts[].productAmount | Integer | 상품 가격 (구매 당시 스냅샷) |
| orderProducts[].orderProductStatus | Enum | 항목 상태 (`PENDING` / `PAID` / `FAILED` / `CANCELED` / `REFUNDED`) |
| orderProducts[].isDownload | Boolean | 해당 항목 열람 여부 |
| orderProducts[].contentAvailable | Boolean | 콘텐츠 열람 가능 여부 (`PAID` 상태일 때 `true`) |

```json
{
  "success": true,
  "data": {
    "orderId": "9f1c2a7e-4b8d-4e2a-9c11-2d3e4f5a1111",
    "orderNumber": "ORD-20260618-000001",
    "buyerId": "7c2f6e91-2c1b-4a3b-9f99-3f527f7d1234",
    "orderStatus": "PAID",
    "totalOrderAmount": 30000,
    "totalItemCount": 2,
    "paidAt": "2026-06-18T10:45:00",
    "canceledAt": null,
    "refundedAt": null,
    "createdAt": "2026-06-18T10:40:00",
    "canCancel": true,
    "cancelUnavailableReason": null,
    "hasDownloadedProduct": false,
    "orderProducts": [
      {
        "orderProductId": "op1c2a7e-4b8d-4e2a-9c11-2d3e4f5a2222",
        "productId": "p1b55b60-5e84-4f3f-b4f1-6c10e1a22222",
        "sellerId": "s1b55b60-5e84-4f3f-b4f1-6c10e1a33333",
        "productTitle": "면접 답변 프롬프트",
        "productType": "PROMPT",
        "productAmount": 15000,
        "orderProductStatus": "PAID",
        "isDownload": false,
        "contentAvailable": true
      }
    ]
  },
  "message": "success"
}
```

---

### GET /orders/{orderId}/content/{orderProductId} — 구매 상품 열람

- 인증: 필요
- 필요 역할: BUYER
- 구매 완료 후 콘텐츠 열람

#### Path Parameters

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| orderId | UUID | 주문 ID |
| orderProductId | UUID | 주문 항목 ID |

#### Response

`200 OK`

| 필드 | 타입 | 설명 |
|------|------|------|
| orderId | UUID | 주문 ID |
| orderProductId | UUID | 주문 항목 ID |
| orderNumber | String | 주문 번호 |
| productId | UUID | 상품 ID |
| isDownload | Boolean | 콘텐츠 열람 여부. 최초 열람 시 `true`로 갱신 |
| productTitle | String | 상품명 (**논의 중** — 지희님과 확인 필요) |
| contentUrl | String | 콘텐츠 S3 URL (**논의 중** — 지희님과 확인 필요) |

```json
{
  "success": true,
  "data": {
    "orderId": "9f1c2a7e-4b8d-4e2a-9c11-2d3e4f5a1111",
    "orderProductId": "op1c2a7e-4b8d-4e2a-9c11-2d3e4f5a2222",
    "orderNumber": "ORD-20260618-000001",
    "productId": "p1b55b60-5e84-4f3f-b4f1-6c10e1a22222",
    "isDownload": true,
    "productTitle": "면접 답변 프롬프트",
    "contentUrl": "https://s3.ap-northeast-2.amazonaws.com/prompthub-content/..."
  },
  "message": "success"
}
```

> **TODO**: `productTitle`, `contentUrl` 포함 여부 지희님과 논의 필요

---

### POST /orders/review — 리뷰 평점 생성 및 수정

- 인증: 필요
- 필요 역할: BUYER

#### Request

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| productId | UUID | O | 리뷰를 작성할 상품 ID |
| rating | Integer | O | 평점 (1 ~ 5) |

```json
{
  "productId": "9f1c2a7e-4b8d-4e2a-9c11-2d3e12345678",
  "rating": 4
}
```

#### Response

`200 OK`

```json
{
  "success": true,
  "data": null,
  "message": "success"
}
```

---

### GET /orders/payments — 결제 내역 목록 조회

- 인증: 필요

#### Response

`200 OK`

| 필드 | 타입 | 설명 |
|------|------|------|
| data[].orderId | UUID | 주문 ID |
| data[].orderProductId | UUID | 주문 항목 ID |
| data[].paymentId | UUID | 결제 ID |
| data[].paymentStatus | Enum | 결제 상태 (`PAID` / `CANCELED` / `REFUNDED` 등) |
| data[].isRefund | Boolean | 환불 여부 |
| data[].productType | Enum | 상품 유형 (`PROMPT` / `TEMPLATE` / `DATASET` / `IMAGE_ASSET`) |
| data[].title | String | 상품명 |
| data[].amount | Integer | 결제 금액 |
| data[].paidAt | DateTime | 결제 완료 시각 |
| meta.page | Integer | 현재 페이지 번호 |
| meta.size | Integer | 페이지 크기 |
| meta.total | Integer | 전체 항목 수 |
| meta.hasNext | Boolean | 다음 페이지 존재 여부 |

```json
{
  "success": true,
  "data": [
    {
      "orderId": "9f1c2a7e-4b8d-4e2a-9c11-2d3e4f5a1111",
      "orderProductId": "9f1c2a7e-4b8d-4e2a-9c11-2d3e4f5a1234",
      "paymentId": "9f1c2a7e-4b8d-4e2a-9c11-2d3e4f5a1234",
      "paymentStatus": "PAID",
      "isRefund": true,
      "productType": "PROMPT",
      "title": "면접 답변 프롬프트",
      "amount": 1900,
      "paidAt": "2026-06-18T10:45:00"
    }
  ],
  "message": "success",
  "meta": {
    "page": 1,
    "size": 20,
    "total": 15,
    "hasNext": false
  }
}
```

---

## 장바구니

### GET /cart — 장바구니 조회

- UC: UC-CART-01
- 인증: 필요
- 필요 역할: BUYER

#### Response

`200 OK`

| 필드 | 타입 | 설명 |
|------|------|------|
| cartId | UUID | 장바구니 ID |
| buyerId | UUID | 구매자 ID |
| totalAmount | Integer | 장바구니 전체 금액 합계 |
| totalItemCount | Integer | 담긴 상품 수 |
| products | Array | 장바구니 상품 목록 |
| products[].cartProductId | UUID | 장바구니 항목 ID |
| products[].productId | UUID | 상품 ID |
| products[].productTitle | String | 상품명 |
| products[].productType | String | 상품 유형 (`PROMPT` / `TEMPLATE` / `DATASET` / `IMAGE_ASSET`) |
| products[].productAmount | Integer | 상품 가격 |
| products[].thumbnailUrl | String | 썸네일 이미지 URL |
| products[].sellerId | UUID | 판매자 ID |
| products[].sellerNickname | String | 판매자 닉네임 |
| products[].productStatus | String | 상품 판매 상태 (`ON_SALE` 등) |
| products[].addedAt | DateTime | 장바구니에 담은 시각 |

```json
{
  "success": true,
  "data": {
    "cartId": "4f9f8e2e-8b4a-4b7d-9a8f-94d9c8e2f111",
    "buyerId": "7c2f6e91-2c1b-4a3b-9f99-3f527f7d1234",
    "totalAmount": 30000,
    "totalItemCount": 2,
    "products": [
      {
        "cartProductId": "c1b55b60-5e84-4f3f-b4f1-6c10e1a11111",
        "productId": "p1b55b60-5e84-4f3f-b4f1-6c10e1a22222",
        "productTitle": "면접 답변 프롬프트",
        "productType": "PROMPT",
        "productAmount": 15000,
        "thumbnailUrl": "https://cdn.prompthub.io/products/thumbnail-1.png",
        "sellerId": "s1b55b60-5e84-4f3f-b4f1-6c10e1a33333",
        "sellerNickname": "프롬프트상점",
        "productStatus": "ON_SALE",
        "addedAt": "2026-06-18T10:30:00"
      }
    ]
  },
  "message": "success"
}
```

---

### POST /cart/products — 장바구니 담기

- UC: UC-CART-02
- 인증: 필요
- 필요 역할: BUYER
- 담기 시 Product Service snapshot API 호출하여 ON_SALE 상태 확인

#### Request

**Body**

```json
{
  "productId": "uuid-product"
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| productId | UUID | O | 장바구니에 담을 상품 ID |
```

#### Response

`201 Created`

| 필드 | 타입 | 설명 |
|------|------|------|
| cartId | UUID | 장바구니 ID |
| cartProductId | UUID | 생성된 장바구니 항목 ID |
| productId | UUID | 담긴 상품 ID |
| productTitle | String | 상품명 |
| productAmount | Integer | 상품 가격 |
| totalAmount | Integer | 업데이트된 장바구니 전체 금액 |
| totalItemCount | Integer | 업데이트된 장바구니 상품 수 |
| addedAt | DateTime | 담은 시각 |

```json
{
  "success": true,
  "data": {
    "cartId": "4f9f8e2e-8b4a-4b7d-9a8f-94d9c8e2f111",
    "cartProductId": "c1b55b60-5e84-4f3f-b4f1-6c10e1a11111",
    "productId": "p1b55b60-5e84-4f3f-b4f1-6c10e1a22222",
    "productTitle": "면접 답변 프롬프트",
    "productAmount": 15000,
    "totalAmount": 30000,
    "totalItemCount": 2,
    "addedAt": "2026-06-18T10:30:00"
  },
  "message": "success"
}
```

---

### DELETE /cart/products/{cartProductId} — 장바구니 상품 삭제

- UC: UC-CART-03
- 인증: 필요
- 필요 역할: BUYER

#### Path Parameters

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| cartProductId | UUID | 장바구니 항목 ID |

#### Request

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| cartProductId | UUID | O | 삭제할 장바구니 항목 ID (Path Parameter와 동일) |

```json
{
  "cartProductId": "uuid-product-id"
}
```

#### Response

`200 OK`

```json
{
  "success": true,
  "data": null,
  "message": "success"
}
```

---

## 관리자

### GET /admin/orders — 전체 주문 관리 내역

- 인증: 필요
- 필요 역할: ADMIN

#### Query Parameters

| 파라미터 | 타입 | 필수 | 기본값 | 설명 |
|---------|------|------|--------|------|
| orderStatus | Enum | N | ALL | `ALL` / `PENDING` / `PAID` / `FAILED` / `CANCELED` / `REFUNDED` |
| page | Integer | N | 1 | 페이지 번호 |
| size | Integer | N | 20 | 페이지 크기 |

#### Response

`200 OK`

| 필드 | 타입 | 설명 |
|------|------|------|
| data[].orderId | UUID | 주문 ID |
| data[].sellerNickname | String | 판매자 닉네임 |
| data[].productTitle | String | 상품명 |
| data[].totalOrderCount | Integer | 주문 항목 수 |
| data[].totalOrderAmount | Integer | 총 주문 금액 |
| data[].orderStatus | Enum | 주문 상태 |
| data[].createdAt | DateTime | 주문 생성 시각 |
| meta.page | Integer | 현재 페이지 번호 |
| meta.size | Integer | 페이지 크기 |
| meta.total | Integer | 전체 항목 수 |
| meta.hasNext | Boolean | 다음 페이지 존재 여부 |

```json
{
  "success": true,
  "data": [
    {
      "orderId": "",
      "sellerNickname": "",
      "productTitle": "",
      "totalOrderCount": "",
      "totalOrderAmount": "",
      "orderStatus": "",
      "createdAt": ""
    }
  ],
  "message": "success",
  "meta": {
    "page": 1,
    "size": 20,
    "total": 15,
    "hasNext": false
  }
}
```

---

### GET /admin/orders/month — 이번 달 거래액

- 인증: 필요
- 필요 역할: ADMIN

#### Response

`200 OK`

| 필드 | 타입 | 설명 |
|------|------|------|
| monthlyTransactionAmount | Integer | 이번 달 총 거래액 |

```json
{
  "success": true,
  "data": {
    "monthlyTransactionAmount": 1250000
  },
  "message": "success"
}
```

---

### GET /admin/orders/weekend — 최근 7일 거래량

- 인증: 필요
- 필요 역할: ADMIN

#### Response

`200 OK`

| 필드 | 타입 | 설명 |
|------|------|------|
| totalTransactionCount | Integer | 최근 7일 총 거래 건수 |
| totalTransactionAmount | Integer | 최근 7일 총 거래액 |
| period.startDate | Date | 조회 시작일 (`yyyy-MM-dd`) |
| period.endDate | Date | 조회 종료일 (`yyyy-MM-dd`) |
| dailyTransactions[].date | Date | 일자 (`yyyy-MM-dd`) |
| dailyTransactions[].transactionCount | Integer | 해당 일 거래 건수 |
| dailyTransactions[].transactionAmount | Integer | 해당 일 거래액 |

```json
{
  "success": true,
  "data": {
    "totalTransactionCount": 42,
    "totalTransactionAmount": 980000,
    "period": {
      "startDate": "2026-06-12",
      "endDate": "2026-06-18"
    },
    "dailyTransactions": [
      {
        "date": "2026-06-12",
        "transactionCount": 5,
        "transactionAmount": 120000
      },
      {
        "date": "2026-06-13",
        "transactionCount": 8,
        "transactionAmount": 180000
      },
      {
        "date": "2026-06-14",
        "transactionCount": 4,
        "transactionAmount": 90000
      },
      {
        "date": "2026-06-15",
        "transactionCount": 7,
        "transactionAmount": 150000
      },
      {
        "date": "2026-06-16",
        "transactionCount": 6,
        "transactionAmount": 110000
      },
      {
        "date": "2026-06-17",
        "transactionCount": 9,
        "transactionAmount": 230000
      },
      {
        "date": "2026-06-18",
        "transactionCount": 3,
        "transactionAmount": 100000
      }
    ]
  },
  "message": "success"
}
```

---

## 내부 API (Internal)

### GET /internal/orders/paid — 정산 대상 PAID 주문 조회

- 인증: 필요
- 호출 대상: Settlement Service
- 정산 배치에서 사용

#### Query Parameters

#### Response
