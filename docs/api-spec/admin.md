# Admin Service API

**Base:** `http://localhost:18086/api/v2`

> admin-service는 여러 서비스에 흩어져 있던 관리자(어드민) 기능이 이관되어 통합된 서비스다.
> user-service의 사용자 관리·판매자 등록 심사, order-service의 주문 관리, product-service의
> 상품 검수, settlement-service의 정산 관리 API가 모두 이 서비스에 있다.

## 공통 사항

- 인증이 필요한 엔드포인트는 `Authorization: Bearer {accessToken}` 헤더가 필요하다. 토큰 검증은 API
  Gateway에서 수행하고, admin-service는 게이트웨이가 주입하는 `X-User-Id`, `X-User-Role` 헤더만 읽는다.
- **`/api/{version}/admin/**` 전체는 API Gateway의 코드 레벨 캐치올 정책(`RoutePolicyResolver`)에
  걸려 항상 `ADMIN` 역할만 허용된다.** 이 캐치올은 `apigateway`의 `route-policies` yml 설정과
  무관하게 최우선으로 검사되므로, 이 문서의 모든 엔드포인트는 예외 없이 ADMIN 전용이다.
- 목록 조회의 페이지네이션 기준(`page`)은 엔드포인트마다 다르다 — 아래 각 절에 명시했지만 요약하면:

  | 목록 | page 기준 | 기본값 |
  |---|---|---|
  | 사용자 목록 / 판매자 신청 목록 / 상품 목록 | 0-base | `0` |
  | 주문 목록 | **1-base** | `1` |
  | 정산 목록 / 주간 정산 목록 | 0-base | `0` |

  주문 목록만 1-base라는 점에 특히 주의한다.
- 에러 응답은 공통 `ErrorResponse` 형식이며, 코드는 `AdminErrorCode`(`global/exception`) 한곳에서
  관리한다. 아래 각 엔드포인트의 에러 설명에 `(코드)` 형태로 표기한다.

---

## 어드민 홈

### GET /admin/home — 어드민 홈 통합 조회

- 인증: 필요
- 필요 역할: `ADMIN`
- 기준 시간대: `Asia/Seoul`
- 최근 7일은 오늘을 포함하며, 데이터가 없는 날짜도 `0`으로 반환
- 검수 대기 상품은 전체 건수와 오래된 순 최대 4건을 반환

기존에 화면에서 각각 조회하던 회원, 주문, 정산, 상품 데이터를 한 요청으로 조회한다.

#### Response

**200 OK**

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

**401 Unauthorized** — 인증 정보 없음

**403 Forbidden** — ADMIN 권한 없음

---

## 사용자 관리 (user-service에서 이관)

### GET /admin/users — 전체 사용자 목록 조회

- 인증: 필요
- 필요 역할: `ADMIN`
- 상태·역할·키워드 필터와 페이지네이션을 지원한다.
- ⚠ **알려진 이슈**: `page` 기본값은 문서상 관례(`1`)와 달리 실제로는 **`0`**이다
  (`@RequestParam(defaultValue = "0") int page`). 0-base로 호출해야 한다.

#### Request

**Query Parameters**

| 파라미터 | 타입 | 필수 | 기본값 | 설명 |
|---------|------|:---:|--------|------|
| status | string | N | `ALL` | 계정 상태 필터 (`active` \| `suspended` \| `withdrawn` \| `ALL`) |
| role | string | N | `ALL` | 역할 필터 (`buyer` \| `seller` \| `ALL`) |
| keyword | string | N | - | 이름·이메일 검색 키워드 |
| page | int | N | `0` | 페이지 번호 (0부터 시작) |
| size | int | N | `20` | 페이지당 항목 수 |

`status`·`role`은 대소문자를 구분하며 위 값 외 입력 시 400을 반환한다.

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
    "page": 0,
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
| meta.page | integer | 현재 페이지 번호 (0-base) |
| meta.size | integer | 페이지당 항목 수 |
| meta.total | integer | 전체 항목 수 |
| meta.hasNext | boolean | 다음 페이지 존재 여부 |

**400 Bad Request** — `page < 0` 또는 `status`/`role` 값이 허용된 목록에 없음 (`INVALID_INPUT_VALUE`, A-001)

**401 Unauthorized** — 인증 정보 없음

**403 Forbidden** — ADMIN 권한 없음

---

### GET /admin/stats/users — 회원 통계 조회

- 인증: 필요
- 필요 역할: `ADMIN`

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

**401 Unauthorized** — 인증 정보 없음

**403 Forbidden** — ADMIN 권한 없음

---

### PATCH /admin/users/{userId}/status — 사용자 상태 변경

- 인증: 필요
- 필요 역할: `ADMIN`

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
|------|------|:---:|------|
| status | string | Y | 변경할 계정 상태 (`active` / `suspended` / `withdrawn`) |

`status`가 비어 있거나(`@NotBlank`) 위 세 값 중 하나가 아니면(예: `ALL`) 400을 반환한다.
`withdrawn`으로 변경하면 세션이 즉시 폐기되고(`refresh_token` 삭제), 그 외 상태 변경은 authorize
캐시만 무효화된다.

#### Response

**200 OK**

