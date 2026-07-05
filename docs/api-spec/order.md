# Order Service API

**Base:** `http://localhost:{order-service-port}/api/v1`

## 공통 사항

- 외부 인증과 토큰 검증은 API Gateway가 담당한다.
- Order Service는 Gateway가 주입한 trusted header를 읽는다.
- 구매자 API는 `X-User-Id`가 필요하다.
- 관리자 API는 `X-User-Role: ADMIN`이 필요하다.
- 응답 envelope는 `common-module`의 `ApiResult` 또는 `PageResponse` 형식을 따른다.

---

## 주문

### POST /orders - 주문 생성

- 인증: 필요
- 필요 헤더: `X-User-Id`
- 요청 상품 목록으로 `PENDING` 주문과 주문 상품을 생성한다.

#### Request

| 필드 | 타입 | 필수 | 설명 |
|------|------|:----:|------|
| productIds | UUID[] | O | 주문할 상품 ID 목록. 비어 있을 수 없고 각 값은 null일 수 없음 |

```json
{
  "productIds": [
    "11111111-1111-1111-1111-111111111111",
    "22222222-2222-2222-2222-222222222222"
  ]
}
```

#### Response

`200 OK`

| 필드 | 타입 | 설명 |
|------|------|------|
| orderId | UUID | 생성된 주문 ID |
| orderNumber | String | 사용자 노출 주문 번호 |
| buyerId | UUID | 구매자 ID |
| orderStatus | Enum | 주문 상태. 생성 직후 `PENDING` |
| products[].orderProductId | UUID | 주문 상품 ID |
| products[].productId | UUID | 상품 ID |
| products[].sellerId | UUID | 판매자 ID |
| products[].productTitleSnapshot | String | 주문 시점 상품명 스냅샷 |
| products[].productTypeSnapshot | String | 주문 시점 상품 유형 스냅샷 |
| products[].productModelSnapshot | String \| null | 주문 시점 상품 모델명/분류 스냅샷 |
| products[].productAmountSnapshot | Integer | 주문 시점 상품 금액 스냅샷 |
| products[].orderStatus | Enum | 주문 상품 상태. 생성 직후 `PENDING` |
| totalAmount | Integer | 총 주문 금액 |
| createdAt | DateTime | 주문 생성 시각 |
| canceledAt | DateTime \| null | 주문 취소 시각. 생성 직후 `null` |

```json
{
  "success": true,
  "data": {
    "orderId": "9f1c2a7e-4b8d-4e2a-9c11-2d3e4f5a1111",
    "orderNumber": "ORD-20260618-000001",
    "buyerId": "7c2f6e91-2c1b-4a3b-9f99-3f527f7d1234",
    "orderStatus": "PENDING",
    "products": [
      {
        "orderProductId": "72d95cb0-1835-49bf-8f08-2e0f1c4e4aaa",
        "productId": "11111111-1111-1111-1111-111111111111",
        "sellerId": "8f2c6e91-2c1b-4a3b-9f99-3f527f7d5678",
        "productTitleSnapshot": "면접 준비 프롬프트",
        "productTypeSnapshot": "PROMPT",
        "productModelSnapshot": "GPT-4",
        "productAmountSnapshot": 15000,
        "orderStatus": "PENDING"
      }
    ],
    "totalAmount": 15000,
    "createdAt": "2026-06-18T14:30:00",
    "canceledAt": null
  },
  "message": "success"
}
```

#### Error

| Status Code | Error Code | 설명 |
|-------------|------------|------|
| 400 | V001 | 입력값 검증 실패 |
| 401 | A003 | 인증 실패 |
| 403 | A004 | 권한 없음 |
| 503 | SYS002 | 상품 서비스 사용 불가 |

---

### GET /orders - 내 주문 목록 조회

- 인증: 필요
- 필요 헤더: `X-User-Id`
- 주문 상품 row 기준으로 페이지를 반환한다.

#### Query Parameters

| 파라미터 | 타입 | 필수 | 기본값 | 설명 |
|---------|------|:----:|--------|------|
| page | Integer | N | 1 | 1부터 시작하는 페이지 번호 |
| size | Integer | N | 20 | 페이지 크기. 1 이상 100 이하 |
| status | Enum | N | - | `PENDING` / `PAID` / `FAILED` / `CANCELED` / `REFUNDED` |
| from | Date | N | - | 조회 시작일 (`yyyy-MM-dd`) |
| to | Date | N | - | 조회 종료일 (`yyyy-MM-dd`) |

