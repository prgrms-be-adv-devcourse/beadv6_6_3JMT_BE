# 클린아키텍처 컨벤션

사용자 서비스(user-service)의 패키지 구조와 계층 규칙을 정의한다.
헥사고날(포트 & 어댑터) 스타일을 기반으로 한다.

> 관련 문서
> - 도메인 모델·Lombok 규칙: `domain-model.md`
> - Controller·예외 처리 규칙: `controller-exception.md`
> - 코드 스타일(네이밍 케이스·import 등): `code-style.md`

## 1. 의존성 규칙

의존성은 항상 안쪽(도메인)을 향한다.

```
presentation ──▶ application ──▶ domain ◀── infrastructure
```

- `domain`은 다른 어떤 계층도 import 하지 않는다. (단, 엔티티 겸용 정책상 JPA는 예외 — `domain-model.md` 참고)
- `application`은 `domain`만 의존한다. presentation·infrastructure를 모른다.
- `presentation`·`infrastructure`는 바깥 계층이며, 안쪽(application·domain)에 의존한다.
- 바깥에서 안쪽으로의 호출은 **포트(인터페이스)**를 통한다.

이 방향이 깨지면(예: domain이 infrastructure를 import) 구조 위반으로 본다.

## 2. 패키지 구조

기능(도메인) 단위로 패키지를 나누고, 그 안에 4계층 + config를 둔다.

```
com.prompthub.user
├── user                        ← 회원 도메인
│   ├── presentation
│   │   ├── controller
│   │   └── dto
│   │       ├── request
│   │       └── response
│   ├── application
│   │   ├── usecase
│   │   ├── service
│   │   └── dto
│   ├── domain
│   │   ├── model
│   │   └── repository
│   ├── infrastructure
│   │   ├── persistence
│   │   └── event
│   └── config
├── auth                        ← 인증 도메인 (이메일/OAuth 로그인, 토큰)
│   └── (동일한 4계층 구조)
├── seller                      ← 판매자 도메인 (등록 신청, 심사)
│   └── (동일한 4계층 구조)
└── wishlist                    ← 찜 도메인
    └── (동일한 4계층 구조)
```

기능이 늘어나면 같은 레벨에 새 기능 패키지를 추가한다.

## 3. 계층별 책임

| 계층 | 담는 것 | 하면 안 되는 것 |
| --- | --- | --- |
| `presentation` | HTTP 요청/응답 처리, request·response DTO 변환 | 비즈니스 로직, 도메인 모델 직접 노출 |
| `application` | 유스케이스 조율, 트랜잭션 경계, Command·Result 변환 | HTTP·JPA 등 기술 세부사항 직접 다루기 |
| `domain` | 핵심 비즈니스 규칙, 도메인 모델, 포트 정의 | 다른 계층 의존(JPA 제외), 프레임워크 로직 |
| `infrastructure` | 포트 구현(어댑터), DB·메시징 등 기술 연동 | 비즈니스 규칙 |

핵심:
- 컨트롤러는 얇게. 받고 → usecase 호출 → 응답으로 변환만 한다.
- 비즈니스 규칙은 `domain`에, 흐름 조율은 `application`에 둔다.

## 4. 포트 & 어댑터 규칙

| 구분 | 포트(인터페이스) 위치 | 구현(어댑터) 위치 |
| --- | --- | --- |
| 인바운드(유스케이스) | `application/usecase` | `application/service` |
| 아웃바운드(영속성) | `domain/repository` | `infrastructure/persistence` |

- 인바운드: `UserUseCase`(포트) ← `UserApplicationService`(구현)
- 아웃바운드: `UserRepository`(포트, domain) ← `UserRepositoryAdapter`(구현, infrastructure)
- 어댑터는 내부에서 `UserJpaRepository`(Spring Data)를 호출한다.

```
application/service/UserApplicationService   implements   application/usecase/UserUseCase
                                │ 호출
                                ▼
domain/repository/UserRepository  ◀ implements ◀  infrastructure/persistence/UserRepositoryAdapter
                                                                    │ 호출
                                                                    ▼
                                                infrastructure/persistence/UserJpaRepository
```

## 5. 계층 네이밍 규칙

계층·역할별 클래스 접미사를 통일한다. (일반 Java 네이밍 케이스 규칙은 `code-style.md` 참고)

| 계층/역할 | 접미사 | 예시 |
| --- | --- | --- |
| 컨트롤러 | `~Controller` | `UserController`, `AuthController` |
| 요청 DTO | `~Request` | `SignupRequest`, `SellerRegisterRequest` |
| 응답 DTO | `~Response` | `UserResponse`, `TokenResponse` |
| 인바운드 포트 | `~UseCase` | `UserUseCase`, `SellerUseCase` |
| 유스케이스 구현 | `~ApplicationService` | `UserApplicationService` |
| 입력 Command | `~Command` | `SignupCommand`, `UpdateProfileCommand` |
| 출력 Result | `~Result` | `UserResult`, `TokenResult` |
| 아웃바운드 포트 | `~Repository` | `UserRepository`, `SellerRepository` |
| 영속성 어댑터 | `~RepositoryAdapter` | `UserRepositoryAdapter` |
| Spring Data | `~JpaRepository` | `UserJpaRepository` |
| 설정 | `~Config` | `SecurityConfig`, `JwtConfig` |
| 도메인 예외 | `~Exception` | `UserNotFoundException`, `SellerAlreadyAppliedException` |

## 6. DTO 변환 규칙

계층 경계를 넘을 때마다 DTO를 변환해, 안쪽 모델이 바깥으로 새지 않게 한다.

```
Request ──▶ Command ──▶ (domain) ──▶ Result ──▶ Response
 표현         애플리케이션      도메인        애플리케이션    표현
```

- 도메인 모델(`@Entity`)을 컨트롤러 응답으로 직접 반환하지 않는다. 반드시 `~Response`로 변환한다.
- 변환 코드는 각 DTO의 정적 팩토리 메서드(`from`, `of`)에 둔다.
  (예: `UserResponse.from(userResult)`, `request.toCommand()`)
- 변환 로직이 복잡해지면 표현/애플리케이션 계층에 전용 매퍼를 둘 수 있다.