```json
{
  "success": true,
  "data": {
    "id": "uuid",
    "status": "suspended",
    "updatedAt": "2026-06-17T10:00:00"
  },
  "message": "success"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| id | string | 사용자 ID |
| status | string | 변경된 계정 상태 |
| updatedAt | string | 변경일시 (ISO 8601) |

**400 Bad Request** — `status` 누락 또는 허용되지 않은 값 (`INVALID_INPUT_VALUE`, A-001)

**401 Unauthorized** — 인증 정보 없음

**403 Forbidden** — ADMIN 권한 없음

**404 Not Found** — 사용자를 찾을 수 없음 (`USER_NOT_FOUND`, A-007)

---

### PATCH /admin/users/{userId}/role — 사용자 역할 변경

> 신규 엔드포인트(커밋 `cfc2dec8`). 기존 user-service 시절 문서에는 없었다.

- 인증: 필요
- 필요 역할: `ADMIN`

#### Path Parameters

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| userId | UUID | 대상 사용자 ID |

#### Request

**Body**

```json
{
  "role": "seller"
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|:---:|------|
| role | string | Y | 변경할 역할 (`buyer` / `seller`) |

`role`이 비어 있거나 위 두 값 중 하나가 아니면(예: `ALL`, `admin`) 400을 반환한다.

#### Response

**200 OK**

```json
{
  "success": true,
  "data": {
    "id": "uuid",
    "role": "seller",
    "updatedAt": "2026-07-21T10:00:00"
  },
  "message": "success"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| id | string | 사용자 ID |
| role | string | 변경된 역할 (`buyer` / `seller`) |
| updatedAt | string | 변경일시 (ISO 8601) |

**400 Bad Request** — `role` 누락 또는 허용되지 않은 값 (`INVALID_INPUT_VALUE`, A-001)

**401 Unauthorized** — 인증 정보 없음

**403 Forbidden** — ADMIN 권한 없음

**404 Not Found** — 사용자를 찾을 수 없음 (`USER_NOT_FOUND`, A-007)

---

## 판매자 등록 심사 (user-service에서 이관)

### GET /admin/sellers/register — 판매자 신청 목록 조회

- 인증: 필요
- 필요 역할: `ADMIN`
- 상태 필터와 페이지네이션을 지원한다. `page` 기본값은 `0`이다(User 목록과 동일 계약).

#### Request

**Query Parameters**

| 파라미터 | 타입 | 필수 | 기본값 | 설명 |
|---------|------|:---:|--------|------|
| status | string | N | `ALL` | 신청 상태 필터 (`PENDING` \| `APPROVED` \| `REJECTED` \| `ALL`, 대소문자 무관) |
| page | int | N | `0` | 페이지 번호 (0부터 시작) |
| size | int | N | `20` | 페이지당 항목 수 |

#### Response

**200 OK**

```json
{
  "success": true,
  "data": [
    {
      "registerId": "uuid",
      "userId": "uuid",
      "name": "이서아",
      "email": "seoah@example.com",
      "introduction": "미드저니·DALL·E 기반 제품 목업과 광고 컷 프롬프트를 전문으로 제작합니다.",
      "categories": ["이미지 생성"],
      "portfolioUrl": "https://blog.example.com",
      "status": "pending",
      "submittedAt": "2026-06-14T00:00:00"
    }
  ],
  "message": "success",
  "meta": {
    "page": 0,
    "size": 20,
    "total": 6,
    "hasNext": false
  }
}
```

| 필드 | 타입 | 설명 |
|--------------|------|---------------------------------------------|
| registerId   | string | 판매자 등록 신청 ID |
| userId       | string | 신청자 ID |
| name         | string | 신청자 이름 — ⚠ **알려진 이슈**: 필드명이 `nickname`이 아니라 `name`이다 |
| email        | string | 신청자 이메일 |
| introduction | string \| null | 판매자 소개 |
| categories   | string[] | 주력 카테고리 |
| portfolioUrl | string \| null | 포트폴리오 URL |
| status       | string | 신청 상태 (`pending` / `approved` / `rejected`) |
| submittedAt  | string | 신청일시 (ISO 8601) |
| meta.page    | integer | 현재 페이지 번호 (0-base) |
| meta.size    | integer | 페이지당 항목 수 |
| meta.total   | integer | 전체 항목 수 |
| meta.hasNext | boolean | 다음 페이지 존재 여부 |

**401 Unauthorized** — 인증 정보 없음

**403 Forbidden** — ADMIN 권한 없음

---

### PATCH /admin/sellers/register/{registerId}/approve — 판매자 신청 승인

- 인증: 필요
- 필요 역할: `ADMIN`
- 승인 시 대상 사용자에게 `SELLER` 역할이 추가되고 authorize 캐시가 무효화된다. 요청 본문 없음.

#### Path Parameters

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| registerId | UUID | 판매자 등록 신청 ID |

#### Response

**200 OK**

```json
{
  "success": true,
  "data": {
    "registerId": "uuid",
    "userId": "uuid",
    "status": "approved",
    "rejectReason": null,
    "reviewedAt": "2026-06-17T10:00:00"
  },
  "message": "success"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| registerId | string | 판매자 등록 신청 ID |
| userId | string | 승인된 사용자 ID |
| status | string | 처리 상태 (`approved`) |
| rejectReason | string \| null | 승인 시 항상 `null` |
| reviewedAt | string | 심사 완료일시 (ISO 8601) |

**400 Bad Request** — 이미 심사된 신청(`PENDING`이 아님) (`INVALID_INPUT_VALUE`, A-001)

**401 Unauthorized** — 인증 정보 없음

**403 Forbidden** — ADMIN 권한 없음

**404 Not Found** — 신청 내역을 찾을 수 없음 (`SELLER_REGISTER_NOT_FOUND`, A-008)

---

### PATCH /admin/sellers/register/{registerId}/reject — 판매자 신청 반려

- 인증: 필요
- 필요 역할: `ADMIN`

#### Path Parameters

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| registerId | UUID | 판매자 등록 신청 ID |

#### Request

**Body**

```json
{
  "rejectReason": "포트폴리오가 확인되지 않습니다. 샘플을 보완 후 재신청해 주세요."
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|:---:|------|
| rejectReason | string | Y | 반려 사유 |

#### Response

**200 OK**

```json
{
  "success": true,
  "data": {
    "registerId": "uuid",
    "userId": "uuid",
    "status": "rejected",
    "rejectReason": "포트폴리오가 확인되지 않습니다. 샘플을 보완 후 재신청해 주세요.",
    "reviewedAt": "2026-06-17T10:05:00"
  },
  "message": "success"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| registerId | string | 판매자 등록 신청 ID |
| userId | string | 대상 사용자 ID |
| status | string | 처리 상태 (`rejected`) |
| rejectReason | string | 반려 사유 |
| reviewedAt | string | 심사 완료일시 (ISO 8601) |

**400 Bad Request** — `rejectReason` 누락, 또는 이미 심사된 신청(`PENDING`이 아님) (`INVALID_INPUT_VALUE`, A-001)

**401 Unauthorized** — 인증 정보 없음

**403 Forbidden** — ADMIN 권한 없음

**404 Not Found** — 신청 내역을 찾을 수 없음 (`SELLER_REGISTER_NOT_FOUND`, A-008)

---

## 주문 관리 (order-service에서 이관)

### GET /admin/orders — 전체 주문 목록 조회

- 인증: 필요
- 필요 역할: `ADMIN`
- 주문별 구매자 정보와 주문 상품 목록을 함께 반환한다.
- ⚠ **이 목록만 `page`가 1-base다.** `page` 생략 시 `1`, 최솟값도 `1`이다(0 이하는 400).
  내부적으로 `page - 1`을 실제 페이지 인덱스로 사용하고, 응답 `meta.page`에는 **요청받은 값
  그대로(1-base)** 를 되돌려준다 — 다른 admin 목록(0-base)과 `meta.page` 해석 기준이 다르다.

#### Request

**Query Parameters**

| 파라미터 | 타입 | 필수 | 기본값 | 설명 |
|---------|------|:---:|--------|------|
| orderStatus | string | N | `ALL` | 주문 상태 필터 (`CREATED` \| `COMPLETED` \| `FAILED` \| `PARTIAL_REFUNDED` \| `ALL_REFUNDED` \| `ALL`) |
| page | int | N | `1` | 페이지 번호. **1부터 시작**, 1 미만이면 400 |
| size | int | N | `20` | 페이지당 항목 수. 1~100, 범위 밖이면 400 |

#### Response

**200 OK**

```json
{
  "success": true,
  "data": [
    {
      "orderNumber": "ORD-20260724-0001",
      "buyer": {
        "userId": "9f1c2a7e-4b8d-4e2a-9c11-2d3e4f5a1111",
        "name": "prompt-user",
        "profileImageUrl": "https://cdn.example.com/profile.png"
      },
      "totalOrderAmount": 15000,
      "orderStatus": "COMPLETED",
      "orderedAt": "2026-06-24T10:00:00",
      "orderProducts": [
        {
          "seller": {
            "userId": "s1b55b60-5e84-4f3f-b4f1-6c10e1a33333",
            "name": "판매자닉네임",
            "profileImageUrl": null
          },
          "productTitle": "면접 답변 프롬프트",
          "productAmount": 15000,
          "orderProductStatus": "PAID"
        }
      ]
    }
  ],
  "message": "success",
  "meta": {
    "page": 1,
    "size": 20,
    "total": 42,
    "hasNext": true
  }
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| orderNumber | string | 주문 번호 |
| buyer | object | 구매자 요약 정보 |
| buyer.userId | string(UUID) | 구매자 ID |
| buyer.name | string | 구매자 이름 — 프로필 조회 실패 시 `"알 수 없음"` |
| buyer.profileImageUrl | string \| null | 프로필 이미지 URL |
| totalOrderAmount | integer | 총 주문 금액 |
| orderStatus | string | 주문 상태 (`CREATED` / `COMPLETED` / `FAILED` / `PARTIAL_REFUNDED` / `ALL_REFUNDED`) |
| orderedAt | string | 주문 일시 (ISO 8601) |
| orderProducts | array | 주문 상품 목록 |
| orderProducts[].seller | object | 판매자 요약 정보 (구조는 `buyer`와 동일) |
| orderProducts[].productTitle | string | 상품 제목 |
| orderProducts[].productAmount | integer | 상품 주문 금액 |
| orderProducts[].orderProductStatus | string | 주문 상품 상태 (`PENDING` / `PAID` / `FAILED` / `REFUND_REQUESTED` / `REFUNDED`) |
| meta.page | integer | 요청받은 페이지 번호 (1-base, 요청값 그대로 echo) |
| meta.size | integer | 페이지당 항목 수 |
| meta.total | integer | 전체 항목 수 |
| meta.hasNext | boolean | 다음 페이지 존재 여부 |

**400 Bad Request** — `orderStatus` 값이 허용된 목록에 없음, `page < 1`, 또는 `size`가 1~100 범위 밖 (`INVALID_INPUT_VALUE`, A-001)

**401 Unauthorized** — 인증 정보 없음

**403 Forbidden** — ADMIN 권한 없음

---

### GET /admin/orders/month — 이번 달 실제 거래액 조회

- 인증: 필요
- 필요 역할: `ADMIN`
- 이번 달(1일 00:00 ~ 현재) 결제 승인 금액에서 같은 기간 취소/환불 금액을 차감한 실제 거래액을 반환한다.

#### Response

**200 OK**

```json
{
  "success": true,
  "data": {
    "monthlyTransactionAmount": 1250000
  },
  "message": "success"
}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| monthlyTransactionAmount | integer | 이번 달 실제 거래액 |

**401 Unauthorized** — 인증 정보 없음

**403 Forbidden** — ADMIN 권한 없음

---

### GET /admin/orders/weekend — 최근 7일 거래량 조회

- 인증: 필요
- 필요 역할: `ADMIN`
- 오늘을 포함한 최근 7일의 일자별 결제 승인 건수·실제 거래액을 반환한다. 데이터가 없는 날짜도 `0`으로 채워진다.

#### Response

**200 OK**

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

| 필드 | 타입 | 설명 |
|---|---|---|
| totalTransactionCount | integer | 최근 7일 결제 승인 완료 주문 수 |
| totalTransactionAmount | integer | 최근 7일 실제 거래액 |
| period.startDate | string | 조회 시작일 |
| period.endDate | string | 조회 종료일 |
| dailyTransactions[].date | string | 거래 일자 |
| dailyTransactions[].transactionCount | integer | 해당일 결제 승인 완료 주문 수 |
| dailyTransactions[].transactionAmount | integer | 해당일 실제 거래액 |

**401 Unauthorized** — 인증 정보 없음

**403 Forbidden** — ADMIN 권한 없음

---

## 상품 검수 (product-service에서 이관)

### GET /admin/products — 상품 목록 조회

- 인증: 필요
- 필요 역할: `ADMIN`
- 정렬은 `createdAt` 내림차순으로 고정이며, 클라이언트가 정렬 기준을 바꿀 수 없다.

#### Request

**Query Parameters**

| 파라미터 | 타입 | 필수 | 기본값 | 설명 |
|---------|------|:---:|--------|------|
| status | string | N | `ALL` | 검수 상태 필터. **소문자+언더스코어**(`pending_review` \| `on_sale` \| `rejected` \| `ALL`), 대소문자 구분 |
| keyword | string | N | - | 상품명 또는 판매자 이름 검색 키워드 |
| page | int | N | `0` | 페이지 번호 (0부터 시작) |
| size | int | N | `20` | 페이지당 항목 수 |

#### Response

**200 OK**

```json
{
  "success": true,
  "data": [
    {
      "productId": "8cb888c3-bcdc-4458-885b-ec7281ec3ef0",
      "title": "면접 답변 프롬프트",
      "sellerNickname": "판매자닉네임",
      "productType": "PROMPT",
      "model": "GPT-4",
      "amount": 15000,
      "status": "PENDING_REVIEW",
      "rejectionReason": null,
      "createdAt": "2026-07-20T10:00:00"
    }
  ],
  "message": "success",
  "meta": {
    "page": 0,
    "size": 20,
    "total": 12,
    "hasNext": false
  }
}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| productId | string(UUID) | 상품 ID |
| title | string | 상품명 |
| sellerNickname | string | 판매자 이름 — 조회 실패 시 `"알 수 없음"` |
| productType | string | 상품 타입 |
| model | string | AI 모델 |
| amount | integer | 가격 |
| status | string | 상품 상태(원본 enum 값 그대로, 예: `PENDING_REVIEW` / `ON_SALE` / `REJECTED`) |
| rejectionReason | string \| null | 반려 사유 — 반려 상태가 아니면 `null` |
| createdAt | string | 등록일시 (ISO 8601) |
| meta.page | integer | 현재 페이지 번호 (0-base) |
| meta.size | integer | 페이지당 항목 수 |
| meta.total | integer | 전체 항목 수 |
| meta.hasNext | boolean | 다음 페이지 존재 여부 |

**400 Bad Request** — `page < 0`, 또는 `status` 값이 허용된 목록에 없음 (`INVALID_INPUT_VALUE`, A-001)

**401 Unauthorized** — 인증 정보 없음

**403 Forbidden** — ADMIN 권한 없음

---

### PATCH /admin/products/{productId}/approve — 상품 승인

- 인증: 필요
- 필요 역할: `ADMIN`
- 검수 대기(`PENDING_REVIEW`) 상태의 상품만 승인할 수 있다. 승인 시 같은 상품 계열(family)의
  기존 판매중(`ON_SALE`) 버전이 있으면 `SUPERSEDED`로 전환한다. 요청 본문 없음.

#### Path Parameters

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| productId | UUID | 대상 상품 ID |

#### Response

**200 OK** — 응답 `data`는 `null`

```json
{
  "success": true,
  "data": null,
  "message": "success"
}
```

**401 Unauthorized** — 인증 정보 없음

**403 Forbidden** — ADMIN 권한 없음

**404 Not Found** — 상품을 찾을 수 없음 (`PRODUCT_NOT_FOUND`, A-009)

**409 Conflict** — 검수 대기 상태가 아님 (`PRODUCT_INVALID_STATUS`, A-010)

---

### PATCH /admin/products/{productId}/reject — 상품 반려

- 인증: 필요
- 필요 역할: `ADMIN`
- 검수 대기(`PENDING_REVIEW`) 상태의 상품만 반려할 수 있다.

#### Path Parameters

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| productId | UUID | 대상 상품 ID |

#### Request

**Body**

```json
{
  "reason": "가이드라인 위반 콘텐츠가 포함되어 있습니다."
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|:---:|------|
| reason | string | Y | 반려 사유 |

#### Response

**200 OK** — 응답 `data`는 `null`

```json
{
  "success": true,
  "data": null,
  "message": "success"
}
```

**400 Bad Request** — `reason` 누락 (`INVALID_INPUT_VALUE`, A-001)

**401 Unauthorized** — 인증 정보 없음

**403 Forbidden** — ADMIN 권한 없음

**404 Not Found** — 상품을 찾을 수 없음 (`PRODUCT_NOT_FOUND`, A-009)

**409 Conflict** — 검수 대기 상태가 아님 (`PRODUCT_INVALID_STATUS`, A-010)

---

### PATCH /admin/products/{productId}/revert — 검수 상태 되돌리기

> 신규 엔드포인트. 승인·반려 처리를 실수로 했을 때 다시 검수 대기 상태로 되돌리는 용도다.

- 인증: 필요
- 필요 역할: `ADMIN`
- 판매중(`ON_SALE`) 또는 반려(`REJECTED`) 상태만 되돌릴 수 있다. `PENDING_REVIEW`로 전환되며
  `rejectionReason`은 초기화된다. 되돌리는 상품이 `ON_SALE`이었다면, 승인 시 `SUPERSEDED`로
  전환됐던 이전 버전이 있는 경우 그 버전을 다시 `ON_SALE`로 복원한다. 요청 본문 없음.

#### Path Parameters

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| productId | UUID | 대상 상품 ID |

#### Response

**200 OK** — 응답 `data`는 `null`

```json
{
  "success": true,
  "data": null,
  "message": "success"
}
```

**401 Unauthorized** — 인증 정보 없음

**403 Forbidden** — ADMIN 권한 없음

**404 Not Found** — 상품을 찾을 수 없음 (`PRODUCT_NOT_FOUND`, A-009)

**409 Conflict** — 판매중/반려 상태가 아님 (`PRODUCT_INVALID_STATUS`, A-010)

---

## 정산 관리 (settlement-service에서 이관)

> 정산 표시 상태(`SettlementDisplayStatus`)는 7가지다: `WAITING`(대기) → `APPROVED`(승인) 또는
> `APPROVAL_ON_HOLD`(승인 보류) → `PAYOUT_REQUESTED`(지급 신청) → `PAID`(지급 완료) 또는
> `PAYOUT_ON_HOLD`(지급 보류). `PAID`를 제외한 모든 상태에서 `CANCELLED`(취소)로 전환할 수 있다.
> 아래 상태 변경 엔드포인트들은 이 상태 값을 기준으로 전이 가능 여부를 검증한다.

### GET /admin/settlements/summary — 정산 요약 카드 조회

- 인증: 필요
- 필요 역할: `ADMIN`
- 정산 관리 화면 상단 요약 카드(상태별 지급액 합계·건수)를 조회한다.
- 카드는 `WAITING`(`WAITING`+`APPROVAL_ON_HOLD` 합산), `APPROVED`(`APPROVED`+`PAYOUT_REQUESTED`
  합산), `PAYOUT_ON_HOLD`, `PAID` 4개 버킷이다. `CANCELLED`는 요약 카드에 집계되지 않는다.

#### Request

**Query Parameters**

| 파라미터 | 타입 | 필수 | 설명 |
|---------|------|:---:|------|
| settlementMonth | string(YYYY-MM) | N | 정산 월. 미지정 시 전체 |

#### Response

**200 OK**

```json
{
  "success": true,
  "data": {
    "cards": [
      { "status": "WAITING", "totalAmount": 1135500.00, "count": 4 },
      { "status": "APPROVED", "totalAmount": 2200000.00, "count": 3 },
      { "status": "PAYOUT_ON_HOLD", "totalAmount": 330000.00, "count": 1 },
      { "status": "PAID", "totalAmount": 5000000.00, "count": 10 }
    ]
  },
  "message": "success"
}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| cards[].status | string | 표시 상태 (`WAITING` / `APPROVED` / `PAYOUT_ON_HOLD` / `PAID`) |
| cards[].totalAmount | number | 지급액 합계 |
| cards[].count | integer | 건수 |

**400 Bad Request** — `settlementMonth` 형식 오류 (`INVALID_INPUT_VALUE`, A-001)

**401 Unauthorized** — 인증 정보 없음

**403 Forbidden** — ADMIN 권한 없음

---

### GET /admin/settlements — 판매자 월별 정산 목록 조회

- 인증: 필요
- 필요 역할: `ADMIN`
- 정산을 **판매자 × 정산월** 단위로 집계해 조회한다. 개별 주간 정산 건은 `GET /admin/settlements/weeks`를 쓴다.
- ⚠ 이 응답은 공통 `PageResponse` 봉투(`meta`)가 아니라, `ApiResult` 안에 페이지 필드
  (`page`/`size`/`totalElements`)가 직접 들어간다. `hasNext` 필드는 없다.

#### Request

**Query Parameters**

| 파라미터 | 타입 | 필수 | 기본값 | 설명 |
|---------|------|:---:|--------|------|
| status | string | N | 전체 | 표시 상태 필터 (`WAITING` \| `APPROVAL_ON_HOLD` \| `APPROVED` \| `PAYOUT_REQUESTED` \| `PAYOUT_ON_HOLD` \| `PAID` \| `CANCELLED`) |
| settlementMonth | string(YYYY-MM) | N | 전체 | 정산 월 |
| page | int | N | `0` | 페이지 번호 (0부터 시작, 0 미만 400) |
| size | int | N | `20` | 페이지당 항목 수 (1~100, 범위 밖 400) |

#### Response

**200 OK**

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "sellerId": "3f1b1b0e-1111-2222-3333-444444444444",
        "sellerName": "김철수",
        "settlementMonth": "2026-07",
        "weeklySettlementCount": 4,
        "aggregatedSettlementCount": 3,
        "salesCount": 22,
        "grossAmount": 2200000.00,
        "feeAmount": 330000.00,
        "refundAmount": 100000.00,
        "payoutAmount": 1770000.00,
        "statusCounts": [
          { "status": "APPROVED", "statusLabel": "승인", "count": 2 },
          { "status": "PAID", "statusLabel": "지급 완료", "count": 1 }
        ]
      }
    ],
    "totalElements": 16,
    "page": 0,
    "size": 20
  },
  "message": "success"
}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| items[].sellerId | string(UUID) | 판매자 ID |
| items[].sellerName | string \| null | 판매자명 — 조회 실패 시 `null` |
| items[].settlementMonth | string | 정산 월(YYYY-MM) |
| items[].weeklySettlementCount | integer | 월에 포함된 전체 주간 정산 건수 |
| items[].aggregatedSettlementCount | integer | 합계에 반영된 비취소 주간 정산 건수 |
| items[].salesCount | integer | 비취소 판매 건수 합계 |
| items[].grossAmount | number | 비취소 총 거래액 |
| items[].feeAmount | number | 비취소 판매 수수료 |
| items[].refundAmount | number | 비취소 환불 차감액 |
| items[].payoutAmount | number | 비취소 지급 예정/완료 금액 |
| items[].statusCounts[].status | string | 주간 정산 표시 상태 코드 |
| items[].statusCounts[].statusLabel | string | 표시 상태 한글 라벨 |
| items[].statusCounts[].count | integer | 해당 상태 건수 |
| totalElements | integer | 전체 판매자-월 그룹 수 |
| page | integer | 현재 페이지 번호 (0-base) |
| size | integer | 페이지 크기 |

**400 Bad Request** — `page`/`size` 범위 오류, `settlementMonth` 형식 오류 (`INVALID_INPUT_VALUE`, A-001)

**401 Unauthorized** — 인증 정보 없음

**403 Forbidden** — ADMIN 권한 없음

---

### GET /admin/settlements/weeks — 주간 정산 목록 조회

> 신규 엔드포인트.

- 인증: 필요
- 필요 역할: `ADMIN`
- 개별 주간 정산 건을 상태·정산 월로 필터링해 조회한다. 상태 필터는 (월별 집계가 아니라) 주간
  정산 항목에 직접 적용된다.
- `GET /admin/settlements`와 마찬가지로 `ApiResult` 안에 페이지 필드가 직접 들어간다.

#### Request

**Query Parameters**

| 파라미터 | 타입 | 필수 | 기본값 | 설명 |
|---------|------|:---:|--------|------|
| status | string | N | 전체 | 주간 정산 표시 상태 필터 (7개 값 — 위 공통 설명 참고) |
| settlementMonth | string(YYYY-MM) | N | 전체 | 정산 월 |
| page | int | N | `0` | 페이지 번호 (0부터 시작, 0 미만 400) |
| size | int | N | `20` | 페이지당 항목 수 (1~100, 범위 밖 400) |

#### Response

**200 OK**

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "sellerId": "3f1b1b0e-1111-2222-3333-444444444444",
        "sellerName": "김철수",
        "settlementId": "550e8400-e29b-41d4-a716-446655440000",
        "periodStart": "2026-06-29",
        "periodEnd": "2026-07-05",
        "salesCount": 22,
        "grossAmount": 2200000.00,
        "feeAmount": 330000.00,
        "refundAmount": 100000.00,
        "payoutAmount": 1770000.00,
        "status": "APPROVED",
        "statusLabel": "승인",
        "calculatedAt": "2026-07-06T02:00:00",
        "approvedAt": "2026-07-06T09:00:00",
        "payoutRequestedAt": null,
        "paidAt": null,
        "cancelledAt": null,
        "availableActions": [
          { "type": "CANCEL", "label": "정산 취소" }
        ]
      }
    ],
    "statusCounts": [
      { "status": "WAITING", "statusLabel": "대기", "count": 0 },
      { "status": "APPROVAL_ON_HOLD", "statusLabel": "승인 보류", "count": 0 },
      { "status": "APPROVED", "statusLabel": "승인", "count": 1 },
      { "status": "PAYOUT_REQUESTED", "statusLabel": "지급 신청", "count": 0 },
      { "status": "PAYOUT_ON_HOLD", "statusLabel": "지급 보류", "count": 0 },
      { "status": "PAID", "statusLabel": "지급 완료", "count": 0 },
      { "status": "CANCELLED", "statusLabel": "취소", "count": 0 }
    ],
    "totalElements": 16,
    "page": 0,
    "size": 20
  },
  "message": "success"
}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| items[].sellerId / sellerName | string / string\|null | 판매자 정보 |
| items[].settlementId | string(UUID) | 정산 ID |
| items[].periodStart / periodEnd | string | 정산 기간 시작/종료일 |
| items[].salesCount | integer | 판매 건수 |
| items[].grossAmount / feeAmount / refundAmount / payoutAmount | number | 총 거래액 / 수수료 / 환불 차감액 / 지급액 |
| items[].status / statusLabel | string | 표시 상태 코드/라벨 |
| items[].calculatedAt / approvedAt / payoutRequestedAt / paidAt / cancelledAt | string \| null | 단계별 시각. 아직 도달하지 않은 단계는 `null` |
| items[].availableActions[] | array | 현재 상태에서 수행 가능한 액션(`type`, `label`) |
| statusCounts[] | array | **7개 상태 전부** 항상 포함(0건도 표시) |
| totalElements | integer | 전체 주간 정산 건수 |
| page / size | integer | 페이지 번호(0-base) / 크기 |

**400 Bad Request** — `page`/`size` 범위 오류, `settlementMonth` 형식 오류 (`INVALID_INPUT_VALUE`, A-001)

**401 Unauthorized** — 인증 정보 없음

**403 Forbidden** — ADMIN 권한 없음

---

### GET /admin/settlements/sellers/{sellerId}/months/{settlementMonth} — 판매자 월별 정산 상세 조회

> 신규 엔드포인트.

- 인증: 필요
- 필요 역할: `ADMIN`
- 특정 판매자·정산월의 집계와 그 안에 포함된 주간 정산 목록(+ 가능한 액션)을 함께 조회한다.

#### Path Parameters

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| sellerId | UUID | 판매자 ID |
| settlementMonth | string(YYYY-MM) | 정산 월 |

#### Response

**200 OK**

```json
{
  "success": true,
  "data": {
    "sellerId": "3f1b1b0e-1111-2222-3333-444444444444",
    "sellerName": "김철수",
    "settlementMonth": "2026-07",
    "weeklySettlementCount": 4,
    "aggregatedSettlementCount": 3,
    "salesCount": 22,
    "grossAmount": 2200000.00,
    "feeAmount": 330000.00,
    "refundAmount": 100000.00,
    "payoutAmount": 1770000.00,
    "statusCounts": [
      { "status": "APPROVED", "statusLabel": "승인", "count": 2 },
      { "status": "PAID", "statusLabel": "지급 완료", "count": 1 }
    ],
    "weeklySettlements": [
      {
        "settlementId": "550e8400-e29b-41d4-a716-446655440000",
        "periodStart": "2026-06-29",
        "periodEnd": "2026-07-05",
        "salesCount": 22,
        "grossAmount": 2200000.00,
        "feeAmount": 330000.00,
        "refundAmount": 100000.00,
        "payoutAmount": 1770000.00,
        "status": "APPROVED",
        "statusLabel": "승인",
        "calculatedAt": "2026-07-06T02:00:00",
        "approvedAt": "2026-07-06T09:00:00",
        "payoutRequestedAt": null,
        "paidAt": null,
        "cancelledAt": null,
        "availableActions": [
          { "type": "CANCEL", "label": "정산 취소" }
        ]
      }
    ]
  },
  "message": "success"
}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| sellerId / sellerName | string / string\|null | 판매자 정보 |
| settlementMonth | string | 정산 월(YYYY-MM) |
| weeklySettlementCount | integer | 월에 포함된 전체 주간 정산 건수 |
| aggregatedSettlementCount | integer | 합계에 반영된 비취소 주간 정산 건수 |
| salesCount / grossAmount / feeAmount / refundAmount / payoutAmount | integer/number | 비취소 집계 값 |
| statusCounts[] | array | 이 판매자-월의 주간 정산 상태별 건수 |
| weeklySettlements[] | array | 기간 시작일 오름차순 주간 정산 목록. 필드 구조는 `GET /admin/settlements/weeks`의 `items[]`와 동일(단, `sellerId`/`sellerName` 없음) |

**400 Bad Request** — `settlementMonth` 형식 오류 (`INVALID_INPUT_VALUE`, A-001)

**401 Unauthorized** — 인증 정보 없음

**403 Forbidden** — ADMIN 권한 없음

**404 Not Found** — 해당 판매자·정산월 집계 없음 (`SETTLEMENT_NOT_FOUND`, A-003)

---

### 정산 상태 변경 엔드포인트

아래 7개 엔드포인트는 모두 같은 모양이다: 경로에 `{settlementId}`(UUID)만 받고, 요청 본문은 없으며,
`X-User-Id` 헤더(요청 수행자, actorId — 로그 기록용)를 요구한다. **`X-User-Id`는 게이트웨이가 인증된
요청에 자동 주입하므로 클라이언트가 별도로 설정할 필요는 없다.**

`cancel`을 제외한 6개는 `SettlementStatusResponse`를 반환하며, 이 DTO는 `null` 필드를 JSON에서
생략한다(`@JsonInclude(NON_NULL)`) — 응답 예시의 필드는 값이 있을 때만 나타난다.

| 엔드포인트 | 요구 선행 상태 | 전이 후 상태 |
|---|---|---|
| `PATCH /{settlementId}/approve` | `WAITING` | `APPROVED` (`approvedAt` 기록) |
| `PATCH /{settlementId}/hold` | `WAITING` | `APPROVAL_ON_HOLD` |
| `PATCH /{settlementId}/release-hold` | `APPROVAL_ON_HOLD` | `WAITING` |
| `PATCH /{settlementId}/payout` | `PAYOUT_REQUESTED` | `PAID` (`paidAt` 기록) |
| `PATCH /{settlementId}/payout-hold` | `PAYOUT_REQUESTED` | `PAYOUT_ON_HOLD` |
| `PATCH /{settlementId}/payout-hold/release` | `PAYOUT_ON_HOLD` | `PAYOUT_REQUESTED` |

선행 상태가 아닌 상태에서 호출하면 409(`SETTLEMENT_INVALID_STATE`, A-004)를 반환한다.

#### Request

**Path Parameters**

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| settlementId | UUID | 정산 ID |

**Headers**

| 헤더 | 필수 | 설명 |
|------|:---:|------|
| `X-User-Id` | Y | 게이트웨이가 주입하는 관리자 ID. 실행자(actorId)로 기록(응답에는 포함 안 됨) |

#### Response — approve 예시

**200 OK**

```json
{
  "success": true,
  "data": {
    "settlementId": "550e8400-e29b-41d4-a716-446655440000",
    "displayStatus": "APPROVED",
    "approvedAt": "2026-06-24T09:00:00",
    "updatedAt": "2026-06-24T09:00:00"
  },
  "message": "success"
}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| settlementId | string(UUID) | 정산 ID |
| displayStatus | string | 변경된 표시 상태 |
| approvedAt | string \| 생략 | 승인 시각 — 값이 있을 때만 포함 |
| paidAt | string \| 생략 | 지급 완료 시각 — 값이 있을 때만 포함 |
| cancelledAt | string \| 생략 | 취소 시각 — 값이 있을 때만 포함 |
| updatedAt | string | 최종 수정 시각 |

**401 Unauthorized** — 인증 정보 없음

**403 Forbidden** — ADMIN 권한 없음

**404 Not Found** — 정산을 찾을 수 없음 (`SETTLEMENT_NOT_FOUND`, A-003)

**409 Conflict** — 위 표의 요구 선행 상태가 아님 (`SETTLEMENT_INVALID_STATE`, A-004)

---

### PATCH /admin/settlements/{settlementId}/cancel — 정산 취소

- 인증: 필요
- 필요 역할: `ADMIN`
- **지급 완료(`PAID`) 전 어떤 상태에서도 취소할 수 있다** (`WAITING`/`APPROVAL_ON_HOLD`/`APPROVED`/
  `PAYOUT_REQUESTED`/`PAYOUT_ON_HOLD` 모두 취소 가능 — 위 6개 엔드포인트처럼 특정 선행 상태 하나만
  요구하지 않는다). 취소 시 이 정산에 묶여 있던 소스 라인을 풀어 재정산 대상으로 되돌린다.
- 다른 상태 변경 엔드포인트와 달리 **`SettlementResponse`**(다른 필드 구성)를 반환한다.

#### Request

**Path Parameters**

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| settlementId | UUID | 정산 ID |

**Headers**

| 헤더 | 필수 | 설명 |
|------|:---:|------|
| `X-User-Id` | Y | 게이트웨이가 주입하는 관리자 ID. 실행자(actorId)로 기록(응답에는 포함 안 됨) |

#### Response

**200 OK**

```json
{
  "success": true,
  "data": {
    "settlementId": "550e8400-e29b-41d4-a716-446655440000",
    "sellerId": "3f1b1b0e-1111-2222-3333-444444444444",
    "displayStatus": "CANCELLED",
    "cancelledAt": "2026-06-24T09:00:00"
  },
  "message": "success"
}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| settlementId | string(UUID) | 정산 ID |
| sellerId | string(UUID) | 판매자 ID |
| displayStatus | string | 변경된 표시 상태 (`CANCELLED`) |
| cancelledAt | string \| null | 취소 시각 |

**401 Unauthorized** — 인증 정보 없음

**403 Forbidden** — ADMIN 권한 없음

**404 Not Found** — 정산을 찾을 수 없음 (`SETTLEMENT_NOT_FOUND`, A-003)

**409 Conflict** — 이미 지급 완료됨 (`SETTLEMENT_ALREADY_PAID`, A-005) 또는 이미 취소됨 (`SETTLEMENT_ALREADY_CANCELLED`, A-006)
