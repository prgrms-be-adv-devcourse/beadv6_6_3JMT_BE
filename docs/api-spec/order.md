# Order Service API

**Base:** `http://localhost:{order-service-port}/api/v1`

> ⚠ `api/v1`은 세미 프로젝트 완성 스냅샷(`v1.0.0` 태그) 기준 경로다. 최종 프로젝트에서
> `api/v2`로 전환 예정이며 별도 이슈로 진행한다(`docs/adr/config-management.md` §10).

## 공통 사항

- 외부 인증과 토큰 검증은 API Gateway가 담당한다.
- Order Service는 Gateway가 주입한 trusted header를 읽는다.
- 구매자 API는 `X-User-Id`가 필요하다.
- 관리자 API는 `X-User-Role: ADMIN`이 필요하다.
- 응답 envelope는 `common-module`의 `ApiResult` 또는 `PageResponse` 형식을 따른다.

## v2 다건 부분 환불

아래 API는 v2 전환 대상이며, 기존 v1 API 경로를 대체하는 새로운 환불 API는 `POST /api/v2/orders/{orderId}/refund`이다.

### POST /api/v2/orders/{orderId}/refund - 주문 상품 다건 부분 환불 요청

- 인증: 필요
- 필요 헤더: Gateway가 주입한 `X-User-Id`, `X-User-Role: BUYER`
- 클라이언트는 환불 금액을 보내지 않는다. Order Service는 선택한 주문 상품의 금액 스냅샷 합계를 한 번만 환불 요청한다.
- `order_product_ids`는 비어 있을 수 없다. 선택 상품은 모두 주문에 속하고, 결제와 구매자가 주문에 일치해야 하며, 요청은 전부 성공하거나 전부 거절된다.
- 정상 접수는 `202 Accepted`를 반환한다. Payment Service의 최종 결과는 비동기로 반영된다.

#### Path Parameters

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| orderId | UUID | 부분 환불할 주문 ID |

#### Request

| 필드 | 타입 | 필수 | 설명 |
|------|------|:----:|------|
| payment_id | UUID | O | 주문에 연결된 결제 ID |
| order_product_ids | UUID[] | O | 환불할 주문 상품 ID 목록. 비어 있을 수 없음 |

```json
{
  "payment_id": "3f1c2a7e-4b8d-4e2a-9c11-2d3e4f5a9999",
  "order_product_ids": [
    "72d95cb0-1835-49bf-8f08-2e0f1c4e4aaa",
    "82d95cb0-1835-49bf-8f08-2e0f1c4e4bbb"
  ]
}
```

#### Response

`202 Accepted`

| 필드 | 타입 | 설명 |
|------|------|------|
| refundRequestId | UUID | 비동기 환불 요청 ID |
| orderId | UUID | 주문 ID |
| paymentId | UUID | 결제 ID |
| status | Enum | 접수 직후 `REQUESTED` |

```json
{
  "success": true,
  "data": {
    "refundRequestId": "4f1c2a7e-4b8d-4e2a-9c11-2d3e4f5a2222",
    "orderId": "9f1c2a7e-4b8d-4e2a-9c11-2d3e4f5a1111",
    "paymentId": "3f1c2a7e-4b8d-4e2a-9c11-2d3e4f5a9999",
    "status": "REQUESTED"
  },
  "message": "success"
}
```

#### Error

| Status Code | 설명 |
|-------------|------|
| 400 | 요청 상품 목록이 비었거나 잘못된 요청 |
| 401 | 인증 실패 |
| 403 | 구매자 본인 주문이 아님 |
| 404 | 주문, 결제 또는 주문 상품 없음 |
| 409 | 환불 불가 상태, 중복 또는 진행 중인 환불 |

---

## 주문

### POST /orders - 주문 생성

- 인증: 필요
- 필요 헤더: `X-User-Id`
- 요청 상품 목록으로 `PENDING` 주문과 주문 상품을 생성한다.
- 주문 생성 트랜잭션에서 요청 상품만 구매자 장바구니에서 제거한다.
- DB 커밋 이후 Redis Sorted Set `order:expiration`에 만료 후보를 등록한다.
- 만료 기준은 `createdAt + 20분`이며, 결제 완료 전까지 `PENDING` 상태로 유지된다.

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

