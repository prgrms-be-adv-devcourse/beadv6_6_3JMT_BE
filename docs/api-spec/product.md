# Product Service API

**Base:** `http://localhost:xxxx/api/v1`

## 공통 사항

- 인증이 필요한 엔드포인트는 `Authorization: Bearer {accessToken}` 헤더 필요
- 토큰 검증은 API Gateway에서 수행. 각 서비스는 헤더(`X-User-Id`, `X-User-Role`)만 읽음

---

## 상품

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
  "model": "Claude 3.5",
  "desc": "설명",
  "amount": 5000,
  "content": "실제 프롬프트 내용"
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
    "cat": "image",
    "icon": "image",
    "model": "Midjourney v6",
    "amount": 5900,
    "rating": 4.9,
    "salesCount": 1240,
    "seller": "비주얼랩",
    "sellerId": "uuid",
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

| 필드 | 타입 | 설명 |
|------|------|------|
| id | string | 상품 ID |
| title | string | 상품명 |
| cat | string | 카테고리 |
| icon | string | 아이콘 |
| model | string | 대상 AI 모델 |
| amount | integer | 가격 |
| rating | number | 평균 별점 |
| salesCount | integer | 누적 판매 수 |
| seller | string | 판매자 이름 |
| sellerId | string | 판매자 ID |
| badge | string | 뱃지 (`신규` 등) |
| desc | string | 상품 설명 |
| thumbnail_url | string \| null | 썸네일 이미지 URL |
| content | string | 프롬프트 내용 (미구매 시 일부만 노출) |
| versions | array | 버전 히스토리 |
| versions[].ver | string | 버전명 |
| versions[].date | string | 배포일 |
| versions[].note | string | 변경 내용 |
| features | array | 주요 특징 목록 |
| createdAt | string | 생성일시 (ISO 8601) |
| updatedAt | string | 수정일시 (ISO 8601) |

---

### PUT /products/{productId} — 상품 수정

- UC: UC-PRODUCT-02
- 인증: 필요
- 필요 역할: SELLER
- 본인 상품만 수정 가능

#### Path Parameters

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| productId | UUID | 상품 ID |

#### Request

#### Response

---

### DELETE /products/{productId} — 상품 삭제 (판매 중지)

- UC: UC-PRODUCT-04
- 인증: 필요
- 필요 역할: ADMIN / SELLER
- 본인 상품 또는 관리자만 가능

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
    "message": "삭제되었습니다."
  },
  "message": "success"
}
```

---

### GET /products/{productId}/related — 연관 상품 조회

- 인증: 불필요
- 동일 카테고리 상품 배열 반환

#### Path Parameters

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| productId | UUID | 상품 ID |

#### Query Parameters

| 파라미터 | 타입 | 필수 | 기본값 | 설명 |
|---------|------|------|--------|------|
| limit | number | N | `4` | 반환할 상품 수 |

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
  "message": "success"
}
```

---

## 상품 검수 (관리자)

### GET /products/pending-review — 검수 대기 목록

- UC: UC-PRODUCT-05
- 인증: 필요
- 필요 역할: ADMIN

#### Query Parameters

#### Response

---

### PATCH /products/{productId}/approve — 검수 승인

- UC: UC-PRODUCT-05
- 인증: 필요
- 필요 역할: ADMIN
- 상태 전이: PENDING_REVIEW → ON_SALE

#### Path Parameters

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| productId | String (UUID) | 상품 ID |

#### Response

**200 OK** — 응답 바디 없음

---

### PATCH /products/{productId}/reject — 검수 반려

- UC: UC-PRODUCT-05
- 인증: 필요
- 필요 역할: ADMIN
- 상태 전이: PENDING_REVIEW → REJECTED

#### Path Parameters

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| productId | UUID | 상품 ID |

#### Request

#### Response

---

## 리뷰

### GET /products/{productId}/reviews — 별점 목록 조회

- 인증: 불필요

#### Path Parameters

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| productId | UUID | 상품 ID |

#### Response

---

### POST /products/{productId}/reviews — 별점 작성

- 인증: 필요
- 필요 역할: USER
- 1상품 1리뷰 제약

#### Path Parameters

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| productId | UUID | 상품 ID |

#### Request

#### Response

---

## 내부 API (Internal)

### GET /internal/products/{productId}/snapshot — 상품 스냅샷 조회

- 인증: 필요
- 호출 대상: Order Service
- 상품명·가격·상태·sellerId 반환

#### Path Parameters

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| productId | String (UUID) | 상품 ID |

#### Response

**200 OK**

```json
{
  "productId": "string (UUID)",
  "sellerId": "string (UUID)",
  "name": "string",
  "amount": 0,
  "status": "string"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| productId | string | 상품 ID |
| sellerId | string | 판매자 ID |
| name | string | 상품명 |
| amount | integer | 상품 가격 |
| status | string | 상품 상태 (`DRAFT` / `PENDING_REVIEW` / `ON_SALE` / `REJECTED`) |

---

### GET /internal/products/{productId}/content — 프롬프트 원문 조회

- 인증: 필요
- 호출 대상: Order Service

#### Path Parameters

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| productId | String (UUID) | 상품 ID |

#### Response

**200 OK**

```json
{
  "productId": "string (UUID)",
  "content": "string"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| productId | string | 상품 ID |
| content | string | 프롬프트 원문 |
