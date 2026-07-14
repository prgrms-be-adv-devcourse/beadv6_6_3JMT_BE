# Auth API

**Base:** `http://localhost:8081/api/v2`

> 최종 프로젝트 전환으로 `api/v1` → `api/v2`로 변경됨(`docs/adr/config-management.md` §10).
> 다른 도메인(user·seller·wishlist·admin)도 `#305 (이슈)`에서 `api/v2`로 전환되어, 현재 user-service 공개 API는 전부 `api/v2`다.

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
    "name": "민서",
    "role": "BUYER"
  },
  "message": "success"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| userId | string | 생성된 사용자 ID |
| email | string | 이메일 |
| name | string | 닉네임 |
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
- **서버 측 검증(ADR-0008 §결정4)**: 프런트는 카카오 access token만 전달한다. oauthId·email·
  닉네임·프로필이미지는 user-service가 서버 측에서 카카오 API(`kapi.kakao.com/v2/user/me`)를
  호출해 직접 획득하며, 클라이언트가 주장하는 신원 정보는 신뢰하지 않는다.

#### Kakao OAuth 플로우

```
버튼 클릭
  → 프론트엔드에서 Kakao SDK로 로그인 → access token 발급
  → 이 API 호출 (access token 전달)
  → user-service가 kapi.kakao.com/v2/user/me 호출로 신원 검증
  → 로그인/자동 회원가입 완료
```

#### Path Parameters

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| provider | string | OAuth 제공자 (`kakao`) |

#### Request

**Body**

```json
{
  "accessToken": "abcdEFGH1234..."
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| accessToken | string | Y | 카카오로부터 발급받은 access token |

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
      "roles": ["BUYER"]
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
| user.roles | string[] | 역할 목록 (`BUYER` / `SELLER` / `ADMIN`) |
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
    "refreshToken": "eyJhbGci...(새 RT)",
    "expiresAt": "2025-06-17T11:00:00Z"
  },
  "message": "success"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| accessToken | string | 새로 발급된 JWT 액세스 토큰 |
| refreshToken | string | 새로 발급된 JWT 리프레시 토큰(RTR — 재발급마다 회전) |
| expiresAt | string | 액세스 토큰 만료일시 (ISO 8601) |

**RTR(Refresh Token Rotation)**: 재발급마다 기존 RT를 폐기하고 새 RT를 발급한다(ADR-0008 결정2).
제시된 RT의 서명은 유효하지만 저장된 현재 RT와 다르면 재사용(탈취 시나리오)으로 판정해
401(`AUTH_REFRESH_TOKEN_REUSE_DETECTED`, `A012`)을 반환하고 해당 유저의 세션을 전부 무효화한다.

---

### POST /auth/logout — 로그아웃

- UC: UC-AUTH-04
- 인증: 필요 (AT)
- 필요 역할: 없음
- API Gateway에서 AT 검증 후 `X-User-Id` 헤더를 user-service에 전달
- user-service는 해당 유저의 RT를 DB에서 삭제
- AT 무효화: 없음 (AT는 만료될 때까지 유효)

#### Request

**Headers**

| 헤더 | 설명 |
|------|------|
| Authorization | `Bearer {accessToken}` |

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

## 내부 API (Internal)

> gateway forward-auth 전용. 서비스 외부에 노출하지 않음. 공개 API와 달리 `ApiResult` 래핑 없이 순수 JSON을 반환.

### GET /internal/authorize/{userId} — 인가 정보 조회

- 인증: 없음(gateway 내부망에서만 호출)
- 호출처: apigateway forward-auth 필터(#290)
- user-service 내부 Redis에 60초 TTL로 캐시(`user:authz:{userId}`). Redis 장애 시 DB 직접 조회로 폴백.
- 로그인 시 캐시에 적재, 상태·역할 변경 시 즉시 무효화.
- **epoch 세션 검증(ADR-0008 결정 8-1)**: AT의 `epoch` 클레임을 쿼리파라미터로 전달한다. 저장된
  "현재 RT epoch"(`refresh_token` 테이블, RTR마다 회전)과 비교해 값이 없거나 다르면 로그아웃/재로그인으로
  무효화된 이전 세션으로 판정해 401을 fail-closed로 반환한다.

#### Path Parameters

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| userId | UUID | 조회할 사용자 ID |

#### Query Parameters

| 파라미터 | 타입 | 필수 | 설명 |
|---------|------|------|------|
| epoch | long | Y | AT의 `epoch` 클레임 값. 없거나 저장된 현재 epoch과 다르면 401 |

#### Response

**200 OK**

```json
{
  "status": "ACTIVE",
  "role": "BUYER"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| status | string | 계정 상태 (`ACTIVE` / `BLOCKED` / `WITHDRAWN`) |
| role | string | 대표 역할 (`BUYER` / `SELLER` / `ADMIN`, 여러 역할 보유 시 ADMIN > SELLER > BUYER 우선순위) |

**401 Unauthorized** — epoch 없음/불일치, 세션 무효 (`AUTH_SESSION_INVALIDATED`, A013)

**404 Not Found** — 사용자 없음 (`AUTH_NOT_FOUND`, A001)