#### Response

`200 OK`

| 필드 | 타입 | 설명 |
|------|------|------|
| data[].orderId | UUID | 주문 ID |
| data[].orderProductId | UUID | 주문 상품 ID |
| data[].productId | UUID | 상품 ID |
| data[].orderStatus | Enum | 주문 상태 |
| data[].isRefundable | Boolean | 환불 가능 여부 |
| data[].productType | String | 상품 유형 |
| data[].title | String | 상품명 |
| data[].model | String \| null | 모델명/분류 |
| data[].rating | Number \| null | 리뷰 평점. 현재 조회 구현에서는 null 가능 |
| data[].paidAt | DateTime \| null | 결제 완료 시각 |
| data[].createdAt | DateTime | 주문 생성 시각 |
| meta.page | Integer | 현재 페이지 번호 |
| meta.size | Integer | 페이지 크기 |
| meta.total | Long | 전체 항목 수 |
| meta.hasNext | Boolean | 다음 페이지 존재 여부 |

```json
{
  "success": true,
  "data": [
    {
      "orderId": "9f1c2a7e-4b8d-4e2a-9c11-2d3e4f5a1111",
      "orderProductId": "72d95cb0-1835-49bf-8f08-2e0f1c4e4aaa",
      "productId": "11111111-1111-1111-1111-111111111111",
      "orderStatus": "PAID",
      "isRefundable": true,
      "productType": "PROMPT",
      "title": "면접 준비 프롬프트",
      "model": "GPT-4",
      "rating": null,
      "paidAt": "2026-06-18T14:35:00",
      "createdAt": "2026-06-18T14:30:00"
    }
  ],
  "message": "success",
  "meta": {
    "page": 1,
    "size": 20,
    "total": 1,
    "hasNext": false
  }
}
```

#### Error

| Status Code | Error Code | 설명 |
|-------------|------------|------|
| 400 | V001 | 잘못된 페이지, 크기, 기간 조건 |
| 401 | A003 | 인증 실패 |
| 403 | A004 | 권한 없음 |

---

### GET /orders/{orderId} - 주문 상세 조회

- 인증: 필요
- 필요 헤더: `X-User-Id`
- 구매자 본인 주문만 조회할 수 있다.

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
| orderStatus | Enum | 주문 상태 |
| products[].orderProductId | UUID | 주문 상품 ID |
| products[].productId | UUID | 상품 ID |
| products[].sellerId | UUID | 판매자 ID |
| products[].productTitleSnapshot | String | 주문 시점 상품명 스냅샷 |
| products[].productTypeSnapshot | String | 주문 시점 상품 유형 스냅샷 |
| products[].productModelSnapshot | String \| null | 주문 시점 모델명/분류 스냅샷 |
| products[].productAmountSnapshot | Integer | 주문 시점 상품 금액 스냅샷 |
| products[].orderStatus | Enum | 주문 상품 상태 |
| products[].isContentAccessible | Boolean | 구매 콘텐츠 열람 가능 여부 |
| products[].isRefundable | Boolean | 환불 가능 여부 |
| products[].downloaded | Boolean | 콘텐츠 열람/다운로드 확정 여부 |
| totalAmount | Integer | 총 주문 금액 |
| totalProductCount | Integer | 총 상품 수 |
| paidAt | DateTime \| null | 결제 완료 시각 |
| canceledAt | DateTime \| null | 취소 시각 |
| refundedAt | DateTime \| null | 환불 시각 |
| createdAt | DateTime | 주문 생성 시각 |
| hasDownloadedProduct | Boolean | 다운로드 확정된 상품 포함 여부 |

