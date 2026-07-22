# User Service — 기획문서

## 서비스 소개

User Service는 PromptHub 마켓플레이스의 **회원·인증·판매자·찜** 기능을 담당한다.

- 이메일 회원가입 / 로그인, Kakao OAuth 소셜 로그인
- JWT(Access Token + Refresh Token) 발급 및 갱신
- 회원 프로필 관리, 판매자 등록 신청 및 관리자 심사
- 찜 목록 관리

---

## 구현 현황

> `- [ ]` 미구현 &nbsp;|&nbsp; `- [x]` 구현 완료

### 인증 (Auth)

| 구현 | 메서드 | 경로 |
|------|--------|------|
| - [ ] | POST | `/auth/signup` |
| - [ ] | POST | `/auth/login` |
| - [x] | POST | `/auth/oauth/{provider}` |
| - [x] | POST | `/auth/token/refresh` |
| - [x] | POST | `/auth/logout` |

### 회원 프로필 (User)

| 구현 | 메서드 | 경로 |
|------|--------|------|
| - [x] | GET | `/users/me` |
| - [x] | PATCH | `/users/me` |
| - [x] | DELETE | `/users/me` |

### 판매자 (Seller)

| 구현 | 메서드 | 경로 |
|------|--------|------|
| - [x] | POST | `/seller/register` |
| - [ ] | GET | `/sellers/register/me` |
| - [x] | POST | `/sellers/products` |
| - [x] | POST | `/users/order-products` |
| - [x] | POST | `/sellers/wishlists` |
| - [x] | GET | `/sellers/product` |
| - [x] | GET | `/users/order-product` |

### 찜 (Wishlist)

| 구현 | 메서드 | 경로 |
|------|--------|------|
| - [x] | POST | `/wishlists` |
| - [x] | DELETE | `/wishlists/{wishlistId}` |
| - [x] | GET | `/wishlists` |
| - [x] | GET | `/wishlists/exists` |

> **관리자 API 이관**: 회원 관리(`/admin/users*`, `/admin/stats/users`)와 판매자 등록 심사
> (`/admin/sellers/register*`) 6개 엔드포인트는 admin-service로 이관 완료됐다
> (`admin-service/works/user/design.md` 참고). `com.prompthub.user.admin` 패키지는
> 삭제됨 — 이 문서에서도 제거.

### 내부 API (Internal)

> gateway forward-auth 전용. 공개 API 아님(`ApiResult` 래핑 없음). 상세: `docs/specs/2026-07-13-authorize-api-cache-design.md`

| 구현 | 메서드 | 경로 |
|------|--------|------|
| - [x] | GET | `/internal/authorize/{userId}` |

---

## 에러 코드

> 변경 시 루트 `docs/error-codes.md`도 함께 수정한다.

| enum | code | HTTP | 의미 |
|------|------|------|------|
| `VALIDATION_FAILED` | V001 | 400 | 입력값이 올바르지 않습니다. |
| `AUTH_NOT_FOUND` | A001 | 404 | 사용자가 없습니다. |
| `AUTH_INVALID_PASSWORD` | A002 | 401 | 비밀번호가 일치하지 않습니다. |
| `AUTH_TOKEN_EXPIRED` | A003 | 401 | 토큰이 만료되었습니다. |
| `AUTH_FORBIDDEN` | A004 | 403 | 권한이 없습니다. |
| `AUTH_SELLER_ALREADY_APPLIED` | A005 | 409 | 이미 신청된 판매자입니다. |
| `AUTH_INVALID_REFRESH_TOKEN` | A006 | 401 | 리프레시 토큰이 유효하지 않습니다. |
| `AUTH_EMAIL_DUPLICATED` | A007 | 409 | 이미 사용 중인 이메일입니다. |
| `AUTH_SELLER_APPLICATION_NOT_FOUND` | A008 | 404 | 판매자 등록 신청 내역이 없습니다. |
| `UNSUPPORTED_OAUTH_PROVIDER` | A009 | 400 | 지원하지 않는 OAuth 공급자입니다. |
| `AUTH_WITHDRAW_ORDER_IN_PROGRESS` | A010 | 400 | 진행 중인 주문이 있어 탈퇴할 수 없습니다. |
| `AUTH_OAUTH_VERIFICATION_FAILED` | A011 | 401 | OAuth 인증에 실패했습니다. |
| `AUTH_REFRESH_TOKEN_REUSE_DETECTED` | A012 | 401 | 리프레시 토큰 재사용이 감지되어 모든 세션이 무효화되었습니다. |
| `AUTH_SESSION_INVALIDATED` | A013 | 401 | 세션이 무효화되었습니다. 다시 로그인해주세요. |
| `WISHLIST_DUPLICATED` | W001 | 409 | 이미 찜한 상품입니다. |
| `WISHLIST_NOT_FOUND` | W002 | 404 | 찜 항목이 존재하지 않습니다. |
| `WISHLIST_FORBIDDEN` | W003 | 403 | 본인의 찜 항목이 아닙니다. |

---

## 공통 응답 포맷

```json
{
  "success": true,
  "data": { ... },
  "message": "success",
  "meta": { "page": 1, "size": 20, "total": 100, "hasNext": true }
}
```

- `meta`는 페이지네이션 응답에만 포함
- 에러 시 `success: false`, `data: null`, `message`에 에러 메시지

---

## 진행 현황 요약

- 전체: 19개
- 구현 완료: 16개
- 미구현: 3개

(관리자 API 6개는 admin-service로 이관되어 이 집계에서 제외)
