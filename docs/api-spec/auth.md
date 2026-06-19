# Auth API

**Base:** `http://localhost:xxxx/api/v1`

## 공통 사항

- 인증이 필요한 엔드포인트는 `Authorization: Bearer {accessToken}` 헤더 필요
- 토큰 검증은 API Gateway에서 수행. 각 서비스는 헤더(`X-User-Id`, `X-User-Role`)만 읽음

---

## 인증

### POST /auth/signup — 이메일 회원가입

- UC: UC-AUTH-01
- 인증: 불필요
- 필요 역할: 없음

#### Request

**Body**

```json
{
  "name": "홍길동",
  "email": "user@example.com",
  "password": "password123",
  "serviceAgree": true
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| name | string | Y | 이름 |
| email | string | Y | 이메일 |
| password | string | Y | 비밀번호 |
| serviceAgree | boolean | Y | 서비스 이용약관 동의 여부 |

#### Response

**201 Created**

```json
{
  "success": true,
  "data": {
    "userId": "uuid",
    "email": "user@example.com",
    "nickname": "민서",
    "role": "BUYER"
  },
  "message": "success"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| userId | string | 생성된 사용자 ID |
| email | string | 이메일 |
| nickname | string | 닉네임 |
| role | string | 기본 역할 (`BUYER`) |

---

### POST /auth/login — 이메일 로그인

- UC: UC-AUTH-02
- 인증: 불필요
- 필요 역할: 없음
- 응답으로 AT(Access Token) / RT(Refresh Token) 발급

#### Request

**Body**

```json
{
  "email": "user@example.com",
  "password": "password123"
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| email | string | Y | 이메일 |
| password | string | Y | 비밀번호 |

#### Response

**200 OK**

```json
{
  "success": true,
  "data": {
    "accessToken": "eyJhbGci...",
    "refreshToken": "eyJhbGci...",
    "tokenType": "Bearer",
    "expiresAt": "2025-06-17T11:00:00Z"
  },
  "message": "success"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| accessToken | string | JWT 액세스 토큰 |
| refreshToken | string | JWT 리프레시 토큰 |
| tokenType | string | 토큰 타입 (`Bearer`) |
| expiresAt | string | 액세스 토큰 만료일시 (ISO 8601) |

---

### POST /auth/oauth/{provider} — OAuth 소셜 로그인

- UC: UC-AUTH-02b
- 인증: 불필요
- 필요 역할: 없음
- 최초 로그인 시 자동 회원가입 처리
- 현재 지원 provider: `kakao`

#### Kakao OAuth 플로우

```
버튼 클릭
  → Kakao 인증 페이지 (아래 URL로 리다이렉트)
  → /auth/kakao/callback?code=... 콜백
  → 이 API 호출
  → 로그인 완료
```

**Kakao 인증 URL**

```
https://kauth.kakao.com/oauth/authorize
  ?client_id={KAKAO_CLIENT_ID}
  &redirect_uri={origin}/auth/kakao/callback
  &response_type=code
```

#### Path Parameters

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| provider | string | OAuth 제공자 (`kakao`) |

#### Request

**Body**

```json
{
  "code": "authorization-code-from-kakao"
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| code | string | Y | 카카오 OAuth 인가코드 |

#### Response

**200 OK**

```json
{
  "success": true,
  "data": {
    "user": {
      "id": "uuid",
      "name": "카카오사용자",
      "email": "kakao@user.com",
      "role": "BUYER"
    },
    "accessToken": "eyJhbGci...",
    "refreshToken": "eyJhbGci...",
    "tokenType": "Bearer",
    "expiresAt": "2025-06-17T11:00:00Z",
    "isNewUser": false
  },
  "message": "success"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| user.id | string | 사용자 ID |
| user.name | string | 이름 |
| user.email | string | 이메일 |
| user.role | string | 역할 |
| accessToken | string | JWT 액세스 토큰 |
| refreshToken | string | JWT 리프레시 토큰 |
| tokenType | string | 토큰 타입 (`Bearer`) |
| expiresAt | string | 액세스 토큰 만료일시 (ISO 8601) |
| isNewUser | boolean | 신규 가입 여부 |

---

### POST /auth/token/refresh — 토큰 재발급

- UC: UC-AUTH-03
- 인증: 불필요 (RT 사용)
- 필요 역할: 없음

#### Request

**Body**

```json
{
  "refreshToken": "eyJhbGci..."
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| refreshToken | string | Y | JWT 리프레시 토큰 |

#### Response

**200 OK**

```json
{
  "success": true,
  "data": {
    "accessToken": "eyJhbGci...",
    "expiresAt": "2025-06-17T11:00:00Z"
  },
  "message": "success"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| accessToken | string | 새로 발급된 JWT 액세스 토큰 |
| expiresAt | string | 액세스 토큰 만료일시 (ISO 8601) |

---

### POST /auth/logout — 로그아웃

- 인증: 필요
- 필요 역할: 없음
- RT 무효화 (DB에서 삭제)

#### Response

**200 OK**

```json
{
  "success": true,
  "data": null,
  "message": "success"
}
```