```json
{
  "success": true,
  "data": {
    "orderId": "9f1c2a7e-4b8d-4e2a-9c11-2d3e4f5a1111",
    "orderNumber": "ORD-20260618-000001",
    "buyerId": "7c2f6e91-2c1b-4a3b-9f99-3f527f7d1234",
    "orderStatus": "PAID",
    "products": [
      {
        "orderProductId": "72d95cb0-1835-49bf-8f08-2e0f1c4e4aaa",
        "productId": "11111111-1111-1111-1111-111111111111",
        "sellerId": "8f2c6e91-2c1b-4a3b-9f99-3f527f7d5678",
        "productTitleSnapshot": "면접 준비 프롬프트",
        "productTypeSnapshot": "PROMPT",
        "productModelSnapshot": "GPT-4",
        "productAmountSnapshot": 15000,
        "orderStatus": "PAID",
        "isContentAccessible": true,
        "isRefundable": true,
        "downloaded": false
      }
    ],
    "totalAmount": 15000,
    "totalProductCount": 1,
    "paidAt": "2026-06-18T14:35:00",
    "canceledAt": null,
    "refundedAt": null,
    "createdAt": "2026-06-18T14:30:00",
    "hasDownloadedProduct": false
  },
  "message": "success"
}
```

#### Error

| Status Code | Error Code | 설명 |
|-------------|------------|------|
| 401 | A003 | 인증 실패 |
| 403 | A004 | 권한 없음 또는 본인 주문 아님 |
| 404 | O001 | 주문 없음 |

---

### GET /orders/{orderId}/content/{orderProductId} - 구매 콘텐츠 열람

- 인증: 필요
- 필요 헤더: `X-User-Id`
- 주문과 주문 상품이 모두 `PAID` 상태일 때 콘텐츠 원문을 반환한다.
- 이 API는 콘텐츠를 반환하지만 `downloaded` 값을 변경하지 않는다. 다운로드 확정은 별도 `PATCH` API가 담당한다.

#### Path Parameters

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| orderId | UUID | 주문 ID |
| orderProductId | UUID | 주문 상품 ID |

#### Response

`200 OK`

| 필드 | 타입 | 설명 |
|------|------|------|
| orderId | UUID | 주문 ID |
| orderProductId | UUID | 주문 상품 ID |
| orderNumber | String | 주문 번호 |
| productId | UUID | 상품 ID |
| downloaded | Boolean | 현재 다운로드 확정 여부 |
| productTitle | String | 주문 시점 상품명 |
| content | String | 구매 후 열람 가능한 콘텐츠 원문 |

```json
{
  "success": true,
  "data": {
    "orderId": "9f1c2a7e-4b8d-4e2a-9c11-2d3e4f5a1111",
    "orderProductId": "72d95cb0-1835-49bf-8f08-2e0f1c4e4aaa",
    "orderNumber": "ORD-20260618-000001",
    "productId": "11111111-1111-1111-1111-111111111111",
    "downloaded": false,
    "productTitle": "면접 준비 프롬프트",
    "content": "구매 후 확인 가능한 프롬프트 원문"
  },
  "message": "success"
}
```

#### Error

| Status Code | Error Code | 설명 |
|-------------|------------|------|
| 401 | A003 | 인증 실패 |
| 403 | A004, E001 | 권한 없음 또는 콘텐츠 열람 불가 |
| 404 | O001 | 주문 없음 |
| 503 | SYS002 | 상품 서비스 사용 불가 |

---

### PATCH /orders/{orderId}/products/{orderProductId}/download - 주문 상품 다운로드 확정

- 인증: 필요
- 필요 헤더: `X-User-Id`
- 콘텐츠 열람 또는 다운로드 버튼 클릭을 주문 상품에 기록한다.
- 내부적으로 Product Service 콘텐츠 조회가 성공하는지 확인한 뒤 `downloaded`를 `true`로 변경한다.

#### Path Parameters

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| orderId | UUID | 주문 ID |
| orderProductId | UUID | 주문 상품 ID |

#### Response

`200 OK`

| 필드 | 타입 | 설명 |
|------|------|------|
| orderId | UUID | 주문 ID |
| orderProductId | UUID | 주문 상품 ID |
| downloaded | Boolean | 다운로드 확정 여부 |
| isRefundable | Boolean | 다운로드 확정 이후 환불 가능 여부 |

```json
{
  "success": true,
  "data": {
    "orderId": "9f1c2a7e-4b8d-4e2a-9c11-2d3e4f5a1111",
    "orderProductId": "72d95cb0-1835-49bf-8f08-2e0f1c4e4aaa",
    "downloaded": true,
    "isRefundable": false
  },
  "message": "success"
}
```

#### Error

| Status Code | Error Code | 설명 |
|-------------|------------|------|
| 401 | A003 | 인증 실패 |
| 403 | A004, E001 | 권한 없음 또는 콘텐츠 열람 불가 |
| 404 | O001, O012 | 주문 또는 주문 상품 없음 |
| 503 | SYS002 | 상품 서비스 사용 불가 |

