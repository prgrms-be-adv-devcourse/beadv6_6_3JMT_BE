# user-service 어드민 API → admin-service 이관 설계

## 배경

user-service의 어드민 관련 API 6개(회원 목록/통계/상태변경, 판매자 등록 신청 목록/승인/반려)를 admin-service로 이관한다. settlement-service, order-service 이관 시 확립된 전례(admin-service가 다른 서비스 스키마를 own entity로 직접 접근하고, 네트워크 호출은 두지 않음)를 따르되, 이번 이관은 **쓰기(mutating) 엔드포인트 3개**를 포함한다는 점에서 앞선 두 이관(순수 조회)과 다르다.

## 현재 상태 (user-service)

### 컨트롤러

- `AdminUserController`(`/api/v2/admin`) — `com.prompthub.user.admin.presentation.controller`
  - `GET /users` — `listUsers`: 상태/역할/키워드 필터 + 페이지네이션
  - `GET /stats/users` — `getUserStats`: 누적 회원 수, 오늘 신규 가입 수
  - `PATCH /users/{userId}/status` — `changeUserStatus`: active/suspended/withdrawn 전환
- `AdminSellerController`(`/api/v2/admin`) — 같은 패키지
  - `GET /sellers/register` — `listSellerRegisters`: 상태 필터 + 페이지네이션
  - `PATCH /sellers/register/{registerId}/approve` — `approveSeller`
  - `PATCH /sellers/register/{registerId}/reject` — `rejectSeller`

각각 `AdminUserUseCase`/`AdminSellerUseCase` → `AdminUserApplicationService`/`AdminSellerApplicationService`(`com.prompthub.user.admin.application.service`)로 위임. Repository 직접 접근은 이 서비스 계층에서만 일어난다.

### 도메인 모델 (이관 대상 테이블)

- `User`(`com.prompthub.user.user.domain.model`, 테이블 `"user"`) — `UserRepository` 포트, `UserRepositoryAdapter`(Spring Data JPA `Specification` 기반 필터링, QueryDSL 아님). 도메인 메서드: `activate()`, `block()`, `withdraw()`, `addRole(UserRole)`. `roles`는 `@ElementCollection`(`user_role` 테이블).
- `SellerRegister`(`com.prompthub.user.seller.domain.model`, 테이블 `seller_register`) — `SellerRegisterRepository` 포트. 도메인 메서드: `approve()`, `reject(reason)`. `categories`는 `@ElementCollection`(`seller_register_category` 테이블).

두 엔티티 모두 **일반(비어드민) 플로우에서도 쓰인다** — `User`는 로그인/프로필/OAuth, `SellerRegister`는 `POST /seller/register`(자기 신청). 이관 후에도 user-service가 이 엔티티들을 그대로 소유한다.

### 쓰기 엔드포인트의 부수효과 (auth 인프라 결합)

`changeUserStatus`와 `approveSeller`는 `User`/`SellerRegister` 상태를 바꾸는 것 외에, gateway forward-auth가 참조하는 인가 캐시를 무효화한다. 캐시를 안 지우면 최대 60초간 게이트웨이가 바뀌기 전 상태·역할로 요청을 통과시킨다(`docs/api-spec/auth.md`의 `GET /internal/authorize/{userId}` 60초 TTL 캐시).

- `AdminUserApplicationService.changeUserStatus`: `WITHDRAWN`이면 `SessionRevocationUseCase.revoke(userId)` 호출(내부에서 `RefreshTokenRepository.deleteByUserId()` + `AuthorizationCacheRepository.evict()`), 그 외(`ACTIVE`/`BLOCKED`)는 `AuthorizationCacheRepository.evict()`만.
- `AdminSellerApplicationService.approve`: `user.addRole(SELLER)` 후 `AuthorizationCacheRepository.evict()`.
- `reject`는 `SellerRegister.reject()`만 하고 auth 인프라 무관.

관련 구현체(모두 `com.prompthub.user.auth` 패키지):

- `AuthorizationCacheRepository`(포트) / `RedisAuthorizationCacheAdapter` — Redis 해시, 키 `user:authz:{userId}`, TTL 60초.
- `RefreshTokenRepository`(포트) / `RefreshTokenRepositoryAdapter` — RDB(`refresh_token` 테이블) + Redis 캐시(키 `refresh_token:{userId}`, TTL 5분) 하이브리드. `deleteByUserId`가 DB 삭제 + 캐시 evict 둘 다 수행.
- `SessionRevocationUseCase`/`SessionRevocationApplicationService` — 위 둘을 조합.