### POST /orders/{orderId}/payment-ready - 결제 승인 전 주문 검증

- 인증: 필요
- 필요 헤더: `X-User-Id`
- Payment Service가 PG 승인 요청 전에 호출할 수 있는 주문 검증 API이다.
- Order Service는 주문 존재 여부, 구매자 일치, 주문 상태, 만료 시간, 결제 금액을 DB 기준으로 검증한다.
- `payment-service` 구현은 이 문서 범위에서 수정하지 않는다.

#### Path Parameters

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| orderId | UUID | 결제하려는 주문 ID |

#### Request

| 필드 | 타입 | 필수 | 설명 |
|------|------|:----:|------|
| amount | Integer | O | PG 승인 요청 예정 금액 |

```json
{
  "amount": 30000
}
```

#### Response

`200 OK`

| 필드 | 타입 | 설명 |
|------|------|------|
| payable | Boolean | 결제 가능 여부. 성공 응답에서는 `true` |
| orderId | UUID | 주문 ID |
| buyerId | UUID | 구매자 ID |
| totalAmount | Integer | 주문 총 금액 |
| expiresAt | DateTime | 결제 가능 만료 시각 |

```json
{
  "success": true,
  "data": {
    "payable": true,
    "orderId": "9f1c2a7e-4b8d-4e2a-9c11-2d3e4f5a1111",
    "buyerId": "7c2f6e91-2c1b-4a3b-9f99-3f527f7d1234",
    "totalAmount": 30000,
    "expiresAt": "2026-06-18T14:50:00"
  },
  "message": "success"
}
```

#### Error

| Status Code | Error Code | 설명 |
|-------------|------------|------|
| 400 | V001 | 입력값 검증 실패 |
| 400 | O014 | 주문 금액과 결제 요청 금액 불일치 |
| 401 | A003 | 인증 실패 |
| 403 | A004 | 구매자 불일치 |
| 404 | O001 | 주문 없음 |
| 409 | O010 | 주문 상태가 `PENDING`이 아님 |
| 409 | O015 | 결제 가능 시간이 만료됨 |

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

### GET /cart/products - 장바구니 조회

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

### POST /cart/products - 장바구니 상품 추가

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

### DELETE /cart/products/{cartProductId} - 장바구니 상품 삭제

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

### GET /admin/orders - 전체 주문 관리 목록 조회

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

### GET /admin/orders/month - 이번 달 실제 거래액 조회

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

### GET /admin/orders/weekend - 최근 7일 거래량 조회

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

## Kafka 이벤트

## 주문 상품 부분 환불 요청

`POST /api/v1/orders/{orderId}/refunds`

- 인증: Gateway가 전달한 `X-User-Id` 필요
- 성공: `202 Accepted`
- 조건: 요청한 모든 주문 상품이 해당 주문 소속이며 `PAID`, 미다운로드, 결제 금액이 0원보다 커야 한다.
- `reason`은 선택값이며 앞뒤 공백 제거 후 최대 500자이다.
- 접수 성공 시 상품 상태는 `REFUND_REQUESTED`, 요청 상태는 `REQUESTED`가 되며 `REFUND_REQUESTED` outbox 이벤트를 생성한다.

```json
{
  "orderProductIds": ["UUID", "UUID"],
  "reason": "고객 변심"
}
```

응답의 `data`는 `refundId`, `orderId`, `orderProductIds`, `totalRefundAmount`, `status`, `requestedAt`을 포함한다.

오류 코드는 `O001`, `O012`, `O016`, `O018`, `O019`, `A004`를 사용한다.

---

### 공통 사항

- Order Service Kafka consumer group은 `order-service`이다.
- Payment 이벤트는 `payment.approved`, `payment.refunded` 토픽을 각각 소비한다.
- Product 이벤트는 `product-events` 토픽을 소비한다.
- Order 상태 변경 이벤트는 outbox에 저장한 뒤 `order-events` 토픽으로 발행한다.
- Outbox relay 기본 설정은 `enabled: true`, `fixed-delay-ms: 5000`, `batch-size: 100`, `max-retry-count: 3`이다.
- Kafka 발행 key는 `aggregateId` 문자열이다.
- 알 수 없는 `eventType`은 경고 로그를 남기고 acknowledge한다.
- 주문 만료 예약은 Kafka timeout/outbox consumer가 아니라 Redis Sorted Set과 Order Service Worker가 담당한다.
- Kafka는 결제/주문 상태 변경 사실 전파에만 사용한다.