---

### GET /orders/payments - 주문 결제 내역 조회

- 인증: 필요
- 필요 헤더: `X-User-Id`
- 결제 내역 row 기준으로 페이지를 반환한다.

#### Query Parameters

| 파라미터 | 타입 | 필수 | 기본값 | 설명 |
|---------|------|:----:|--------|------|
| page | Integer | N | 1 | 1부터 시작하는 페이지 번호 |
| size | Integer | N | 20 | 페이지 크기. 1 이상 100 이하 |
| status | Enum | N | - | DTO에는 존재하지만 현재 결제 내역 조회 구현에서는 사용하지 않음 |
| from | Date | N | - | DTO에는 존재하지만 현재 결제 내역 조회 구현에서는 사용하지 않음 |
| to | Date | N | - | DTO에는 존재하지만 현재 결제 내역 조회 구현에서는 사용하지 않음 |

#### Response

`200 OK`

| 필드 | 타입 | 설명 |
|------|------|------|
| data[].orderId | UUID | 주문 ID |
| data[].paymentId | UUID | 결제 ID |
| data[].paymentStatus | Enum | 주문 상태에서 변환한 결제 상태 |
| data[].isRefundable | Boolean | 환불 가능 여부 |
| data[].productType | String | 대표 상품 유형 |
| data[].title | String | 대표 상품명. 다건 결제는 `첫 상품명 외 N건` 형식 가능 |
| data[].amount | Integer | 결제 금액 |
| data[].paidAt | DateTime \| null | 결제 완료 시각. 없으면 승인 시각 사용 |
| meta.page | Integer | 현재 페이지 번호 |
| meta.size | Integer | 페이지 크기 |
| meta.total | Long | 전체 항목 수 |
| meta.hasNext | Boolean | 다음 페이지 존재 여부 |

```json
{
  "success": true,
  "data": [
    {
      "orderId": "9f1c2a7e-4b8d-4e2a-9c11-2d3e4f5a1111",
      "paymentId": "3f1c2a7e-4b8d-4e2a-9c11-2d3e4f5a9999",
      "paymentStatus": "PAID",
      "isRefundable": true,
      "productType": "PROMPT",
      "title": "면접 준비 프롬프트",
      "amount": 15000,
      "paidAt": "2026-06-18T14:35:00"
    }
  ],
  "message": "success",
  "meta": {
    "page": 1,
    "size": 20,
    "total": 1,
    "hasNext": false
  }
}
```

#### Error

| Status Code | Error Code | 설명 |
|-------------|------------|------|
| 400 | V001 | 잘못된 페이지 또는 크기 |
| 401 | A003 | 인증 실패 |
| 403 | A004 | 권한 없음 |

---

## 장바구니

### GET /api/v1/cart/products - 장바구니 조회

- 인증: 필요
- 필요 헤더: `X-User-Id`
- 장바구니가 없으면 `cartId: null`, 빈 상품 목록, 합계 `0`을 반환한다.

#### Response

`200 OK`

| 필드 | 타입 | 설명 |
|------|------|------|
| cartId | UUID \| null | 장바구니 ID |
| buyerId | UUID | 구매자 ID |
| products[].cartProductId | UUID | 장바구니 상품 ID |
| products[].productId | UUID | 상품 ID |
| products[].productTitle | String | 상품명 |
| products[].productType | String | 상품 유형 |
| products[].productAmount | Integer | 상품 금액 |
| products[].thumbnailUrl | String \| null | 썸네일 URL |
| products[].sellerId | UUID | 판매자 ID |
| products[].sellerNickname | String | 판매자 닉네임 |
| products[].productStatus | String | 상품 판매 상태 |
| products[].addedAt | DateTime | 장바구니 추가 시각 |
| totalAmount | Integer | 상품 금액 합계 |
| totalItemCount | Integer | 장바구니 상품 수 |

