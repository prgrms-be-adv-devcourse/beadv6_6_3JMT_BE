# 카카오 OAuth 소셜 로그인 설계

- 이슈: #42
- 날짜: 2026-06-23

---

## 1. 개요

프론트엔드가 카카오 인증 후 받은 `code`를 `POST /auth/oauth/kakao`로 전달하면,
user-service가 카카오 토큰 교환 → 사용자 조회/생성 → JWT 발급까지 처리한다.

### 전체 흐름

```
프론트 → 카카오 인증 페이지
카카오 → /auth/kakao/callback?code=xxx  (프론트 콜백)
프론트 → POST /auth/oauth/kakao { code }
          ↓ (API Gateway 화이트리스트 통과)
          AuthController
            → OAuthApplicationService
                → KakaoApiAdapter  ── POST kauth.kakao.com/oauth/token   (code → kakao AT)
                → KakaoApiAdapter  ── GET  kapi.kakao.com/v2/user/me     (kakao AT → user info)
                → AuthRepository.findByProviderAndProviderUserId(KAKAO, kakaoId)
                → (없으면) User.create() + Auth.create() → 저장
                → JwtProvider.issue(userId, role)
            ← OAuthLoginResponse (accessToken, refreshToken, user, isNewUser)
```

---

## 2. 패키지 구조

```
com.prompthub.user.auth
├── presentation/
│   ├── controller/AuthController.java
│   └── dto/
│       ├── request/OAuthLoginRequest.java
│       └── response/OAuthLoginResponse.java
├── application/
│   ├── usecase/
│   │   ├── OAuthUseCase.java          ← 인바운드 포트
│   │   └── KakaoApiPort.java          ← 외부 API 아웃바운드 포트
│   ├── service/
│   │   └── OAuthApplicationService.java
│   └── dto/
│       ├── OAuthLoginCommand.java
│       ├── OAuthLoginResult.java
│       └── KakaoUserInfo.java         ← KakaoApiPort 반환 타입
├── domain/
│   ├── model/
│   │   ├── Auth.java
│   │   └── AuthProvider.java          (enum: KAKAO, NAVER, GOOGLE)
│   └── repository/
│       └── AuthRepository.java        ← 영속성 아웃바운드 포트
└── infrastructure/
    ├── persistence/
    │   ├── AuthJpaRepository.java
    │   └── AuthRepositoryAdapter.java
    └── external/
        ├── KakaoApiAdapter.java
        └── dto/
            ├── KakaoTokenResponse.java
            └── KakaoUserInfoResponse.java

com.prompthub.user.global
├── config/
│   └── RestClientConfig.java          ← 카카오 API용 RestClient 빈
└── jwt/
    ├── JwtProvider.java
    └── JwtProperties.java
```

### 의존성 방향

```
infrastructure ──▶ application ◀── presentation
                        │
                        ▼
                      domain
```

- `KakaoApiAdapter`(infrastructure) implements `KakaoApiPort`(application) ✓
- `AuthRepositoryAdapter`(infrastructure) implements `AuthRepository`(domain) ✓
- `OAuthApplicationService`(application) uses `KakaoApiPort`(application), `AuthRepository`(domain), `UserRepository`(domain) ✓

---

## 3. 도메인 모델

### Auth 엔티티

`auth` 테이블에 대응. `user_id`는 JPA 연관관계 없이 UUID 값으로만 보유해 도메인 경계를 유지한다.

```java
@Entity @Table(name = "auth")
@Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Auth {
    @Id @Column(name = "auth_id", columnDefinition = "uuid")
    private UUID authId;

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false)
    private AuthProvider provider;

    @Column(name = "provider_user_id", nullable = false, length = 100)
    private String providerUserId;

    @Column(name = "connected_at", nullable = false)
    private LocalDateTime connectedAt;

    public static Auth create(UUID userId, AuthProvider provider, String providerUserId) { ... }
}
```

### AuthProvider enum

```java
public enum AuthProvider { KAKAO, NAVER, GOOGLE }
```

---

## 4. 포트 & 어댑터

### 인바운드 포트

```java
// application/usecase/OAuthUseCase.java
public interface OAuthUseCase {
    OAuthLoginResult login(OAuthLoginCommand command);
}
```

### 외부 API 아웃바운드 포트

