# Product Service API

**Base:** `http://localhost:xxxx/api/v1`

> ⚠ `api/v1`은 세미 프로젝트 완성 스냅샷(`v1.0.0` 태그) 기준 경로다. 최종 프로젝트에서
> `api/v2`로 전환 예정이며 별도 이슈로 진행한다(`docs/adr/config-management.md` §10).

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
| category | string | N | `"all"` | `all\|image\|writing\|coding\|marketing\|chatbot\|data` |
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
      "category": "image",
      "icon": "image",
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
| category | string | 카테고리 |
| icon | string | 아이콘 |
| productType | string | 상품 유형 (`PROMPT` \| `TEMPLATE` \| `DATASET` \| `IMAGE_ASSET`) |
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
    "category": "image",
    "icon": "image",
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
- 동일 카테고리 상품 배열 반환

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

### POST /products — 상품 등록

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
  "category": "coding",
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
| category | string | Y | 카테고리 |
| model | string | Y | 대상 AI 모델 |
| desc | string | Y | 상품 설명 |
| amount | integer | Y | 가격 |
| content | string | Y | 프롬프트 원문 |
| thumbnailUrl | string | N | 썸네일 이미지 URL |
| tags | string[] | N | 판매자 지정 태그 목록 |

#### Response

**201 Created**

```json
{
  "success": true,
  "data": {
    "productId": "uuid",
    "sellerId": "uuid",
    "title": "새 프롬프트 제목",
    "category": "coding",
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

### PUT /products/{productId} — 상품 수정

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
  "category": "coding",
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
| category | string | Y | 카테고리 |
| model | string | Y | 대상 AI 모델 |
| desc | string | Y | 상품 설명 |
| amount | integer | Y | 가격 |
| content | string | Y | 프롬프트 원문 |
| thumbnailUrl | string | N | 썸네일 이미지 URL |
| tags | string[] | N | 판매자 지정 태그 목록 |
| changeReason | string | N | 변경 사유 |
| versionType | string | N | `MINOR`(기본) \| `MAJOR` |

#### Response

**200 OK** — 응답 바디 없음

---

### DELETE /products/{productId} — 상품 삭제 / 판매 중단

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

### PATCH /products/{productId}/submit — 검수 요청

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
      "category": "coding",
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
| category | string | 카테고리 |
| productType | string | 상품 유형 (`PROMPT` \| `TEMPLATE` \| `DATASET` \| `IMAGE_ASSET`) |
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
    "category": "coding",
    "productType": "PROMPT",
    "model": "Claude 3.5",
    "amount": 5000,
    "desc": "설명",
    "content": "프롬프트 원문",
    "status": "DRAFT",
    "version": "1.0",
    "thumbnailUrl": null,
    "tags": ["태그1", "태그2"]
  },
  "message": "success"
}
```

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
      "category": "coding",
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
    "productType": "GPT-4o",
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
  "productType": "GPT-4o",
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