```json
{
  "success": true,
  "data": {
    "cartId": "00000000-0000-0000-0000-000000000700",
    "buyerId": "7c2f6e91-2c1b-4a3b-9f99-3f527f7d1234",
    "products": [
      {
        "cartProductId": "00000000-0000-0000-0000-000000000701",
        "productId": "11111111-1111-1111-1111-111111111111",
        "productTitle": "면접 준비 프롬프트",
        "productType": "PROMPT",
        "productAmount": 15000,
        "thumbnailUrl": "https://cdn.prompthub.com/products/prompt-thumb.png",
        "sellerId": "8f2c6e91-2c1b-4a3b-9f99-3f527f7d5678",
        "sellerNickname": "prompt-seller",
        "productStatus": "ON_SALE",
        "addedAt": "2026-06-22T10:00:00"
      }
    ],
    "totalAmount": 15000,
    "totalItemCount": 1
  },
  "message": "success"
}
```

#### Error

| Status Code | Error Code | 설명 |
|-------------|------------|------|
| 401 | A003 | 인증 실패 |
| 403 | A004 | 권한 없음 |
| 503 | SYS002 | 상품 서비스 사용 불가 |

---

### POST /api/v1/cart/products - 장바구니 상품 추가

- 인증: 필요
- 필요 헤더: `X-User-Id`
- Product Service에서 장바구니 스냅샷을 조회하고 `ON_SALE` 상태만 추가한다.

#### Request

| 필드 | 타입 | 필수 | 설명 |
|------|------|:----:|------|
| productId | UUID | O | 장바구니에 추가할 상품 ID |

```json
{
  "productId": "11111111-1111-1111-1111-111111111111"
}
```

#### Response

`200 OK`

| 필드 | 타입 | 설명 |
|------|------|------|
| cartProductId | UUID | 장바구니 상품 ID |
| productId | UUID | 상품 ID |
| productTitle | String | 상품명 |
| productType | String | 상품 유형 |
| productAmount | Integer | 상품 금액 |
| thumbnailUrl | String \| null | 썸네일 URL |
| sellerId | UUID | 판매자 ID |
| sellerNickname | String | 판매자 닉네임 |
| productStatus | String | 상품 판매 상태 |
| addedAt | DateTime | 장바구니 추가 시각 |

```json
{
  "success": true,
  "data": {
    "cartProductId": "00000000-0000-0000-0000-000000000701",
    "productId": "11111111-1111-1111-1111-111111111111",
    "productTitle": "면접 준비 프롬프트",
    "productType": "PROMPT",
    "productAmount": 15000,
    "thumbnailUrl": "https://cdn.prompthub.com/products/prompt-thumb.png",
    "sellerId": "8f2c6e91-2c1b-4a3b-9f99-3f527f7d5678",
    "sellerNickname": "prompt-seller",
    "productStatus": "ON_SALE",
    "addedAt": "2026-06-22T10:00:00"
  },
  "message": "success"
}
```

#### Error

| Status Code | Error Code | 설명 |
|-------------|------------|------|
| 400 | V001, O003 | 입력값 검증 실패 또는 판매 중이 아닌 상품 |
| 401 | A003 | 인증 실패 |
| 403 | A004 | 권한 없음 |
| 409 | C001 | 이미 장바구니에 담긴 상품 |
| 503 | SYS002 | 상품 서비스 사용 불가 |

---

### DELETE /api/v1/cart/products/{cartProductId} - 장바구니 상품 삭제

- 인증: 필요
- 필요 헤더: `X-User-Id`
- 본인 장바구니 상품만 삭제할 수 있다.

#### Path Parameters

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| cartProductId | UUID | 장바구니 상품 ID |

#### Response

`200 OK`

```json
{
  "success": true,
  "data": null,
  "message": "success"
}
```

#### Error

| Status Code | Error Code | 설명 |
|-------------|------------|------|
| 400 | V001 | 잘못된 경로 변수 |
| 401 | A003 | 인증 실패 |
| 403 | A004, C003 | 권한 없음 또는 본인 장바구니 항목 아님 |
| 404 | O006 | 장바구니 상품 없음 |

---

## 관리자

### GET /api/v1/admin/orders - 전체 주문 관리 목록 조회

- 인증: 필요
- 필요 헤더: `X-User-Role: ADMIN`

#### Query Parameters

| 파라미터 | 타입 | 필수 | 기본값 | 설명 |
|---------|------|:----:|--------|------|
| orderStatus | String | N | ALL | `ALL` / `PENDING` / `PAID` / `FAILED` / `CANCELED` / `REFUNDED` |
| page | Integer | N | 1 | 1부터 시작하는 페이지 번호 |
| size | Integer | N | 20 | 페이지 크기. 1 이상 100 이하 |

#### Response