테스트는 `StringRedisTemplate`을 Mockito로 목킹하는 순수 유닛 테스트(`RedisAuthorizationCacheAdapterTest`) — 임베디드 Redis나 Testcontainers 안 씀.

## 현재 상태 (admin-service)

- 기존 도메인: `settlement`(정산, settlement-service에서 이관), `order`(주문 조회 3종, order-service에서 이관). 둘 다 **읽기 전용** 이관이었다 — own entity로 다른 서비스 테이블을 읽기만 하고, 쓰기용 API(`@Builder`/`create()`/setter)는 두지 않았다.
- `com.prompthub.admin.order.domain.model.SellerNickname`이 이미 `"user"` 테이블을 읽기 전용으로 일부 컬럼(`user_id`, `name`)만 매핑 중(주문 목록의 판매자 닉네임 enrichment용). 이번에 추가되는 `admin.user.domain.model.User`(읽기+쓰기, 전체 컬럼)와 같은 물리 테이블을 별개 클래스로 매핑하지만 컬럼 겹침 없고 목적이 달라 충돌 없음.
- datasource가 `user_service` 스키마를 이미 whitelist 상태(order 이관 때 확인됨) — 스키마 접근 관련 별도 설정 변경 불필요. `ddl-auto: none`.
- 인터서비스 네트워크 클라이언트(Feign/gRPC/WebClient) 전무 — 이 원칙 유지.
- **Redis 의존성 없음**(신규 도입 필요 — 아래 참고).
- QueryDSL 의존성 있음(order 전용). 이번 이관 대상(user/seller 목록 필터링)은 원본이 QueryDSL이 아니라 Spring Data JPA `Specification`을 쓰므로 QueryDSL을 끌어오지 않는다.

## 게이트웨이 라우팅

`apigateway/.../route/VersionedServiceRoute.java`의 `VersionedServiceRoute.ALL`이 서비스별 경로 접미사를 코드로 고정 관리한다. 현재:

- `admin-service`(order=1): `/admin/settlements/**`, `/admin/orders`, `/admin/orders/**`
- `user-service`(order=5): `.../ "/admin/**"` (catch-all, 가장 마지막에 매칭)

`/admin/users/**`, `/admin/stats/users`, `/admin/sellers/register/**`가 지금은 이 catch-all로 user-service에 라우팅되고 있다. 이관 후 이 경로들은 admin-service 항목으로 옮기고, user-service 항목에서는 `/admin/**` 자체를 제거한다(이관 후 user-service엔 어드민 경로가 하나도 안 남으므로 catch-all이 무의미해짐).

## 결정 사항 (Q&A로 확정)

1. **쓰기 3개(상태변경/승인/반려)의 이관 방식**: **전부 own entity로 복제.** admin-service가 `User`/`SellerRegister`를 읽기+쓰기 겸용 own entity로 직접 갖고, `AuthorizationCacheRepository`/`RefreshTokenRepository`/`SessionRevocationUseCase`도 admin-service 안에 그대로 재구현한다. user-service 원본 코드는 완전 삭제. 네트워크 호출(gRPC/REST)로 user-service에 위임하는 방식은 채택하지 않음 — order/settlement 전례의 "네트워크 클라이언트 없음" 원칙을 쓰기까지 확장.
   - **트레이드오프**: `User`/`SellerRegister`의 도메인 불변식(예: `block()`/`withdraw()`/`approve()`)과 세션·캐시 무효화라는 보안 크리티컬 로직이 두 서비스에 중복된다. 향후 이 로직에 버그 수정이 필요하면 양쪽에 반영해야 한다(예: `RefreshTokenRepositoryAdapter`의 캐시-DB 정합성 관련 기존 보안 수정 이력 — 회전 시 캐시를 안 거치고 락을 직접 거는 부분).
2. **이관 방식(공통)**: settlement/order 전례 그대로 — admin-service가 user_service 스키마를 own entity로 직접 읽고 쓴다. REST/gRPC 프록시 두지 않음.
3. **컷오버 방식**: Hard cutover, 한 PR. user-service `admin` 패키지 전체 삭제 + admin-service 신규 코드 + 게이트웨이 라우트 변경을 동시에 적용. 전환기간(듀얼 라우팅) 두지 않음.
4. **클래스명 컨벤션**: "Admin" 접두사 제거(`UserController`, `SellerController` 등) — settlement/order 컨벤션과 통일.
5. **패키지 구조**: user-service 소스가 `user`/`seller`/`auth` 세 도메인 패키지로 나뉘어 있는 걸 그대로 미러링(주문 이관 때 패키지명을 소스 도메인명 그대로 쓴 전례 따름). `auth`를 별도 패키지로 두는 이유:
   - user-service도 세션/캐시 로직을 `user`/`seller` 패키지가 아니라 별도 `auth` 패키지에 둔다 — 회원 리소스 CRUD와 다른 관심사.
   - `admin.user`(상태변경)와 `admin.seller`(승인) 둘 다 캐시 evict가 필요 — 한쪽 패키지 안에 넣으면 다른 쪽이 형제 패키지 내부를 참조하게 됨(클린아키텍처 "패키지 경계" 위반). 별도 포트로 빼면 둘 다 대등하게 참조.
