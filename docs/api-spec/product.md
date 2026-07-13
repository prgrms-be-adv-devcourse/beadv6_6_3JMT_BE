# Product Service API

**Base:** `http://localhost:xxxx/api/v2`

> 최종 프로젝트 전환에 따라 product-service 외부 API는 `/api/v2`로 서빙한다(#273).
> 게이트웨이는 경로를 rewrite하지 않으므로(ADR-0007) 각 서비스가 해당 버전 경로를 직접 서빙한다.
> 내부 통신(`/internal/**`)은 버전 없이 유지한다.

## 공통 사항

- 인증이 필요한 엔드포인트는 `Authorization: Bearer {accessToken}` 헤더 필요
- 토큰 검증은 API Gateway에서 수행. 각 서비스는 헤더(`X-User-Id`, `X-User-Role`)만 읽음

---

## 상품 (공개)

### GET /products — 상품 목록 조회

- UC: UC-PRODUCT-03
- 인증: 불필요
- ON_SALE 상태 상품만 노출

#### Query Parameters

| 파라미터 | 타입 | 필수 | 기본값 | 설명 |
|---------|------|------|--------|------|
| q | string | N | `""` | 제목/설명 검색 |
| productType | string | N | `"all"` | `all\|PROMPT\|NOTION\|PPT\|EXCEL` |
| sort | string | N | `"popular"` | `popular\|rating\|price-asc\|price-desc` |
| page | number | N | `1` | 페이지 번호 |
| size | number | N | `20` | 페이지당 항목 수 |

#### Response

**200 OK**

```json
{
  "success": true,
  "data": [
    {
      "id": "uuid",
      "title": "사진 같은 제품 목업 생성기",
      "productType": "PROMPT",
      "model": "Midjourney v6",
      "amount": 5900,
      "originalAmount": null,
      "rating": 4.9,
      "salesCount": 1240,
      "seller": "비주얼랩",
      "sellerId": "uuid",
      "badge": "신규",
      "desc": "상품 설명",
      "thumbnail_url": null,
      "tags": ["이미지생성", "목업"],
      "createdAt": "2026-05-01T00:00:00.000Z",
      "updatedAt": "2026-06-01T00:00:00.000Z"
    }
  ],
  "message": "success",
  "meta": {
    "page": 1,
    "size": 20,
    "total": 12,
    "hasNext": false
  }
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| id | string | 상품 ID |
| title | string | 상품명 |
| productType | string | 상품 유형 (`PROMPT` \| `NOTION` \| `PPT` \| `EXCEL`) |
| model | string | 대상 AI 모델 |
| amount | integer | 현재 가격 |
| originalAmount | integer \| null | 할인 전 원래 가격 (할인 없으면 null) |
| rating | number | 평균 별점 |
| salesCount | integer | 누적 판매 수 |
| seller | string | 판매자 이름 |
| sellerId | string | 판매자 ID |
| badge | string | 뱃지 (`신규` 등) |
| desc | string | 상품 설명 |
| thumbnail_url | string \| null | 썸네일 이미지 URL |
| tags | string[] | 판매자 지정 태그 목록 |
| createdAt | string | 생성일시 (ISO 8601) |
| updatedAt | string | 수정일시 (ISO 8601) |
| meta.page | integer | 현재 페이지 번호 |
| meta.size | integer | 페이지당 항목 수 |
| meta.total | integer | 전체 항목 수 |
| meta.hasNext | boolean | 다음 페이지 존재 여부 |

---

### GET /products/{productId} — 상품 상세 조회

- 인증: 불필요

#### Path Parameters

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| productId | UUID | 상품 ID |

#### Response

**200 OK**

```json
{
  "success": true,
  "data": {
    "id": "uuid",
    "title": "사진 같은 제품 목업 생성기",
    "productType": "PROMPT",
    "model": "Midjourney v6",
    "amount": 5900,
    "rating": 4.9,
    "salesCount": 1240,
    "seller": "비주얼랩",
    "sellerId": "uuid",
    "sellerProfileImageUrl": "https://...",
    "sellerProductCount": 12,
    "badge": "신규",
    "desc": "상품 설명",
    "thumbnail_url": null,
    "content": "[상품명]\n\n전체 내용은 구매 후 확인...",
    "tags": ["이미지생성", "목업"],
    "versions": [
      { "ver": "v1.3", "date": "2026-06-01", "note": "조명 프리셋 3종 추가" },
      { "ver": "v1.2", "date": "2026-05-10", "note": "배경 제거 옵션 개선" }
    ],
    "features": ["고해상도 출력 지원", "상업적 이용 가능", "버전 업데이트 무료 제공"],
    "createdAt": "2026-05-01T00:00:00.000Z",
    "updatedAt": "2026-06-01T00:00:00.000Z"
  },
  "message": "success"
}
```

---

### GET /products/{productId}/related — 연관 상품 조회

- 인증: 불필요
- 동일 productType 상품 배열 반환

#### Query Parameters

| 파라미터 | 타입 | 필수 | 기본값 | 설명 |
|---------|------|------|--------|------|
| limit | number | N | `4` | 반환할 상품 수 |

#### Response

**200 OK** — 상품 목록 조회와 동일한 item 구조

---

### GET /products/{productId}/reviews — 별점 목록 조회

- 인증: 불필요

#### Response

**200 OK**

```json
{
  "success": true,
  "data": [
    {
      "id": "uuid",
      "userId": "uuid",
      "rating": 5,
      "content": "리뷰 내용",
      "createdAt": "2024-01-01T00:00:00",
      "updatedAt": "2024-01-01T00:00:00"
    }
  ],
  "message": "success"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| id | string | 리뷰 ID |
| userId | string | 작성자 ID |
| rating | integer | 별점 (1~5) |
| content | string \| null | 리뷰 본문 |
| createdAt | string | 생성일시 |
| updatedAt | string | 수정일시 |

---

## 상품 (판매자)

### POST /sellers/me/products/uploads — 업로드 URL 발급 (presigned PUT)

- 인증: 필요
- 필요 역할: SELLER
- 이미지·산출물 파일 업로드는 백엔드를 경유하지 않는다. 백엔드는 presigned PUT URL만 발급하고,
  프론트가 그 URL로 S3에 파일을 직접 PUT한 뒤, 반환된 `fileUrl`을 상품 생성/수정 요청의
  `thumbnailUrl` / `imageUrls` / `fileUrl`에 넣어 보낸다.

#### Request

**Headers**

| 헤더 | 설명 |
|------|------|
| X-User-Id | 판매자 ID (API Gateway 주입) |
| X-User-Role | 사용자 역할 (API Gateway 주입) |

**Body**

```json
{ "purpose": "file", "fileName": "sample.pptx", "productType": "PPT" }
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| purpose | string | Y | `thumbnail` \| `image` \| `file` |
| fileName | string | Y | 원본 파일명(확장자 추출용) |
| productType | string | 조건부 | `purpose=file`일 때 필수(`PPT` \| `EXCEL`) |

- 확장자 검증(엄격): PPT→`pptx`/`ppt`, EXCEL→`xlsx`/`xls`, 이미지→`jpg`/`jpeg`/`png`/`gif`/`webp`.
  맞지 않으면 400 `P008`. content-type은 발급 시 서명에 포함된다.

#### Response

**200 OK**

```json
{
  "success": true,
  "data": {
    "uploadUrl": "https://<presigned-put-url>",
    "fileUrl": "https://<bucket>.s3.<region>.amazonaws.com/products/temp/file/<uuid>.pptx?..."
  },
  "message": "success"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| uploadUrl | string | 프론트가 파일을 직접 PUT할 대상(만료 있음) |
| fileUrl | string | 업로드 후 상품 생성/수정 요청에 넣을 값(임시 경로). 생성/수정 시 상품 경로로 이동됨 |

---

### POST /sellers/me/products — 상품 등록

- UC: UC-PRODUCT-01
- 인증: 필요
- 필요 역할: SELLER
- 등록 시 status: DRAFT

#### Request

**Headers**

| 헤더 | 설명 |
|------|------|
| X-User-Id | 판매자 ID (API Gateway 주입) |
| X-User-Role | 사용자 역할 (API Gateway 주입) |

**Body**

```json
{
  "title": "새 프롬프트 제목",
  "productType": "PROMPT",
  "model": "Claude 3.5",
  "desc": "설명",
  "amount": 5000,
  "content": "실제 프롬프트 내용",
  "thumbnailUrl": "https://...",
  "tags": ["태그1", "태그2"]
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| title | string | Y | 상품명 |
| productType | string | N | 상품 유형 (`PROMPT` \| `NOTION` \| `PPT` \| `EXCEL`, 기본값 `PROMPT`) |
| model | string | Y | 대상 AI 모델 |
| desc | string | Y | 상품 설명 |
| amount | integer | Y | 가격 |
| content | string | 유형별 | 프롬프트 원문 (PROMPT 필수) |
| fileUrl | string | 유형별 | 산출물 파일 URL (PPT/EXCEL 필수, 업로드 후 받은 URL) |
| externalUrl | string | 유형별 | 외부 노션 링크 (NOTION 필수) |
| thumbnailUrl | string | N | 썸네일 이미지 URL |
| tags | string[] | N | 판매자 지정 태그 목록 |

> **유형별 필수 필드**: PROMPT→`content`, PPT·EXCEL→`fileUrl`, NOTION→`externalUrl`. 각 유형은 해당 필드만 사용하며, 맞지 않는 필드가 채워지면 400 `P007`. 공개 상세 응답에는 `fileUrl`/`externalUrl`을 노출하지 않는다(구매 후 전달은 내부 API).

#### Response

**201 Created**

```json
{
  "success": true,
  "data": {
    "productId": "uuid",
    "sellerId": "uuid",
    "title": "새 프롬프트 제목",
    "productType": "PROMPT",
    "model": "Claude 3.5",
    "desc": "설명",
    "amount": 5000,
    "status": "DRAFT",
    "createdAt": "2024-01-01T00:00:00"
  },
  "message": "success"
}
```

---

### PUT /sellers/me/products/{productId} — 상품 수정

- UC: UC-PRODUCT-02
- 인증: 필요
- 필요 역할: SELLER
- 본인 상품만 수정 가능
- MINOR 수정: patchVersion 증가, 상태 유지
- MAJOR 수정: majorVersion 증가, 상태 → PENDING_REVIEW

#### Path Parameters

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| productId | UUID | 상품 ID |

#### Request

```json
{
  "title": "수정된 제목",
  "productType": "PROMPT",
  "model": "Claude 3.5",
  "desc": "수정된 설명",
  "amount": 6000,
  "content": "수정된 프롬프트 원문",
  "thumbnailUrl": "https://...",
  "tags": ["태그1"],
  "changeReason": "내용 보강",
  "versionType": "MINOR"
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| title | string | Y | 상품명 |
| productType | string | N | 상품 유형 (`PROMPT` \| `NOTION` \| `PPT` \| `EXCEL`, 기본값 `PROMPT`) |
| model | string | Y | 대상 AI 모델 |
| desc | string | Y | 상품 설명 |
| amount | integer | Y | 가격 |
| content | string | 유형별 | 프롬프트 원문 (PROMPT 필수) |
| fileUrl | string | 유형별 | 산출물 파일 URL (PPT/EXCEL 필수, 업로드 후 받은 URL) |
| externalUrl | string | 유형별 | 외부 노션 링크 (NOTION 필수) |
| thumbnailUrl | string | N | 썸네일 이미지 URL |
| tags | string[] | N | 판매자 지정 태그 목록 |
| changeReason | string | N | 변경 사유 |
| versionType | string | N | `MINOR`(기본) \| `MAJOR` |

> **유형별 필수 필드**: PROMPT→`content`, PPT·EXCEL→`fileUrl`, NOTION→`externalUrl`. 각 유형은 해당 필드만 사용하며, 맞지 않는 필드가 채워지면 400 `P007`.

#### Response

**200 OK** — 응답 바디 없음

---

### DELETE /sellers/me/products/{productId} — 상품 삭제 / 판매 중단

- UC: UC-PRODUCT-04
- 인증: 필요
- 필요 역할: SELLER / ADMIN
- DRAFT 상태: 소프트 삭제 (deletedAt 설정, 목록 제외)
- 그 외 상태: 판매 중단 (status → STOPPED, 목록 유지)

#### Path Parameters

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| productId | UUID | 상품 ID |

#### Response

**200 OK** — 응답 바디 없음

---

### PATCH /sellers/me/products/{productId}/submit — 검수 요청

- UC: UC-PRODUCT-06
- 인증: 필요
- 필요 역할: SELLER
- 상태 전이: DRAFT / REJECTED → PENDING_REVIEW
- DRAFT / REJECTED 외 상태에서 호출 시 409

#### Path Parameters

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| productId | UUID | 상품 ID |

#### Response

**200 OK** — 응답 바디 없음

---

### GET /sellers/me/products — 판매자 본인 상품 목록

- UC: UC-PRODUCT-07
- 인증: 필요
- 필요 역할: SELLER

#### Response

**200 OK**

```json
{
  "success": true,
  "data": [
    {
      "productId": "uuid",
      "title": "상품명",
      "productType": "PROMPT",
      "model": "Claude 3.5",
      "amount": 5000,
      "status": "DRAFT",
      "salesCount": 0,
      "thumbnailUrl": null,
      "rejectionReason": null,
      "createdAt": "2024-01-01T00:00:00",
      "updatedAt": "2024-01-01T00:00:00"
    }
  ],
  "message": "success"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| productId | string | 상품 ID |
| title | string | 상품명 |
| productType | string | 상품 유형 (`PROMPT` \| `NOTION` \| `PPT` \| `EXCEL`) |
| model | string | 대상 AI 모델 |
| amount | integer | 가격 |
| status | string | `DRAFT` \| `PENDING_REVIEW` \| `ON_SALE` \| `REJECTED` \| `STOPPED` |
| salesCount | integer | 누적 판매 수 |
| thumbnailUrl | string \| null | 썸네일 이미지 URL |
| rejectionReason | string \| null | 반려 사유 (REJECTED 상태일 때) |
| createdAt | string | 생성일시 |
| updatedAt | string | 수정일시 |

---

### GET /sellers/me/products/{productId} — 판매자 본인 상품 상세

- UC: UC-PRODUCT-08
- 인증: 필요
- 필요 역할: SELLER

#### Response

**200 OK**

```json
{
  "success": true,
  "data": {
    "productId": "uuid",
    "title": "상품명",
    "productType": "PROMPT",
    "model": "Claude 3.5",
    "amount": 5000,
    "desc": "설명",
    "content": "프롬프트 원문",
    "fileUrl": null,
    "externalUrl": null,
    "status": "DRAFT",
    "version": "1.0",
    "thumbnailUrl": null,
    "tags": ["태그1", "태그2"],
    "liveVersion": "1.0",
    "versions": [
      {
        "version": "1.0",
        "status": "ON_SALE",
        "date": "2026-07-01",
        "changeReason": null,
        "rejectionReason": null
      }
    ]
  },
  "message": "success"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| fileUrl | string \| null | 산출물 파일 presigned 다운로드 URL (PPT/EXCEL). 없으면 null |
| externalUrl | string \| null | 외부 노션 링크 (NOTION). 없으면 null |
| liveVersion | string \| null | 현재 판매중(ON_SALE) 버전 표기(`major.patch`). 판매중 버전이 없으면 null |
| versions | array | 이 상품의 버전 이력 목록 |
| versions[].version | string | 버전 표기(`major.patch`) |
| versions[].status | string | 해당 버전 상태 (`ON_SALE` / `SUPERSEDED` / `PENDING_REVIEW` / `REJECTED` 등) |
| versions[].date | string | 해당 버전 갱신일(YYYY-MM-DD) |
| versions[].changeReason | string \| null | 버전업 변경 사유 |
| versions[].rejectionReason | string \| null | 검수 반려 사유 (반려된 버전만) |

---

## 상품 검수 (관리자)

### GET /admin/products — 전체 상품 목록 조회

- UC: UC-PRODUCT-05
- 인증: 필요
- 필요 역할: ADMIN

#### Response

**200 OK**

```json
{
  "success": true,
  "data": [
    {
      "productId": "uuid",
      "title": "상품명",
      "sellerId": "uuid",
      "productType": "PROMPT",
      "model": "Claude 3.5",
      "amount": 5000,
      "status": "PENDING_REVIEW",
      "createdAt": "2024-01-01T00:00:00"
    }
  ],
  "message": "success"
}
```

---

### PUT /admin/products/{productId}/approve — 검수 승인

- UC: UC-PRODUCT-05
- 인증: 필요
- 필요 역할: ADMIN
- 상태 전이: PENDING_REVIEW → ON_SALE

#### Path Parameters

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| productId | UUID | 상품 ID |

#### Response

**200 OK** — 응답 바디 없음

---

### PUT /admin/products/{productId}/reject — 검수 반려

- UC: UC-PRODUCT-05
- 인증: 필요
- 필요 역할: ADMIN
- 상태 전이: PENDING_REVIEW → REJECTED

#### Path Parameters

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| productId | UUID | 상품 ID |

#### Request

```json
{
  "reason": "반려 사유"
}
```

#### Response

**200 OK** — 응답 바디 없음

---

## 리뷰

### POST /products/{productId}/reviews — 별점 작성

- UC: UC-PRODUCT-09
- 인증: 필요
- 필요 역할: USER
- 1상품 1리뷰 제약
- 미구현 (이슈 #93)

---

## 내부 API (Internal)

내부 서비스 간 호출 전용. Gateway를 거치지 않음.

### POST /internal/products/order-snapshots — 주문 스냅샷 조회

- 호출: order-service → product-service
- 호출 시점: 주문 생성 시

#### Request

```json
["uuid1", "uuid2"]
```

#### Response

```json
[
  {
    "productId": "uuid",
    "sellerId": "uuid",
    "title": "상품명",
    "productType": "PROMPT",
    "model": "GPT-4o",
    "amount": 5000
  }
]
```

---

### GET /internal/products/{productId}/cart-snapshot — 장바구니 단건 스냅샷

- 호출: order-service → product-service

#### Response

```json
{
  "productId": "uuid",
  "title": "상품명",
  "productType": "PROMPT",
  "model": "GPT-4o",
  "amount": 5000,
  "thumbnailUrl": "https://...",
  "sellerId": "uuid",
  "sellerNickname": "판매자명",
  "status": "ON_SALE"
}
```

---

### POST /internal/products/cart-snapshots — 장바구니 목록 스냅샷

- 호출: order-service → product-service

#### Request

```json
["uuid1", "uuid2"]
```

#### Response

cart-snapshot 배열 반환

---

### GET /internal/products/{productId}/content — 프롬프트 원문 조회

- 호출: order-service → product-service
- 호출 시점: 구매 후 콘텐츠 다운로드

#### Response

```json
{
  "productId": "uuid",
  "content": "프롬프트 원문"
}
```

---

### POST /internal/products/reviews — 리뷰 upsert

- 호출: order-service → product-service
- 호출 시점: 구매 후 리뷰 작성

#### Request

```json
{
  "buyerId": "uuid",
  "productId": "uuid",
  "rating": 5
}
```

---

### GET /internal/products/count — 판매자 등록 상품 수 조회

- 호출: settlement-service → product-service
- 호출 시점: 판매자 정산 요약 조회 시

#### Query Parameters

| 파라미터 | 타입 | 필수 | 설명 |
|---------|------|------|------|
| sellerId | UUID | Y | 판매자 ID |

#### Response

```json
{
  "sellerId": "uuid",
  "productCount": 12
}
```

## Kafka 이벤트

### 발행 (Producer)

#### `product-events`

`ProductEventProducer`가 발행한다. key는 `productId`(문자열).

| eventType | 발행 시점 | payload |
|---|---|---|
| `PRODUCT_DELETED` | 상품 삭제 시 | `productId`, `occurredAt` |
| `PRODUCT_PRICE_CHANGED` | 상품 가격 변경 시 | `productId`, `previousPrice`, `changedPrice`, `occurredAt` |
| `PRODUCT_STOPPED` | 상품 판매 중지 시 | `productId`, `occurredAt` |

예시 (`PRODUCT_PRICE_CHANGED`):

```json
{
  "eventType": "PRODUCT_PRICE_CHANGED",
  "productId": "uuid",
  "previousPrice": 10000,
  "changedPrice": 8000,
  "occurredAt": "2026-07-07T12:00:00"
}
```

### 구독 (Consumer)

#### `order-events`

`OrderEventConsumer`가 구독한다(`group=product-service`, 수동 커밋).

| eventType | 처리 |
|---|---|
| `ORDER_PAID` | `payload.products[].productId` 목록의 판매량(salesCount) 증가 |
| `ORDER_REFUND` | 위 목록의 판매량 감소 |
| 그 외 | 무시(로그만 남김) |

예상 payload 구조:

```json
{
  "eventType": "ORDER_PAID",
  "payload": {
    "products": [
      { "productId": "uuid" }
    ]
  }
}
```

## gRPC

### 제공 (Server)

product-service가 서버로 구현해 다른 서비스에 노출하는 서비스다.

#### `ProductQueryService` (소비: settlement-service)

| rpc | 요청 | 응답 |
|---|---|---|
| `CountBySeller` | `seller_id` | `seller_id`, `product_count` |

#### `ProductInternalService` (소비: order-service)

| rpc | 요청 | 응답 |
|---|---|---|
| `GetOrderSnapshots` | `product_ids[]` | `products[]`: `product_id`, `seller_id`, `title`, `product_type`, `amount`, `model` |
| `GetCartSnapshots` | `product_ids[]` | `products[]`: `product_id`, `seller_id`, `seller_nickname`, `title`, `product_type`, `amount`, `thumbnail_url` |
| `GetProductContent` | `product_id` | `product_id`, `content` |

#### `ProductService` (소비: user-service)

| rpc | 요청 | 응답 |
|---|---|---|
| `GetProductsByIds` | `product_ids[]` | `products[]`: `product_id`, `seller_id`, `title`, `price`, `thumbnail_url`, `category`, `model`, `sales_count`, `average_rating`, `status` |

### 소비 (Client)

product-service가 클라이언트로 호출하는, 다른 서비스가 제공하는 서비스다.

#### `SellerQueryService` (제공: user-service)

| rpc | 요청 | 응답 |
|---|---|---|
| `FindSellers` | `seller_ids[]` | `sellers[]`: `SellerInfo` |
| `GetSeller` | `seller_id` | `SellerInfo`(`seller_id`, `seller_name`, `profile_image_url`, `status`) |

> `FindSellers`는 이 문서 상단 gRPC 네이밍 컨벤션(`Get{Entity}`)과 다르지만, user-service가
> 소유한 계약이라 product-service 쪽에서 리네임 후 적용한다.
