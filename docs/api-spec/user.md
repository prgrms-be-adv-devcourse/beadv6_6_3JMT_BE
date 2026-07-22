# User Service API

**Base:** `http://localhost:8081/api/v2`

> user-service 공개 API는 `#305 (이슈)`에서 `/api/v2`로 전환했다. 인증 API도 별도 전환을 거쳐
> 현재 `/api/v2/auth`이며, 전체 도메인이 `api/v2`로 통일된 상태다.

## 공통 사항

- 인증이 필요한 엔드포인트는 `Authorization: Bearer {accessToken}` 헤더 필요
- 토큰 검증은 API Gateway에서 수행. 각 서비스는 헤더(`X-User-Id`, `X-User-Role`)만 읽음

---

## 회원 프로필

### GET /users/me — 내 프로필 조회

- 인증: 필요
- 필요 역할: BUYER / SELLER
- 프로필 정보와 판매자 신청 상태를 함께 반환 — 마이페이지 배너 표시 여부 판단에 사용

#### Response

**200 OK**

```json
{
  "success": true,
  "data": {
    "id": "uuid",
    "name": "김민서",
    "email": "user@example.com",
    "profileImageUrl": "https://cdn.example.com/images/profile.jpg",
    "role": "BUYER",
    "sellerStatus": "PENDING",
    "provider": "local"
  },
  "message": "success"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| id | string | 사용자 ID |
| name | string | 이름 |
| email | string | 이메일 |
| profileImageUrl | string \| null | 프로필 이미지 URL |
| role | string | 역할 (`BUYER` / `SELLER`) |
| sellerStatus | string \| null | 판매자 신청 상태 (`PENDING` / `APPROVED` / `REJECTED` / `null`) |

**sellerStatus 값 규칙**

| sellerStatus | 설명 |
|---|---|
| `null` | 판매자 신청 이력 없는 BUYER |
| `PENDING` | 심사 대기 중 |
| `APPROVED` | 승인됨 (role이 `SELLER`이면 항상 이 값) |
| `REJECTED` | 반려됨 |
| provider | string | 가입 방식 (`local` / `kakao`) |

---

### PATCH /users/me — 프로필 수정

- 인증: 필요
- 필요 역할: BUYER / SELLER
- 수정할 필드만 포함 (Partial Update)
- 응답 `data`에는 요청에서 실제로 수정된 필드만 포함됨
- `password` 필드는 `provider=local` 사용자만 허용. OAuth 사용자(`kakao` 등)가 포함하면 400 반환

#### Request

**Body**

```json
{
  "name": "새이름",
  "email": "new@example.com",
  "password": "password"
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| name | string | N | 이름 |
| email | string | N | 이메일 |
| password | string | N | 비밀번호 — `local` 가입 사용자만 허용 |

#### Response

**200 OK** — 수정된 필드만 포함 (미수정 필드는 응답에서 생략)

예: `name`만 수정한 경우
```json
{
  "success": true,
  "data": {
    "id": "uuid",
    "name": "새이름"
  },
  "message": "success"
}
```

예: `name`과 `email` 모두 수정한 경우
```json
{
  "success": true,
  "data": {
    "id": "uuid",
    "name": "새이름",
    "email": "new@example.com"
  },
  "message": "success"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| id | string | 사용자 ID (항상 포함) |
| name | string \| 생략 | 변경된 이름 (수정 시에만 포함) |
| email | string \| 생략 | 변경된 이메일 (수정 시에만 포함) |

---

### DELETE /users/me — 회원 탈퇴

- 인증: 필요
- 필요 역할: BUYER / SELLER
- 참고: ADR-0008 §결정 8 (세션 폐기)

처리 순서

1. `user.status` → `WITHDRAWN` (soft delete. `deleted_at` 컬럼은 없음 — `status` enum으로 관리)
2. 세션 즉시 폐기 — 호출자(본인 탈퇴 / admin 강제 탈퇴) 무관하게 동일 경로로 처리
   1. `refresh_token` 테이블에서 해당 유저의 모든 RT 삭제
   2. `user:authz:{userId}` authorize 캐시 무효화 (Redis 캐시, `#289`)

> **범위 제외**: 진행 중인 주문이 있는지 확인하는 체크(`AUTH_WITHDRAW_ORDER_IN_PROGRESS`, A010)는 order-service
> 조회 연동이 필요해 별도 이슈로 분리한다. 현재 DELETE /users/me는 주문 상태와 무관하게 항상 탈퇴를 허용한다.

#### Response

**204 No Content** — 응답 바디 없음

---

## 판매자 등록

### POST /seller/register — 판매자 등록 신청

- UC: UC-AUTH-05
- 인증: 필요
- 필요 역할: BUYER
- 신청 시 status: PENDING

#### Request

**Body**

```json
{
  "categories": ["marketing", "coding"],
  "introduction": "마케팅 카피·블로그 글쓰기용 GPT 프롬프트를 주로 만듭니다.",
  "portfolioUrl": "https://blog.example.com",
  "agreedToTerms": true
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| categories | string[] | Y | 주력 카테고리, 최대 3개 |
| introduction | string | N | 판매할 프롬프트 소개 |
| portfolioUrl | string | N | 블로그/포트폴리오/SNS 링크 |
| agreedToTerms | boolean | Y | 판매자 이용약관 및 정산 정책 동의 여부 |

#### Response

**201 Created**

```json
{
  "success": true,
  "data": {
    "sellerRequestId": "uuid",
    "status": "PENDING",
    "submittedAt": "2025-06-17T10:00:00Z"
  },
  "message": "success"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| sellerRequestId | string | 판매자 등록 신청 ID |
| status | string | 신청 상태 (`PENDING`) |
| submittedAt | string | 신청일시 (ISO 8601) |

---

### GET /sellers/register/me — 내 판매자 등록 신청 상세 조회(구현 금지)

- 인증: 필요
- 필요 역할: BUYER
- 신청 상세 정보 조회용 — 심사 현황 상세 페이지에서 사용
- 마이페이지 프로필 화면(배너)에서는 `GET /users/me`의 `sellerStatus` 사용

#### Response

**200 OK**

```json
{
  "success": true,
  "data": {
    "sellerRequestId": "uuid",
    "status": "PENDING",
    "categories": ["marketing", "coding"],
    "introduction": "마케팅 카피·블로그 글쓰기용 GPT 프롬프트를 주로 만듭니다.",
    "portfolioUrl": "https://blog.example.com",
    "submittedAt": "2025-06-17T10:00:00Z",
    "reviewedAt": null,
    "rejectReason": null
  },
  "message": "success"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| sellerRequestId | string | 판매자 등록 신청 ID |
| status | string | 신청 상태 (`PENDING` / `APPROVED` / `REJECTED`) |
| categories | string[] | 주력 카테고리 |
| introduction | string \| null | 판매할 프롬프트 소개 |
| portfolioUrl | string \| null | 블로그/포트폴리오/SNS 링크 |
| submittedAt | string | 신청일시 (ISO 8601) |
| reviewedAt | string \| null | 심사 완료일시 (ISO 8601), 심사 전 null |
| rejectReason | string \| null | 반려 사유, 반려된 경우에만 포함 |

---

### GET /sellers/product — 판매자 단건 조회

- 인증: 불필요 (`/detail/[id]` 비로그인 접근 대응)
- 필요 역할: 없음
- 상품 상세 페이지의 판매자 카드 표시용. Client는 `GET /products/{productId}` 응답의 `sellerId`를 그대로 전달해 호출한다.
- 단건 조회라 같은 `sellerId`는 항상 같은 응답을 내므로 HTTP 캐싱 이득이 있다 — 다건 조회(`/sellers/products`)와는 목적이 다르다.

#### Request

**Query Parameters**

| 파라미터 | 타입 | 필수 | 설명 |
|---------|------|------|------|
| sellerId | string(UUID) | Y | 조회할 판매자 ID |

#### Response

**200 OK**

```json
{
  "success": true,
  "data": {
    "sellerName": "김철수",
    "profileImageUrl": "https://.../profile.png"
  },
  "message": "success"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| sellerName | string | 판매자 이름 |
| profileImageUrl | string \| null | 프로필 이미지 URL. 미등록 시 null |

**400 Bad Request** — `sellerId` 누락 또는 잘못된 UUID 형식 (`VALIDATION_FAILED`, V001)

**404 Not Found** — 형식은 유효하나 존재하지 않는 sellerId (`AUTH_NOT_FOUND`, A001)

---

### POST /sellers/products — 판매자 이름 다건 조회

- 인증: 불필요 (`/browse` 비로그인 접근 대응)
- 필요 역할: 없음
- `/browse`(상품 목록)에서 `GET /products` 응답의 `sellerId` 목록을 받아 순차 호출(2차 조회)로 사용

#### Request

**Body**

```json
{
  "sellerIds": ["3f1b1b0e-...", "..."]
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| sellerIds | string(UUID)[] | Y | 조회할 판매자 ID 목록. 최대 30개, 빈 배열/형식 오류 시 400. 중복은 서버가 dedupe |

#### Response

**200 OK**

```json
{
  "success": true,
  "data": {
    "sellers": [
      { "sellerId": "3f1b1b0e-...", "sellerName": "김철수" },
      { "sellerId": "9c2a...", "sellerName": null }
    ]
  },
  "message": "success"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| sellers[].sellerId | string(UUID) | 요청한 sellerId |
| sellers[].sellerName | string \| null | 판매자 이름. 존재하지 않는 sellerId(탈퇴/삭제 등)는 null — 이 경우도 전체 요청은 실패 처리하지 않음 |

**400 Bad Request** — 빈 배열, 잘못된 UUID 형식, 30개 초과 (`VALIDATION_FAILED`, V001)

---

### POST /sellers/wishlists — Wishlist 판매자 이름 다건 조회

- 인증: 필요
- 필요 역할: BUYER / SELLER
- `POST /products/wishlists` 응답의 `sellerId` 목록을 받아 Wishlist 카드의 판매자명을 채운다.
- Wishlist 전용 요청·응답 DTO를 사용하며 JSON 스키마, 최대 30개, 중복 제거와 누락 판매자 `null` 정책은 `POST /sellers/products`와 동일하다.

#### Request

**Body**

```json
{
  "sellerIds": ["3f1b1b0e-...", "..."]
}
```

#### Response

**200 OK**

```json
{
  "success": true,
  "data": {
    "sellers": [
      { "sellerId": "3f1b1b0e-...", "sellerName": "김철수" },
      { "sellerId": "9c2a...", "sellerName": null }
    ]
  },
  "message": "success"
}
```

**400 Bad Request** — 빈 배열, 잘못된 UUID 형식, 30개 초과 (`VALIDATION_FAILED`, V001)

---

## 찜 (Wishlist)

### POST /wishlists — 찜 등록

- 인증: 필요
- 필요 역할: BUYER / SELLER

#### Request

**Body**

```json
{
  "productId": "uuid"
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| productId | string | Y | 찜할 상품 ID |

#### Response

**201 Created**

```json
{
  "success": true,
  "data": {
    "wishlistId": "uuid",
    "productId": "uuid",
    "createdAt": "2026-06-17T10:00:00"
  },
  "message": "success"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| wishlistId | string | 생성된 찜 ID |
| productId | string | 상품 ID |
| createdAt | string | 생성일시 (ISO 8601) |

---

### DELETE /wishlists/{wishlistId} — 찜 삭제

- 인증: 필요
- 필요 역할: BUYER / SELLER

#### Path Parameters

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| wishlistId | UUID | 삭제할 찜 ID |

#### Response

**204 No Content** — 응답 바디 없음

---

### GET /wishlists — 찜한 상품 목록

- 인증: 필요
- 필요 역할: BUYER / SELLER

#### Query Parameters

| 파라미터 | 타입 | 필수 | 기본값 | 설명 |
|---------|------|------|--------|------|
| page | number | N | `0` | 페이지 번호 |
| size | number | N | `20` | 페이지당 항목 수 |

#### Response

**200 OK**

```json
{
  "success": true,
  "data": [
    {
      "wishlistId": "uuid",
      "productId": "uuid",
      "addedAt": "2026-07-22T12:00:00"
    }
  ],
  "message": "success",
  "meta": {
    "page": 1,
    "size": 20,
    "total": 5,
    "hasNext": false
  }
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| wishlistId | string | 찜 ID |
| productId | string | 상품 ID |
| addedAt | string | 찜 등록일시 (ISO 8601) |
| meta.page | integer | 현재 페이지 번호 |
| meta.size | integer | 페이지당 항목 수 |
| meta.total | integer | 전체 항목 수 |
| meta.hasNext | boolean | 다음 페이지 존재 여부 |

상품·판매자 카드 정보는 Client가 Product `POST /products/wishlists`와
User `POST /sellers/wishlists`를 순차 호출해 조합한다.

---

### GET /wishlists/exists — 찜 여부 확인

- 인증: 필요
- 필요 역할: BUYER / SELLER
- 상품 상세 진입 시 하트 버튼 활성화 여부 판단용

#### Query Parameters

| 파라미터 | 타입 | 필수 | 기본값 | 설명 |
|---------|------|------|--------|------|
| productId | string | Y | - | 확인할 상품 ID |

#### Response

**200 OK**

```json
{
  "success": true,
  "data": {
    "wished": true
  },
  "message": "success"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| wished | boolean | 찜 등록 여부 |

---

## 관리자 — 사용자 관리

### GET /admin/users — 전체 사용자 목록

- 인증: 필요
- 필요 역할: ADMIN

#### Query Parameters

| 파라미터 | 타입 | 필수 | 설명 |
|---------|------|------|------|
| page | int | N | `1` | 페이지 번호 |
| size | int | N | `20` | 페이지당 항목 수 |
| status | string | N | `ALL` | 계정 상태 필터 (`active` \| `suspended` \| `withdrawn` \| `ALL`) |
| role | string | N | `ALL` | 역할 필터 (`buyer` \| `seller` \| `ALL`) |
| keyword | string | N | - | 이름·이메일·회원ID 검색 |

#### Response

**200 OK**

```json
{
  "success": true,
  "data": [
    {
      "id": "uuid",
      "name": "김도윤",
      "email": "doyoon.kim@gmail.com",
      "role": "buyer",
      "status": "active"
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

| 필드 | 타입 | 설명 |
|------|------|------|
| id | string | 사용자 ID |
| name | string | 이름 |
| email | string | 이메일 |
| role | string | 역할 (`buyer` / `seller`) |
| status | string | 계정 상태 (`active` / `suspended` / `withdrawn`) |
| meta.page | integer | 현재 페이지 번호 |
| meta.size | integer | 페이지당 항목 수 |
| meta.total | integer | 전체 항목 수 |
| meta.hasNext | boolean | 다음 페이지 존재 여부 |

---

### PATCH /admin/users/{userId}/status — 사용자 상태 변경

- 인증: 필요
- 필요 역할: ADMIN

#### Path Parameters

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| userId | UUID | 대상 사용자 ID |

#### Request

**Body**

```json
{
  "status": "suspended"
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| status | string | Y | 변경할 계정 상태 (`active` / `suspended` / `withdrawn`) |

| status 값 | 설명 |
|-----------|------|
| `active` | 활성으로 변경 |
| `suspended` | 정지 처리 |
| `withdrawn` | 탈퇴 처리 |

#### Response

**200 OK**

```json
{
  "success": true,
  "data": {
    "id": "uuid",
    "status": "suspended",
    "updatedAt": "2026-06-17T10:00:00Z"
  },
  "message": "success"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| id | string | 사용자 ID |
| status | string | 변경된 계정 상태 |
| updatedAt | string | 변경일시 (ISO 8601) |

---

### GET /admin/stats/users — 회원 통계 조회

- 인증: 필요
- 필요 역할: ADMIN

#### Response

**200 OK**

```json
{
  "success": true,
  "data": {
    "totalUsers": 1240,
    "todayNewUsers": 13
  },
  "message": "success"
}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| totalUsers | integer | 누적 회원 수 |
| todayNewUsers | integer | 오늘 신규 가입 수 |

---

## 관리자 — 판매자 등록 심사

### GET /admin/sellers/register — 판매자 신청 목록

- 인증: 필요
- 필요 역할: ADMIN

#### Query Parameters

| 파라미터 | 타입 | 필수 | 설명 |
|---------|------|------|------|
| page | int | N | `1` | 페이지 번호 |
| size | int | N | `20` | 페이지당 항목 수 |
| status | string | N | `ALL` | 신청 상태 필터 (`PENDING` \| `APPROVED` \| `REJECTED` \| `ALL`) |

#### Response

**200 OK**

```json
{
  "success": true,
  "data": [
    {
      "registerId": "uuid",
      "userId": "uuid",
      "nickname": "이서아",
      "email": "seoah@example.com",
      "introduction": "미드저니·DALL·E 기반 제품 목업과 광고 컷 프롬프트를 전문으로 제작합니다.",
      "categories": ["이미지 생성"],
      "portfolioUrl": "https://blog.example.com",
      "status": "pending",
      "submittedAt": "2026-06-14T00:00:00Z"
    }
  ],
  "message": "success",
  "meta": {
    "page": 1,
    "size": 20,
    "total": 6,
    "hasNext": false
  }
}
```

| 필드           | 타입 | 설명                                          |
|--------------|------|---------------------------------------------|
| registerId   | string | 판매자 등록 신청 ID                                |
| userId       | string | 신청자 ID                                      |
| name           | string | 신청자 닉네임                                     |
| email        | string | 신청자 이메일                                     |
| introduction | string \| null | 판매자 소개                                      |
| categories   | string[] | 주력 카테고리                                     |
| portfolioUrl | string \| null | 포트폴리오 URL                                   |
| status       | string | 신청 상태 (`pending` / `approved` / `rejected`) |
| submittedAt  | string | 신청일시 (ISO 8601)                             |
| meta.page    | integer | 현재 페이지 번호                                   |
| meta.size    | integer | 페이지당 항목 수                                   |
| meta.total   | integer | 전체 항목 수                                     |
| meta.hasNext | boolean | 다음 페이지 존재 여부                                |

---

### PATCH /admin/sellers/register/{registerId}/approve — 판매자 신청 승인

- 인증: 필요
- 필요 역할: ADMIN
- 승인 시 SELLER 역할 부여

#### Path Parameters

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| registerId | UUID | 신청 ID |

#### Response

**200 OK**

```json
{
  "success": true,
  "data": {
    "registerId": "uuid",
    "userId": "uuid",
    "status": "APPROVED",
    "reviewedAt": "2026-06-17T10:00:00Z"
  },
  "message": "success"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| registerId | string | 판매자 등록 신청 ID |
| userId | string | 승인된 사용자 ID |
| status | string | 처리 상태 (`APPROVED`) |
| reviewedAt | string | 심사 완료일시 (ISO 8601) |

---

### PATCH /admin/sellers/register/{registerId}/reject — 판매자 신청 반려

- 인증: 필요
- 필요 역할: ADMIN

#### Path Parameters

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| registerId | UUID | 신청 ID |

#### Request

**Body**

```json
{
  "rejectReason": "포트폴리오가 확인되지 않습니다. 샘플을 보완 후 재신청해 주세요."
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| rejectReason | string | Y | 반려 사유 |

#### Response

**200 OK**

```json
{
  "success": true,
  "data": {
    "registerId": "uuid",
    "userId": "uuid",
    "status": "REJECTED",
    "rejectReason": "포트폴리오가 확인되지 않습니다. 샘플을 보완 후 재신청해 주세요.",
    "reviewedAt": "2026-06-17T10:05:00Z"
  },
  "message": "success"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| registerId | string | 판매자 등록 신청 ID |
| userId | string | 대상 사용자 ID |
| status | string | 처리 상태 (`REJECTED`) |
| rejectReason | string | 반려 사유 |
| reviewedAt | string | 심사 완료일시 (ISO 8601) |

---

## product-service 연동 — Kafka 이벤트 구독 스펙

> 찜 목록 응답에 `salesCount`, `model` 필드를 포함하기 위해  
> user-service는 product-service가 발행하는 아래 이벤트를 구독한다.

### 요청 토픽: `product.created` / `product.updated`

**페이로드 스펙 (product-service 담당자 발행 기준)**

> `sellerId`는 `user.user_id`와 동일하다. 별도 seller 테이블을 두지 않으며, 서비스 간 판매자 식별자는 `user_id`로 통일한다.

```json
{
  "eventType": "PRODUCT_CREATED",
  "productId": "uuid",
  "sellerId": "uuid (= user_id)",
  "title": "...",
  "price": 9900,
  "thumbnailUrl": "...",
  "model": "GPT-4",
  "salesCount": 0,
  "averageRating": 0.0,
  "status": "ACTIVE",
  "occurredAt": "2025-06-17T10:00:00Z"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| eventType | string | `PRODUCT_CREATED` / `PRODUCT_UPDATED` |
| productId | string | 상품 ID |
| sellerId | string | 판매자 ID (= user_id) |
| title | string | 상품명 |
| price | integer | 가격 |
| thumbnailUrl | string \| null | 썸네일 이미지 URL |
| model | string | AI 모델 |
| salesCount | integer | 판매 수량 |
| averageRating | number | 평균 별점 |
| status | string | 상품 상태 (`ACTIVE` / `INACTIVE`) |
| occurredAt | string | 이벤트 발생일시 (ISO 8601) |

**발행 시점**

| 이벤트 | 발행 조건 |
|--------|----------|
| `PRODUCT_CREATED` | 상품 등록 시 |
| `PRODUCT_UPDATED` | 상품 수정 시, 리뷰 변경으로 `averageRating` 갱신 시 |

---

## 내부 서비스 통신 (gRPC)

> 외부 노출 없음. 서비스 간 내부 통신 전용.
> 프로토콜: gRPC

---

### 1. 판매자 단건 조회

- **호출 방향**: product-service → user-service
- **호출 시점**: 상품 목록/상세 조회 시 판매자 정보 필요할 때

**Request**

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| userId | string (UUID) | Y | 조회할 판매자 ID |

**Response**

| 필드 | 타입 | 설명 |
|---|---|---|
| sellerId | string (UUID) | 판매자 ID |
| sellerName | string | 판매자 닉네임 |
| profileImageUrl | string \| null | 프로필 이미지 URL |
| status | string | 판매자 상태 (`ACTIVE` 등) |

---

### 2. 판매자 다건 조회 (batch)

- **호출 방향**: settlement-service → user-service
- **호출 시점**: 어드민 판매자별 정산 목록 조회 시

**Request**

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| sellerIds | repeated string (UUID) | Y | 조회할 판매자 ID 목록 (중복 제거 권장) |

**Response**

| 필드 | 타입 | 설명 |
|---|---|---|
| sellers | repeated Seller | 판매자 정보 목록 |

**Seller 객체**

| 필드 | 타입 | 설명 |
|---|---|---|
| sellerId | string (UUID) | 판매자 ID |
| sellerName | string | 판매자 닉네임 |
| profileImageUrl | string \| null | 프로필 이미지 URL |
| status | string | 판매자 상태 (`ACTIVE` 등) |