6. **API 경로 버전**: 새 컨트롤러들은 `${api.init}` 프로퍼티(admin-service 기준 `/api/v2`) 사용. user-service 원본도 이미 `/api/v2/admin/...`이므로(#305 전환 완료) **외부 경로 자체는 안 바뀐다** — order 이관 때(`v1`→`v2`)와 달리 여기선 버전 전환 이슈 없음. 게이트웨이가 라우팅 대상 서비스만 바뀌고 경로/버전은 그대로.
7. **`RefreshTokenRepository`/`AuthorizationCacheRepository` 포트 범위**: 둘 다 원본 시그니처 그대로 안 가져가고 admin-service 어드민 액션이 실제 쓰는 메서드만 있는 축소 인터페이스로 새로 정의한다.
   - `RefreshTokenRepository`: `deleteByUserId(UUID)` 하나만. 원본의 `save`/`findByUserId`/`findByUserIdForUpdate`(발급·회전용)는 어드민 액션이 안 씀.
   - `AuthorizationCacheRepository`: `evict(UUID)` 하나만. 원본의 `find`/`save`(캐시 적재·조회, `AuthzSnapshot` 타입)는 어드민 액션이 안 씀 — admin-service엔 `AuthzSnapshot` 자체를 안 둔다.
   - 원본 그대로면 로그인/토큰 발급 경로에서만 쓰는 메서드까지 admin-service가 구현해야 하는데, 어드민 액션은 삭제·무효화만 하므로 안 쓸 코드다 — order 이관의 "실제 참조하는 컬럼/기능만 미러링한다" 원칙과 동일하게 적용.

## 아키텍처

```
[Gateway] --/api/v2/admin/users/**--------------> [admin-service]
[Gateway] --/api/v2/admin/stats/users----------->   UserController
[Gateway] --/api/v2/admin/sellers/register/**--->   SellerController
                                                       │
                    ┌──────────────────────────────────┴───────────────────────────────┐
                    ▼                                                                    ▼
        UserUseCase → UserApplicationService                          SellerUseCase → SellerApplicationService
              │  reads/writes: user_service.user, user_role                │  reads/writes: user_service.seller_register,
              │  (UserRepository, own entity)                              │  seller_register_category, user_service.user
              │                                                            │  (SellerRegisterRepository + UserRepository)
              └──────────────────┬─────────────────────────────────────────┘
                                  ▼
                    com.prompthub.admin.auth (신규 지원 패키지)
                    AuthorizationCacheRepository → Redis(user:authz:{userId}, TTL 60s)
                    RefreshTokenRepository → refresh_token 테이블 + Redis(refresh_token:{userId}, TTL 5분)
                    SessionRevocationUseCase(WITHDRAWN 전용, 위 둘 조합)
```

user-service는 이 6개 엔드포인트와 관련 코드를 전부 잃는다. user-service의 일반(비어드민) 플로우(`/users/me`, `/seller/register`, OAuth 로그인, 토큰 재발급/로그아웃 등)와 그 소유 `User`/`SellerRegister`/`RefreshToken`/auth 캐시 로직은 그대로 남는다 — admin-service는 같은 테이블/Redis 키 네임스페이스를 어드민 액션용으로 별도 매핑할 뿐 코드나 런타임 상태를 공유하지 않는다(같은 물리 Redis 인스턴스·같은 물리 테이블일 뿐).

## 컴포넌트

### `com.prompthub.admin.user`

| 계층 | 클래스 | 비고 |
|---|---|---|
| presentation.controller | `UserController` | `GET /users`, `GET /stats/users`, `PATCH /users/{userId}/status`. 시그니처/응답 타입은 원본과 동일 |
| presentation.dto.request | `ChangeUserStatusRequest` | 동일 |
| presentation.dto.response | `UserResponse`, `UserStatsResponse`, `UserStatusResponse` | 동일 |
| application.usecase | `UserUseCase` | 원본과 동일 3개 메서드 |
| application.service | `UserApplicationService` | `changeUserStatus`의 WITHDRAWN 분기·캐시 evict 분기 로직 그대로. 의존성만 `com.prompthub.admin.auth`의 포트로 교체 |
| application.dto | `UserListQuery`, `UserPageResult`, `UserSummaryResult`, `UserStatsResult`, `ChangeUserStatusCommand`, `UserStatusResult` | 동일 |
| domain.model | `User` (읽기+쓰기 신규 엔티티) | 원본과 동일 필드/메서드(`activate`/`block`/`withdraw`/`addRole`). `admin-service` 기존 `BaseEntity` 상속 |
| domain.model | `UserRole`, `UserStatus` | enum, 동일 |
| domain.repository | `UserRepository` (포트) | 원본과 동일 시그니처(`findById`, `findUsers`, `countUsers`, `countCreatedBetween`, `findAllByIds`, `save` 등) |
| infrastructure.persistence | `UserRepositoryAdapter`, `UserJpaRepository`, `UserSpecifications` | 원본 Specification 필터링 로직 1:1 이식 |

### `com.prompthub.admin.seller`

| 계층 | 클래스 | 비고 |
|---|---|---|
| presentation.controller | `SellerController` | `GET /sellers/register`, `PATCH /sellers/register/{registerId}/approve`, `PATCH /sellers/register/{registerId}/reject` |
| presentation.dto.request | `RejectSellerRegisterRequest` | 동일 |
| presentation.dto.response | `SellerRegisterResponse`, `SellerRegisterReviewResponse` | 동일 |
| application.usecase | `SellerUseCase` | 원본과 동일 3개 메서드 |
| application.service | `SellerApplicationService` | `approve`의 `addRole`+캐시 evict 로직 그대로. `com.prompthub.admin.user`의 `UserRepository`, `com.prompthub.admin.auth`의 `AuthorizationCacheRepository` 참조 |
| application.dto | `SellerRegisterListQuery`, `SellerRegisterPageResult`, `SellerRegisterSummaryResult`, `ApproveSellerCommand`, `RejectSellerCommand`, `SellerRegisterReviewResult` | 동일 |
| domain.model | `SellerRegister` (읽기+쓰기 신규 엔티티) | 원본과 동일 필드/메서드(`approve`/`reject`) |
| domain.model | `SellerRegisterStatus` | enum, 동일 |
| domain.repository | `SellerRegisterRepository` (포트) | 원본과 동일 시그니처 |
| infrastructure.persistence | `SellerRegisterRepositoryAdapter`, `SellerRegisterJpaRepository` | 원본 로직 1:1 이식 |

### `com.prompthub.admin.auth` (신규 지원 패키지, 공개 API 없음)

| 계층 | 클래스 | 비고 |
|---|---|---|
| domain.repository | `AuthorizationCacheRepository` (포트, 축소 인터페이스) | `evict(UUID)` 단일 메서드만 — 결정 사항 7 참조. `find`/`save`(캐시 적재·조회)는 어드민 액션이 안 씀. `AuthzSnapshot` 타입 자체도 admin-service엔 안 둠 |
| infrastructure.redis | `RedisAuthorizationCacheAdapter` | 키 prefix `user:authz:`(원본과 동일 — evict 시 같은 키를 지워야 하므로) |
| domain.repository | `RefreshTokenRepository` (포트, 축소 인터페이스) | `deleteByUserId(UUID)` 단일 메서드만 — 결정 사항 7 참조. `save`/`findByUserId`/`findByUserIdForUpdate`(발급·회전용)는 어드민 액션이 안 쓰므로 포트에 안 둠 |
| domain.model | `RefreshToken` (읽기+쓰기 신규 엔티티, 테이블 `refresh_token`) | `admin.user`의 세션폐기 경로 전용. 컬럼은 `id`, `user_id`만 매핑(삭제에 필요한 최소 컬럼) |
| infrastructure.persistence | `RefreshTokenRepositoryAdapter`, `RefreshTokenJpaRepository` | `deleteByUserId` — DB 삭제 + Redis 캐시(`refresh_token:{userId}`) evict |
| application.usecase | `SessionRevocationUseCase`/`SessionRevocationApplicationService` | 원본과 동일(`refreshTokenRepository.deleteByUserId` + `authorizationCacheRepository.evict` 조합) |

## 에러 처리

`AdminErrorCode`(기존 `A-001`~`A-006`)에 신규 코드 추가:

| enum | code | HTTP | 의미 |
|---|---|---|---|
| `USER_NOT_FOUND` | A-007 | 404 | 원본 `AUTH_NOT_FOUND`(A001)와 동일 의미 |
| `SELLER_REGISTER_NOT_FOUND` | A-008 | 404 | 원본 `AUTH_SELLER_APPLICATION_NOT_FOUND`(A008)와 동일 의미 |

"이미 심사된 신청 승인/반려 시도" 가드는 기존 `INVALID_INPUT_VALUE`(A-001, 400)를 재사용 — 원본이 `VALIDATION_FAILED`(400)로 응답하던 것과 동일 상태코드 유지(409로 바꾸면 기존 FE 계약이 깨짐).

Redis 장애 시 동작은 원본과 동일하게 유지: `RedisAuthorizationCacheAdapter`/`RefreshTokenRepositoryAdapter`는 `DataAccessException`을 잡아 로그만 남기고 흡수한다(캐시 실패가 어드민 액션 자체를 실패시키지 않음, 단 `evictCache` 실패는 보안상 `log.error`로 남기는 원본 정책도 유지).

## 빌드/설정 변경

- `admin-service/build.gradle`: `spring-boot-starter-data-redis` 추가(신규 의존성). QueryDSL은 이 도메인에 불필요(추가 안 함).
- `config/src/main/resources/configs/admin-service.yml`: `spring.data.redis.host: ${REDIS_HOST}`, `spring.data.redis.port: ${REDIS_PORT}` 추가(user-service.yml과 동일 플레이스홀더 — 같은 Redis 인스턴스 공유).
- **남는 범위 밖 선행조건**: 루트 `docker-compose.yml`의 `admin-service` 서비스 `environment` 블록엔 아직 `REDIS_HOST`/`REDIS_PORT`가 없다(user-service 블록엔 이미 있음). config 모듈 값을 채워도 컨테이너가 그 환경변수를 실제로 받아야 `${REDIS_HOST}` 치환이 값을 갖는다 — `docker-compose.yml`은 `user-service`/`admin-service`/`apigateway` 세 모듈 밖의 루트 인프라 파일이라 이 작업은 손대지 않는다. 채워지기 전까지는 `RedisAuthorizationCacheAdapter`/`RefreshTokenRepositoryAdapter`가 매번 연결 실패를 흡수만 하는 상태가 된다(앱은 뜨지만 캐시 evict가 실제로는 동작하지 않음).
- `apigateway/.../route/VersionedServiceRoute.java`: `"/admin/users", "/admin/users/**", "/admin/stats/users", "/admin/sellers/register", "/admin/sellers/register/**"`를 user-service 항목의 `pathSuffixes`에서 제거하고 admin-service 항목의 `pathSuffixes`에 추가. `order` 값은 admin-service=1 그대로 유지.
- user-service에서 삭제:
  - `admin/presentation/controller/{AdminUserController, AdminSellerController}.java`
  - `admin/presentation/dto/**` 전체
  - `admin/application/usecase/{AdminUserUseCase, AdminSellerUseCase}.java`
  - `admin/application/service/{AdminUserApplicationService, AdminSellerApplicationService}.java`
  - `admin/application/dto/**` 전체
  - 대응 테스트 전부
  - **주의**: `User`/`SellerRegister`/`UserRepository`/`SellerRegisterRepository`/`auth` 패키지 전체는 일반 플로우가 계속 쓰므로 삭제하지 않는다 — 삭제 대상은 `com.prompthub.user.admin` 패키지 하위뿐.

## 테스트

- 원본 `AdminUserControllerTest`(있다면)/`AdminUserApplicationServiceTest`/`AdminSellerApplicationServiceTest` 등을 admin-service로 이식(클래스명 `Admin` 접두사 제거).
- `RedisAuthorizationCacheAdapterTest`, `RefreshTokenRepositoryAdapterTest`, `SessionRevocationApplicationServiceTest`도 원본 그대로(Mockito로 `StringRedisTemplate` 목킹) admin-service로 이식.
- `UserRepositoryAdapter`/`SellerRegisterRepositoryAdapter`는 H2(admin-service 기존 테스트 컨벤션)로 Specification/페이징 검증.
- 원본 테스트는 삭제.

## 범위 밖

- user-service의 일반(비어드민) 회원/판매자/인증 플로우, 그 `User`/`SellerRegister`/`RefreshToken` 엔티티와 `auth` 도메인 패키지 — 변경 없음(admin-service가 별도로 own entity를 두는 것과 무관하게 원본은 그대로 유지·운영).
- admin-service의 기존 settlement/order 기능 — 변경 없음.
- 전환기간/듀얼 라우팅 — 채택하지 않음(결정 사항 3 참조).
- product-service 등 다른 서비스의 어드민 API 이관 — 이 설계의 범위 아님(요구사항상 user-service/admin-service/apigateway만 다룸).
