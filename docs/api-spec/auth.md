# Auth API

**Base:** `http://localhost:8081/api/v1`

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
  → 프론트엔드에서 Kakao SDK로 사용자 정보 직접 조회
  → 이 API 호출 (kakaoId, nickname 등 전달)
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
  "oauthId": "123456789",
  "nickname": "카카오사용자",
  "profileImage": "https://k.kakaocdn.net/...",
  "email": "kakao@user.com"
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| oauthId | string | Y | OAuth 제공자의 고유 식별자 |
| nickname | string | Y | 닉네임 |
| profileImage | string | N | 프로필 이미지 URL (없으면 null) |
| email | string | Y | 이메일 |

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

> **TODO (RTR — Refresh Token Rotation)**
>
> 현재 스펙은 AT만 재발급하고 RT는 재사용하는 구조다.
> 기능 개발 완료 후 Redis 기반 RT 관리로 전환하면서 아래 방식으로 변경할 것.
>
> - **RT도 함께 교체**: 재발급 요청마다 기존 RT를 폐기하고 새 RT를 발급해 응답에 포함
> - **Redis 저장**: 발급된 RT를 `refresh:{userId}` 키로 Redis에 저장, TTL은 RT 만료 시간과 동일하게 설정
> - **재사용 감지(Replay Detection)**: 이미 폐기된 RT로 재발급 시도가 들어오면 해당 유저의 모든 세션 강제 만료 처리 (탈취 시나리오 대응)
> - **로그아웃**: DB 삭제 대신 Redis 키 삭제로 변경
>
> 변경 시 응답 스펙에 `refreshToken` 필드 추가 필요:
>
> ```json
> {
>   "success": true,
>   "data": {
>     "accessToken": "eyJhbGci...",
>     "refreshToken": "eyJhbGci...(새 RT)",
>     "expiresAt": "2025-06-17T11:00:00Z"
>   },
>   "message": "success"
> }
> ```

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