### 이벤트 소비 매트릭스

| Topic | Event Type | 발행 주체 | Order 처리 내용 |
|-------|------------|-----------|----------------|
| `payment.approved` | `payment.approved` | Payment Service | 주문/주문상품을 `PAID`로 변경, `OrderPayment` 저장, Redis 만료 대상 best-effort 제거, `ORDER_PAID` outbox 생성 |
| `payment.refunded` | `payment.refunded` | Payment Service | 전체 환불 완료 주문/주문상품을 `REFUNDED`로 변경, `ORDER_REFUND` outbox 생성 |
| `product-events` | `PRODUCT_STOPPED` | Product Service | 현재 구현은 상품 판매 중지 이벤트 수신 로그 기록 |
| `product-events` | `PRODUCT_DELETED` | Product Service | 현재 구현은 상품 삭제 이벤트 수신 로그 기록 |
| `product-events` | `PRODUCT_PRICE_CHANGED` | Product Service | 현재 구현은 상품 가격 변경 이벤트 수신 로그 기록 |

### payment.approved - 결제 승인 이벤트 소비

- Topic: `payment.approved`
- Consumer group: `order-service`
- 처리 조건: 주문이 `PENDING`이고 승인 금액이 주문 총액과 일치해야 한다.
- 멱등 처리: 주문이 이미 `PAID`이고 동일 `paymentId`의 결제 내역이 있으면 중복 이벤트로 보고 처리하지 않는다.
- 후속 이벤트: `ORDER_PAID` outbox 이벤트를 생성한다.