```java
// application/usecase/KakaoApiPort.java
public interface KakaoApiPort {
    String getAccessToken(String code);
    KakaoUserInfo getUserInfo(String kakaoAccessToken);
}

// application/dto/KakaoUserInfo.java
public record KakaoUserInfo(String id, String name, String email, String profileImageUrl) {}
```

### 영속성 아웃바운드 포트

```java
// domain/repository/AuthRepository.java
public interface AuthRepository {
    Optional<Auth> findByProviderAndProviderUserId(AuthProvider provider, String providerUserId);
    Auth save(Auth auth);
}
```

### KakaoApiAdapter (infrastructure/external)

- `getAccessToken`: RestClient로 `https://kauth.kakao.com/oauth/token` POST
  - 파라미터: `grant_type=authorization_code`, `client_id`, `redirect_uri`, `code`
  - 반환: `KakaoTokenResponse.access_token`
- `getUserInfo`: RestClient로 `https://kapi.kakao.com/v2/user/me` GET
  - 헤더: `Authorization: Bearer {kakaoAccessToken}`
  - 반환: `KakaoUserInfoResponse` → `KakaoUserInfo`로 변환

카카오 `client_id`와 `redirect_uri`는 `application.yml` 환경변수로 관리한다.

---

## 5. 애플리케이션 서비스 로직

```
OAuthApplicationService.login(OAuthLoginCommand):
  1. kakaoApiPort.getAccessToken(command.code())
  2. kakaoApiPort.getUserInfo(kakaoAccessToken)
  3. authRepository.findByProviderAndProviderUserId(KAKAO, kakaoUserInfo.id())
     → 있으면: auth.userId() 추출, isNewUser = false
     → 없으면:
         a. userRepository.findByEmail(kakaoUserInfo.email())
            → 있으면: 기존 User에 Auth 연결
            → 없으면: User.create(...) → userRepository.save()
         b. Auth.create() → authRepository.save()
         c. isNewUser = true
     * 카카오가 이메일을 제공하지 않는 경우: email = null로 User 생성 허용
       (User.email은 nullable하지 않으므로, 카카오 providerId 기반 임시 이메일 생성 또는
        email 컬럼 nullable 허용 여부를 팀과 협의 필요 — 현재는 email 필수로 가정)
  4. userRepository.findById(userId)
  5. jwtProvider.issueAccessToken(userId, user.role())
  6. jwtProvider.issueRefreshToken(userId)
  7. return OAuthLoginResult(user, accessToken, refreshToken, isNewUser)
```

`@Transactional` 범위: step 3~4 (DB 읽기/쓰기 전체).

---

## 6. JWT 발급

### JwtProperties

`application.yml`에서 읽는 설정값:

```yaml
jwt:
  secret: ${JWT_SECRET}
  access-token-expiry: 1800000    # 30분 (ms)
  refresh-token-expiry: 604800000 # 7일 (ms)
```

### JwtProvider

- `issueAccessToken(UUID userId, UserRole role)`: claims에 `userId`, `role` 포함
- `issueRefreshToken(UUID userId)`: claims에 `userId`만 포함
- Refresh Token은 이번 범위에서 **stateless** (DB 저장 없음). RT 무효화(로그아웃·재발급)는 별도 이슈로 분리.

---

## 7. 의존성 추가 (build.gradle)

```groovy
// JWT
implementation 'io.jsonwebtoken:jjwt-api:0.12.6'
runtimeOnly    'io.jsonwebtoken:jjwt-impl:0.12.6'
runtimeOnly    'io.jsonwebtoken:jjwt-jackson:0.12.6'

// Spring Security (SecurityFilterChain, @EnableWebSecurity)
implementation 'org.springframework.boot:spring-boot-starter-security'
```

`RestClient`는 `spring-boot-starter-webmvc`에 포함되어 있어 별도 추가 불필요.

---

## 8. API Gateway 화이트리스트

`/auth/**` 경로는 이미 JWT 검증 우회 대상이어야 한다. Gateway의 `SecurityConfig.WHITE_LIST`에
`/auth/oauth/**`가 포함되어 있는지 확인 필요 (user-service 모듈 외부 수정이므로 직접 변경하지 않고 담당자에게 요청).

---

## 9. 범위 밖 (별도 이슈)

- Refresh Token DB 저장 및 무효화 (`POST /auth/token/refresh`, `POST /auth/logout`)
- 이메일 회원가입/로그인 (`POST /auth/signup`, `POST /auth/login`)
- Naver, Google OAuth 연동