`200 OK`

| 필드 | 타입 | 설명 |
|------|------|------|
| data[].orderId | UUID | 주문 ID |
| data[].sellerNickname | String | 판매자 닉네임. 조회 실패 시 `알 수 없음` |
| data[].productTitle | String | 대표 상품명 |
| data[].totalOrderCount | Integer | 주문 상품 수 |
| data[].totalOrderAmount | Integer | 총 주문 금액 |
| data[].orderStatus | Enum | 주문 상태 |
| data[].createdAt | DateTime | 주문 생성 시각 |
| meta.page | Integer | 현재 페이지 번호 |
| meta.size | Integer | 페이지 크기 |
| meta.total | Long | 전체 항목 수 |
| meta.hasNext | Boolean | 다음 페이지 존재 여부 |

```json
{
  "success": true,
  "data": [
    {
      "orderId": "9f1c2a7e-4b8d-4e2a-9c11-2d3e4f5a1111",
      "sellerNickname": "prompt-seller",
      "productTitle": "면접 준비 프롬프트",
      "totalOrderCount": 1,
      "totalOrderAmount": 15000,
      "orderStatus": "PAID",
      "createdAt": "2026-06-18T14:30:00"
    }
  ],
  "message": "success",
  "meta": {
    "page": 1,
    "size": 20,
    "total": 1,
    "hasNext": false
  }
}
```

#### Error

| Status Code | Error Code | 설명 |
|-------------|------------|------|
| 400 | V001 | 잘못된 조회 조건 |
| 401 | A003 | 인증 실패 |
| 403 | A004 | 관리자 권한 없음 |

---

### GET /api/v1/admin/orders/month - 이번 달 실제 거래액 조회

- 인증: 필요
- 필요 헤더: `X-User-Role: ADMIN`

#### Response

`200 OK`

| 필드 | 타입 | 설명 |
|------|------|------|
| monthlyTransactionAmount | Long | 이번 달 승인 거래액 합계 |

```json
{
  "success": true,
  "data": {
    "monthlyTransactionAmount": 1250000
  },
  "message": "success"
}
```

#### Error

| Status Code | Error Code | 설명 |
|-------------|------------|------|
| 401 | A003 | 인증 실패 |
| 403 | A004 | 관리자 권한 없음 |

---

### GET /api/v1/admin/orders/weekend - 최근 7일 거래량 조회

- 인증: 필요
- 필요 헤더: `X-User-Role: ADMIN`
- 현재 구현 경로는 `/weekend`이다. 의미상 `/week`에 가깝지만, 문서는 구현 경로를 우선한다.

#### Response

`200 OK`

| 필드 | 타입 | 설명 |
|------|------|------|
| totalTransactionCount | Long | 최근 7일 결제 승인 완료 주문 수 |
| totalTransactionAmount | Long | 최근 7일 실제 거래액 |
| period.startDate | Date | 조회 시작일 |
| period.endDate | Date | 조회 종료일 |
| dailyTransactions[].date | Date | 거래 일자 |
| dailyTransactions[].transactionCount | Long | 해당 일 결제 승인 완료 주문 수 |
| dailyTransactions[].transactionAmount | Long | 해당 일 실제 거래액 |

```json
{
  "success": true,
  "data": {
    "totalTransactionCount": 42,
    "totalTransactionAmount": 980000,
    "period": {
      "startDate": "2026-06-18",
      "endDate": "2026-06-24"
    },
    "dailyTransactions": [
      {
        "date": "2026-06-18",
        "transactionCount": 5,
        "transactionAmount": 120000
      }
    ]
  },
  "message": "success"
}
```

#### Error

| Status Code | Error Code | 설명 |
|-------------|------------|------|
| 401 | A003 | 인증 실패 |
| 403 | A004 | 관리자 권한 없음 |

---

## 미구현 또는 별도 서비스 소유 기능

- 리뷰 생성/수정 API는 현재 `OrderController`에 구현되어 있지 않다. 리뷰 작성 가능 여부 검증용 타입과 리포지토리 메서드는 일부 남아 있지만, 공개 엔드포인트로 문서화하지 않는다.
- 정산 대상 내부 조회 API는 현재 `order-service` 컨트롤러에 구현되어 있지 않다. 정산 연동은 Kafka 주문 이벤트 또는 추후 내부 API 설계 문서에서 별도로 다룬다.
