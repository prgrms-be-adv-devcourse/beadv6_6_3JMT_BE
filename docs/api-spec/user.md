# User Service API

**Base:** `http://localhost:xxxx/api/v1`

## 공통 사항

- 인증이 필요한 엔드포인트는 `Authorization: Bearer {accessToken}` 헤더 필요
- 토큰 검증은 API Gateway에서 수행. 각 서비스는 헤더(`X-User-Id`, `X-User-Role`)만 읽음

---

## 회원 프로필

### GET /users/me — 내 프로필 조회

- 인증: 필요
- 필요 역할: BUYER / SELLER

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
    "role": "BUYER"
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

---

### PUT /users/me — 프로필 수정

- 인증: 필요
- 필요 역할: BUYER / SELLER
- 수정할 필드만 포함 (Partial Update)

#### Request

**Body**

```json
{
  "nickname": "새이름",
  "email": "new@example.com",
  "password": "password"
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| nickname | string | N | 닉네임 |
| email | string | N | 이메일 |
| password | string | N | 비밀번호 |

#### Response

**200 OK**

```json
{
  "success": true,
  "data": {
    "id": "uuid",
    "nickname": "새닉네임"
  },
  "message": "success"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| id | string | 사용자 ID |
| nickname | string | 변경된 닉네임 |

---

### DELETE /users/me — 회원 탈퇴

- 인증: 필요
- 필요 역할: BUYER / SELLER

#### Response

**200 OK**

```json
{
  "success": true,
  "data": null,
  "message": "success"
}
```

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
  "categories": ["마케팅", "코딩"],
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

### GET /sellers/register/me — 내 판매자 등록 신청 조회

- 인증: 필요
- 필요 역할: BUYER
- 마이페이지 "심사 중" 상태 배너 표시 등에 활용

#### Response

**200 OK**

```json
{
  "success": true,
  "data": {
    "sellerRequestId": "uuid",
    "status": "PENDING",
    "categories": ["마케팅", "코딩"],
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
      "title": "GPT 마케팅 카피 프롬프트",
      "thumbnailUrl": "https://cdn.example.com/images/thumb.jpg",
      "price": 3900,
      "sellerNickname": "프롬작가",
      "averageRating": 4.7,
      "addedAt": "2025-03-01T12:00:00Z"
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
| title | string | 상품명 |
| thumbnailUrl | string \| null | 썸네일 이미지 URL |
| price | integer | 가격 |
| sellerNickname | string | 판매자 닉네임 |
| averageRating | number | 평균 별점 |
| addedAt | string | 찜 등록일시 (ISO 8601) |
| meta.page | integer | 현재 페이지 번호 |
| meta.size | integer | 페이지당 항목 수 |
| meta.total | integer | 전체 항목 수 |
| meta.hasNext | boolean | 다음 페이지 존재 여부 |

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
| status | string | N | `ALL` | 계정 상태 필터 (`ACTIVE` \| `SUSPENDED` \| `WITHDRAWN` \| `ALL`) |
| role | string | N | `ALL` | 역할 필터 (`BUYER` \| `SELLER` \| `ALL`) |
| keyword | string | N | - | 이름·이메일·회원ID 검색 |

#### Response

**200 OK**

```json
{
  "success": true,
  "data": [
    {
      "userId": "uuid",
      "displayId": "U-10293",
      "nickname": "김도윤",
      "email": "doyoon.kim@gmail.com",
      "role": "BUYER",
      "orderCount": 0,
      "status": "ACTIVE",
      "createdAt": "2026-06-14T00:00:00Z"
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
| userId | string | 사용자 ID |
| displayId | string | 표시용 사용자 ID |
| nickname | string | 닉네임 |
| email | string | 이메일 |
| role | string | 역할 (`BUYER` / `SELLER`) |
| orderCount | integer | 주문 수 |
| status | string | 계정 상태 (`ACTIVE` / `SUSPENDED` / `WITHDRAWN`) |
| createdAt | string | 가입일시 (ISO 8601) |
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
  "status": "SUSPENDED"
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| status | string | Y | 변경할 계정 상태 (`ACTIVE` / `SUSPENDED` / `WITHDRAWN`) |

| status 값 | 설명 |
|-----------|------|
| `ACTIVE` | 활성으로 변경 |
| `SUSPENDED` | 정지 처리 |
| `WITHDRAWN` | 탈퇴 처리 |

#### Response

**200 OK**

```json
{
  "success": true,
  "data": {
    "id": "uuid",
    "status": "SUSPENDED",
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
      "status": "PENDING",
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

| 필드 | 타입 | 설명 |
|------|------|------|
| registerId | string | 판매자 등록 신청 ID |
| userId | string | 신청자 ID |
| nickname | string | 신청자 닉네임 |
| email | string | 신청자 이메일 |
| introduction | string \| null | 판매자 소개 |
| categories | string[] | 주력 카테고리 |
| portfolioUrl | string \| null | 포트폴리오 URL |
| status | string | 신청 상태 (`PENDING` / `APPROVED` / `REJECTED`) |
| submittedAt | string | 신청일시 (ISO 8601) |
| meta.page | integer | 현재 페이지 번호 |
| meta.size | integer | 페이지당 항목 수 |
| meta.total | integer | 전체 항목 수 |
| meta.hasNext | boolean | 다음 페이지 존재 여부 |

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
