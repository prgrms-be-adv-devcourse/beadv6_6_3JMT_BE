# authorize 내부 API + 캐시 — 설계 (#289)

> 기능 요구사항 전문은 GitHub 이슈 #289 참고. 이 문서는 이슈에 없는 구현 결정만 기록한다.

## 스코프

- 이슈 #289의 1~5번 항목(authorize API, Redis 캐시, lazy loading, 무효화, epoch 세션 검증)을 모두 구현한다.
- ADR-0008 문서(`docs/adr/0008-user-service-forward-auth.md`)는 저장소 어디에도 없음(전 브랜치 확인함,
  세션 메모리에만 기록이 남아있던 것으로 보임). 결정 내용은 이슈 #289/#288 본문으로 대체 확인함.

> **정정(추가 커밋)**: 최초 작성 시 "5번(epoch 세션 검증)은 #288 미구현이라 제외"라고 적었으나 이는
> 낡은 근거였다 — epoch 클레임(#288, `ddee910d`/`12068648`/`327b8878`)이 이 브랜치 안에서 authorize API
> 커밋(`705b5703`)보다 **먼저** 이미 끝나 있었다. 즉 "비교 대상이 없다"는 전제가 같은 브랜치 역사와
> 맞지 않는 착오였음을 확인해 아래 "epoch 세션 검증" 절을 추가하고 5번을 스코프에 포함시켰다.

## epoch 세션 검증 (결정 8-1, 추가 커밋)

- `AuthorizeController`에 `epoch`(long, optional 파라미터로 받되 내부적으로는 필수 취급) 쿼리파라미터 추가.
- `AuthorizeApplicationService.authorize(userId, epoch)`가 상태/역할 캐시 조회 **전에** epoch을 검증한다:
  `RefreshTokenRepository.findByUserId(userId)`로 저장된 현재 RT의 epoch을 조회해 비교.
  - `epoch == null` → 세션 무효
  - 저장된 RT 없음(로그아웃 등) → 세션 무효
  - epoch 불일치(RTR로 회전된 이전 세션) → 세션 무효
  - 위 세 경우 모두 `SessionInvalidatedException`(신규, `AUTH_SESSION_INVALIDATED`/A013) → 401
- `RefreshTokenRepository`는 #288에서 이미 만들어진 포트를 그대로 재사용(신규 포트 없음).
  캐시(`RefreshTokenRepositoryAdapter`)의 `deleteByUserId`/`save`가 이미 즉시 무효화·재적재를 하므로
  epoch 검증도 그 즉시성을 그대로 물려받는다.
- 캐시(60초 TTL, `AuthorizationCacheRepository`)는 status/role 조회에만 쓰이고, epoch 검증에는 관여하지
  않는다 — RT epoch 조회가 즉시성을 요구하는 별도 데이터 소스이기 때문에 캐시를 우회한 것이 아니라
  원래 다른 저장소(`refresh_token` 테이블/그 캐시)를 보는 것이다.

## 통신 방식: REST 유지, gRPC 통일 안 함

- product/settlement-service ↔ user-service의 gRPC는 백엔드-백엔드(둘 다 blocking MVC) 통신.
- authorize 호출은 apigateway(WebFlux, 리액티브) → user-service. apigateway엔 gRPC 클라이언트/protobuf
  세팅이 전혀 없고, 게이트웨이의 매 요청 훅으로 걸리는 성격상 REST + WebClient가 자연스러움.
- 게이트웨이 쪽 비동기 호출(WebClient+Mono)은 #290 스코프(apigateway, 읽기 전용 모듈). user-service
  엔드포인트 자체는 기존 컨트롤러들과 동일하게 동기(blocking) MVC로 구현한다.

## 패키지 위치

새 패키지를 만들지 않고 기존 `auth` 패키지에 추가한다(인가 판정은 인증 도메인과 밀접, 기존 4계층 재사용).

```
auth/
├── presentation/controller/AuthorizeController.java
├── application/usecase/AuthorizeUseCase.java
├── application/service/AuthorizeApplicationService.java
├── application/dto/AuthorizeResult.java
├── domain/model/AuthzSnapshot.java                 (record: status, role)
├── domain/repository/AuthorizationCacheRepository.java  (포트)
└── infrastructure/redis/RedisAuthorizationCacheAdapter.java
```

## API 계약

`GET /internal/authorize/{userId}` → 200 `{ "status": "ACTIVE", "role": "BUYER" }`

- `status`/`role`은 이슈 원문(`DELETED` 등)이 아니라 실제 코드 enum(`UserStatus`, `UserRole`) 값 그대로.
- `role`은 기존 `User.getPrimaryRole()`(ADMIN>SELLER>BUYER) 재사용.
- `epoch` 쿼리파라미터는 이번 스코프에서 선언하지 않음 — 나중에 붙어 와도 Spring이 무시하므로 호환 문제 없음.
- 404: `UserErrorCode.AUTH_NOT_FOUND` 재사용.
- ApiResult 래핑 없음(공개 API 아닌 내부 계약이라 이슈 스펙 그대로 plain body).

## 캐시

- 키 `user:authz:{userId}`, Redis Hash(`status`, `role` 필드) + TTL 60초.
- 조회: 캐시 hit → 즉시 응답 / miss → `UserRepository` 조회 → 캐시 적재 → 응답.
- 적재 훅: `AuthApplicationService.oAuthLogin` 성공 시.
- 무효화 훅: `AdminUserApplicationService.changeUserStatus`(상태 전이), `AdminSellerApplicationService`
  SELLER 승인(역할 변경).
- Fail-soft: Redis 조회/저장 예외 시 로그 후 DB 폴백(빈 catch 금지 룰 준수, 예외를 삼키지 않고 로깅 후 폴백 분기).

## 설정 추가

- `user-service/build.gradle`: `spring-boot-starter-data-redis` 추가.
- `application.yml`/`application-local.yml`: `spring.data.redis.host/port` (order-service와 동일 패턴,
  `REDIS_HOST`/`REDIS_PORT` 환경변수, 기본 localhost:6379).
- 루트 `docker-compose.yml`엔 redis 서비스 자체가 없음(order-service도 동일 — 기존 갭). 공용 인프라 파일이라
  이번 작업에서 건드리지 않음.

## 테스트

- `RedisAuthorizationCacheAdapter`: `RedisOrderExpirationStoreTest` 패턴대로 Mockito로
  `StringRedisTemplate`/`HashOperations` mock — 실제 Redis 불필요.
- `AuthorizeApplicationService`: hit/miss/캐시 적재 경로 단위테스트.
- Controller: MockMvc 슬라이스 테스트(200/404).
- 무효화 훅: 기존 `AdminUserApplicationService`/`AdminSellerApplicationService` 테스트에 evict 호출 검증 추가.

## 기획문서.md 반영

공개 API 표에 안 맞으므로 "내부 API(Internal)" 섹션을 신설해 이 항목을 체크한다.