```json
{
  "eventType": "payment.approved",
  "paymentId": "550e8400-e29b-41d4-a716-446655440000",
  "orderId": "660e8400-e29b-41d4-a716-446655440001",
  "userId": "770e8400-e29b-41d4-a716-446655440002",
  "amount": 15000,
  "approvedAt": "2026-06-18T14:35:00Z"
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|:----:|------|
| eventType | String | O | `payment.approved` 고정 |
| paymentId | UUID | O | Payment Service 결제 ID |
| orderId | UUID | O | 결제 대상 주문 ID |
| userId | UUID | O | 결제 사용자 ID. Order Service에서는 구매자 ID로 사용 |
| amount | Integer | O | 승인 금액 |
| approvedAt | OffsetDateTime | O | 결제 승인 시각 |

### payment.refunded - 환불 완료 이벤트 소비

- Topic: `payment.refunded`
- Consumer group: `order-service`
- 처리 조건: 주문이 `PAID` 상태여야 한다.
- 멱등 처리: 주문이 이미 `REFUNDED`이면 중복 이벤트로 보고 처리하지 않는다.
- 기존 `payment.refunded` 처리는 전체 환불 호환 경로로 유지한다. 다건 환불 결과는 `PAYMENT_REFUND_COMPLETED` 또는 `PAYMENT_REFUND_FAILED` 이벤트로 처리한다.
- 후속 이벤트: `ORDER_REFUND` outbox 이벤트를 생성한다.

```json
{
  "eventType": "payment.refunded",
  "paymentId": "550e8400-e29b-41d4-a716-446655440000",
  "orderId": "660e8400-e29b-41d4-a716-446655440001",
  "userId": "770e8400-e29b-41d4-a716-446655440002",
  "amount": 15000,
  "refundedAt": "2026-06-18T15:10:00Z"
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|:----:|------|
| eventType | String | O | `payment.refunded` 고정 |
| paymentId | UUID | O | Payment Service 결제 ID |
| orderId | UUID | O | 환불 대상 주문 ID |
| userId | UUID | O | 결제 사용자 ID. Order Service에서는 구매자 ID로 사용 |
| amount | Integer | O | 환불 금액. 전체 환불 기준으로 원 결제 금액과 동일 |
| refundedAt | OffsetDateTime | O | 환불 완료 시각 |

### product-events - 상품 이벤트 소비

- Topic: `product-events`
- Consumer group: `order-service`
- 현재 구현은 주문 상태나 스냅샷을 변경하지 않고 수신 로그를 남긴다.

#### PRODUCT_STOPPED

```json
{
  "eventType": "PRODUCT_STOPPED",
  "productId": "11111111-1111-1111-1111-111111111111",
  "occurredAt": "2026-06-18T14:20:00"
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|:----:|------|
| eventType | String | O | `PRODUCT_STOPPED` 고정 |
| productId | UUID | O | 판매 중지된 상품 ID |
| occurredAt | LocalDateTime | O | 이벤트 발생 시각 |

#### PRODUCT_DELETED

```json
{
  "eventType": "PRODUCT_DELETED",
  "productId": "11111111-1111-1111-1111-111111111111",
  "occurredAt": "2026-06-18T14:25:00"
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|:----:|------|
| eventType | String | O | `PRODUCT_DELETED` 고정 |
| productId | UUID | O | 삭제된 상품 ID |
| occurredAt | LocalDateTime | O | 이벤트 발생 시각 |

#### PRODUCT_PRICE_CHANGED

```json
{
  "eventType": "PRODUCT_PRICE_CHANGED",
  "productId": "11111111-1111-1111-1111-111111111111",
  "previousPrice": 15000,
  "changedPrice": 17000,
  "occurredAt": "2026-06-18T14:30:00"
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|:----:|------|
| eventType | String | O | `PRODUCT_PRICE_CHANGED` 고정 |
| productId | UUID | O | 가격이 변경된 상품 ID |
| previousPrice | Integer | O | 변경 전 가격 |
| changedPrice | Integer | O | 변경 후 가격 |
| occurredAt | LocalDateTime | O | 이벤트 발생 시각 |

### order-events - 주문 이벤트 발행

- Topic: `order-events`
- 발행 주체: Order Service
- 발행 방식: DB outbox에 `PENDING` 이벤트를 저장한 뒤 relay가 Kafka로 발행한다.
- 발행 성공 시 outbox 상태는 `PUBLISHED`로 변경된다.
- 발행 실패 시 `retryCount`를 증가시키고, `max-retry-count` 3회를 초과하면 `FAILED`로 변경된다.
- 전달 보장은 at-least-once 기준이며, 소비자는 `eventId` 기준 멱등 처리를 해야 한다.

#### Envelope

| 필드 | 타입 | 필수 | 설명 |
|------|------|:----:|------|
| eventId | UUID | O | 이벤트 멱등키 |
| eventType | String | O | `ORDER_PAID` 또는 `ORDER_REFUND` |
| version | Integer | O | 이벤트 버전. 현재 `1` |
| occurredAt | LocalDateTime | O | 주문 상태 변경 발생 시각 |
| aggregateId | UUID | O | 주문 ID |
| payload | Object | O | 이벤트별 payload |

#### ORDER_PAID

- 발행 시점: `payment.approved` 처리로 주문 결제가 완료된 뒤
- 주요 소비자: Product Service, Settlement Service 등 주문 결제 완료 사실이 필요한 서비스

```json
{
  "eventId": "11111111-aaaa-4aaa-8aaa-111111111111",
  "eventType": "ORDER_PAID",
  "version": 1,
  "occurredAt": "2026-06-18T14:35:00",
  "aggregateId": "660e8400-e29b-41d4-a716-446655440001",
  "payload": {
    "orderId": "660e8400-e29b-41d4-a716-446655440001",
    "buyerId": "770e8400-e29b-41d4-a716-446655440002",
    "totalOrderAmount": 15000,
    "totalProductCount": 1,
    "paidAt": "2026-06-18T14:35:00",
    "products": [
      {
        "orderProductId": "72d95cb0-1835-49bf-8f08-2e0f1c4e4aaa",
        "productId": "11111111-1111-1111-1111-111111111111",
        "sellerId": "8f2c6e91-2c1b-4a3b-9f99-3f527f7d5678",
        "productTitle": "면접 준비 프롬프트",
        "productType": "PROMPT",
        "productAmount": 15000
      }
    ]
  }
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| payload.orderId | UUID | 주문 ID |
| payload.buyerId | UUID | 구매자 ID |
| payload.totalOrderAmount | Integer | 총 주문 금액 |
| payload.totalProductCount | Integer | 주문 상품 수 |
| payload.paidAt | LocalDateTime | 결제 완료 시각 |
| payload.products[].orderProductId | UUID | 주문 상품 ID |
| payload.products[].productId | UUID | 상품 ID |
| payload.products[].sellerId | UUID | 판매자 ID |
| payload.products[].productTitle | String | 주문 시점 상품명 |
| payload.products[].productType | String | 주문 시점 상품 유형 |
| payload.products[].productAmount | Integer | 주문 시점 상품 금액 |

#### ORDER_REFUND

- 발행 시점: `payment.refunded` 처리로 주문 환불이 완료된 뒤
- 주요 소비자: Product Service, Settlement Service 등 주문 환불 완료 사실이 필요한 서비스

```json
{
  "eventId": "22222222-bbbb-4bbb-8bbb-222222222222",
  "eventType": "ORDER_REFUND",
  "version": 1,
  "occurredAt": "2026-06-18T15:10:00",
  "aggregateId": "660e8400-e29b-41d4-a716-446655440001",
  "payload": {
    "orderId": "660e8400-e29b-41d4-a716-446655440001",
    "paymentId": "550e8400-e29b-41d4-a716-446655440000",
    "buyerId": "770e8400-e29b-41d4-a716-446655440002",
    "totalRefundAmount": 15000,
    "totalProductCount": 1,
    "refundedAt": "2026-06-18T15:10:00",
    "products": [
      {
        "orderProductId": "72d95cb0-1835-49bf-8f08-2e0f1c4e4aaa",
        "productId": "11111111-1111-1111-1111-111111111111",
        "sellerId": "8f2c6e91-2c1b-4a3b-9f99-3f527f7d5678",
        "productTitle": "면접 준비 프롬프트",
        "productType": "PROMPT",
        "refundAmount": 15000
      }
    ]
  }
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| payload.orderId | UUID | 주문 ID |
| payload.paymentId | UUID | Payment Service 결제 ID |
| payload.buyerId | UUID | 구매자 ID |
| payload.totalRefundAmount | Integer | 총 환불 금액 |
| payload.totalProductCount | Integer | 주문 상품 수 |
| payload.refundedAt | LocalDateTime | 환불 완료 시각 |
| payload.products[].orderProductId | UUID | 주문 상품 ID |
| payload.products[].productId | UUID | 상품 ID |
| payload.products[].sellerId | UUID | 판매자 ID |
| payload.products[].productTitle | String | 주문 시점 상품명 |
| payload.products[].productType | String | 주문 시점 상품 유형 |
| payload.products[].refundAmount | Integer | 주문 상품 환불 금액 |

---

## 내부 서비스 통신 (gRPC)

> 외부 노출 없음. 서비스 간 내부 통신 전용.

### 공통 사항

- Order Service는 Product Service와 Seller Service를 gRPC blocking stub으로 호출한다.
- 기본 deadline은 Product, Seller 모두 `2000ms`이다.
- Product gRPC 호출 실패는 Order Service에서 `SYS002` 상품 서비스 사용 불가 오류로 변환한다.
- Seller gRPC 호출 실패는 빈 결과로 대체하고 관리자 주문 목록에서 판매자 닉네임을 `알 수 없음`으로 표시할 수 있다.

### ProductInternalService

- 호출 방향: Order Service -> Product Service
- Proto package: `prompthub.product.v1`
- Java package: `com.prompthub.grpc.product.v1`
- Service: `ProductInternalService`

#### GetOrderSnapshots

- 호출 시점: `POST /orders` 주문 생성 시 상품 주문 스냅샷이 필요할 때
- 목적: 주문 시점 상품명, 유형, 모델, 가격, 판매자 ID를 Order Service에 저장한다.

**Request: `GetOrderSnapshotsRequest`**

| 필드 | 타입 | 필수 | 설명 |
|------|------|:----:|------|
| product_ids | repeated string(UUID) | O | 주문할 상품 ID 목록 |

**Response: `GetOrderSnapshotsResponse`**

| 필드 | 타입 | 설명 |
|------|------|------|
| products | repeated ProductOrderSnapshot | 상품 주문 스냅샷 목록 |

**ProductOrderSnapshot**

| 필드 | 타입 | 설명 |
|------|------|------|
| product_id | string(UUID) | 상품 ID |
| seller_id | string(UUID) | 판매자 ID |
| title | string | 상품명 |
| product_type | string | 상품 유형 |
| amount | int32 | 상품 금액 |
| model | string | 모델명/분류. 값이 없으면 빈 문자열 |

#### GetCartSnapshots

- 호출 시점: `GET /cart/products`, `POST /cart/products`에서 장바구니 표시용 상품 정보가 필요할 때
- 목적: 장바구니 화면에 표시할 상품명, 유형, 금액, 썸네일, 판매자 정보를 조회한다.

**Request: `GetCartSnapshotsRequest`**

| 필드 | 타입 | 필수 | 설명 |
|------|------|:----:|------|
| product_ids | repeated string(UUID) | O | 장바구니 상품 ID 목록 |

**Response: `GetCartSnapshotsResponse`**

| 필드 | 타입 | 설명 |
|------|------|------|
| products | repeated ProductCartSnapshotMessage | 장바구니 상품 스냅샷 목록 |

**ProductCartSnapshotMessage**

| 필드 | 타입 | 설명 |
|------|------|------|
| product_id | string(UUID) | 상품 ID |
| seller_id | string(UUID) | 판매자 ID |
| seller_nickname | string | 판매자 닉네임 |
| title | string | 상품명 |
| product_type | string | 상품 유형 |
| amount | int32 | 상품 금액 |
| thumbnail_url | string | 썸네일 URL. 값이 없으면 빈 문자열 |

#### GetProductContent

- 호출 시점: `GET /orders/{orderId}/content/{orderProductId}`, `PATCH /orders/{orderId}/products/{orderProductId}/download`
- 목적: 결제 완료된 상품의 콘텐츠 원문을 조회하거나 다운로드 확정 전 접근 가능성을 확인한다.

**Request: `GetProductContentRequest`**

| 필드 | 타입 | 필수 | 설명 |
|------|------|:----:|------|
| product_id | string(UUID) | O | 콘텐츠를 조회할 상품 ID |

**Response: `GetProductContentResponse`**

| 필드 | 타입 | 설명 |
|------|------|------|
| product_id | string(UUID) | 상품 ID |
| content | string | 구매 후 열람 가능한 콘텐츠 원문 |

### SellerQueryService

- 호출 방향: Order Service -> Seller Service
- Proto package: `product.seller`
- Java package: `com.prompthub.order.grpc.seller`
- Service: `SellerQueryService`

#### FindSellers

- 호출 시점: `GET /admin/orders` 전체 주문 관리 목록 조회 시 판매자 닉네임이 필요할 때
- 목적: 주문 목록의 판매자 ID를 판매자 표시 정보로 변환한다.
- 조회 실패 또는 누락된 판매자 닉네임은 관리자 주문 목록에서 `알 수 없음`으로 표시할 수 있다.

**Request: `SellerBatchQueryRequest`**

| 필드 | 타입 | 필수 | 설명 |
|------|------|:----:|------|
| seller_ids | repeated string(UUID) | O | 조회할 판매자 ID 목록 |

**Response: `SellerBatchQueryResponse`**

| 필드 | 타입 | 설명 |
|------|------|------|
| sellers | repeated SellerInfo | 판매자 정보 목록 |

**SellerInfo**

| 필드 | 타입 | 설명 |
|------|------|------|
| seller_id | string(UUID) | 판매자 ID |
| seller_name | string | 판매자 닉네임 |
| profile_image_url | string | 프로필 이미지 URL. 값이 없으면 빈 문자열 |
| status | string | 판매자 상태 |

---

## 미구현 또는 별도 서비스 소유 기능

- 리뷰 생성/수정 API는 현재 `OrderController`에 구현되어 있지 않다. 리뷰 작성 가능 여부 검증용 타입과 리포지토리 메서드는 일부 남아 있지만, 공개 엔드포인트로 문서화하지 않는다.
- 정산 대상 내부 조회 API는 현재 `order-service` 컨트롤러에 구현되어 있지 않다. 정산 연동은 Kafka 주문 이벤트 또는 추후 내부 API 설계 문서에서 별도로 다룬다.
