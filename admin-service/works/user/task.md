# user-service 어드민 API → admin-service 이관 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** user-service의 어드민 API 6개(`GET/PATCH /admin/users*`, `GET /admin/stats/users`, `GET/PATCH /admin/sellers/register*`)를 admin-service로 이관하고, user-service의 대응 코드를 삭제한다.

**Architecture:** admin-service가 user_service 스키마(`user`, `user_role`, `seller_register`, `seller_register_category`, `refresh_token`)를 own JPA 엔티티로 직접 읽고 쓴다(settlement/order 이관 전례와 동일, 네트워크 클라이언트 없음). 이번 이관은 order/settlement와 달리 **쓰기(mutating) 엔드포인트 3개**를 포함하므로, admin-service가 `User`/`SellerRegister`를 읽기+쓰기 겸용 own entity로 갖고 gateway forward-auth가 참조하는 Redis 인가 캐시·세션(refresh_token) 무효화 로직까지 `com.prompthub.admin.auth` 지원 패키지로 복제한다. 게이트웨이 라우트 소유권을 user-service에서 admin-service로 옮기고, user-service의 `com.prompthub.user.admin` 패키지 전체를 삭제하는 hard cutover.

**Tech Stack:** Spring Boot, Spring Data JPA(Specification), Spring Data Redis, H2(테스트), JUnit5 + Mockito + AssertJ.

## Global Constraints

- 새 코드는 `com.prompthub.admin.user`(회원 관리), `com.prompthub.admin.seller`(판매자 등록 심사), `com.prompthub.admin.auth`(세션/캐시 지원, 공개 API 없음) 세 패키지. 계층 구조는 기존 `com.prompthub.admin.settlement`/`order`와 동일(`presentation/application/domain/infrastructure`).
- 클래스명에서 "Admin" 접두사 제거(`UserController`, `SellerController`, `UserUseCase`, `SellerUseCase` 등) — settlement/order 컨벤션과 통일.
- 컨트롤러 경로는 `${api.init}/admin`(admin-service 기준 `/api/v2/admin`으로 해석됨) — user-service 원본도 이미 `/api/v2/admin`이므로 이번엔 API 버전 자체는 바뀌지 않는다(design.md 결정 6).
- **컬럼 매핑 주의**: user-service `db/migration/V1__baseline.sql` 확인 결과 `"user"` 테이블의 실제 PK 컬럼명은 `id`(`user_id`가 아님). 이 플랜의 `User`/`SellerRegister`/`RefreshToken` 엔티티는 전부 실제 DDL 기준 컬럼명을 쓴다. **참고**: 기존 `admin-service`의 `com.prompthub.admin.order.domain.model.SellerNickname`(order 이관 산출물)은 `@Column(name = "user_id")`로 `"user"` 테이블 PK를 매핑하고 있는데, 이는 실제 컬럼명(`id`)과 다르다 — 운영 환경에서 판매자 닉네임 조회 시 SQL 오류가 날 수 있는 기존 버그로 보인다. 이 플랜은 `order` 패키지를 건드리지 않으므로 고치지 않는다(범위 밖, 별도 확인 필요 — task.md 최하단 "발견된 기존 이슈" 참고).
- `User`/`SellerRegister`는 이번 6개 엔드포인트가 실제로 참조하는 컬럼만 매핑한다(`User`엔 `profile_image_url`/`terms_agreed` 매핑 안 함, `SellerRegister`엔 `agreed_to_terms` 매핑 안 함) — order 이관의 "실제 참조하는 컬럼만 매핑한다" 원칙 그대로 적용.
- `RefreshTokenRepository`/`AuthorizationCacheRepository` 포트는 어드민 액션이 실제 쓰는 메서드만 선언하는 축소 인터페이스(`deleteByUserId`/`evict` 단일 메서드) — design.md 결정 7.
- 에러는 admin-service 자체 `AdminException`/`AdminErrorCode`를 사용한다. `USER_NOT_FOUND`(A-007, 404), `SELLER_REGISTER_NOT_FOUND`(A-008, 404) 신규 추가, "이미 심사됨" 가드와 "허용되지 않는 상태/역할 문자열"은 기존 `INVALID_INPUT_VALUE`(A-001, 400) 재사용(원본 user-service가 400으로 응답하던 것과 동일 상태코드 유지). `GlobalExceptionHandler`는 이미 `BusinessException`을 공통 처리하므로 신규 예외 핸들러 추가 불필요.
- **원본 버그 수정 1건**: user-service `AdminUserController.changeUserStatus`는 `status` 쿼리 문자열이 `"ALL"`이면 `parseStatus`가 `null`을 반환하고, 그 `null`이 그대로 커맨드로 넘어가 서비스 계층의 `switch(status){case ACTIVE...}`에서 `NullPointerException`(500으로 응답)을 낸다. 상태변경 엔드포인트는 원래 `ALL`을 받을 이유가 없으므로, admin-service 이관본은 상태변경 경로에서 `ALL`/미인식 값을 전부 `AdminException(INVALID_INPUT_VALUE)`(400)로 막는다. 목록 조회 필터의 `ALL`→`null` 동작은 그대로 유지.
- 커밋 메시지는 `<타입>: <내용>` 컨벤션(`feat`, `test`, `chore`, `refactor`)을 따른다.
- 모든 `./gradlew` 명령은 저장소 루트(`/Users/anjinpyo/developments/dev-course/projects/beadv6_6_3JMT_BE`)에서 실행한다.

---

### Task 1: `com.prompthub.admin.auth` 지원 패키지 — Redis 인가캐시 무효화 + 세션(RT) 삭제

**Files:**
- Modify: `admin-service/build.gradle`
- Modify: `config/src/main/resources/configs/admin-service.yml`
- Create: `admin-service/src/main/java/com/prompthub/admin/auth/domain/repository/AuthorizationCacheRepository.java`
- Create: `admin-service/src/main/java/com/prompthub/admin/auth/infrastructure/redis/RedisAuthorizationCacheAdapter.java`
- Create: `admin-service/src/main/java/com/prompthub/admin/auth/domain/model/RefreshToken.java`
- Create: `admin-service/src/main/java/com/prompthub/admin/auth/domain/repository/RefreshTokenRepository.java`
- Create: `admin-service/src/main/java/com/prompthub/admin/auth/infrastructure/persistence/RefreshTokenJpaRepository.java`
- Create: `admin-service/src/main/java/com/prompthub/admin/auth/infrastructure/persistence/RefreshTokenRepositoryAdapter.java`
- Create: `admin-service/src/main/java/com/prompthub/admin/auth/application/usecase/SessionRevocationUseCase.java`
- Create: `admin-service/src/main/java/com/prompthub/admin/auth/application/service/SessionRevocationApplicationService.java`
- Test: `admin-service/src/test/java/com/prompthub/admin/auth/infrastructure/redis/RedisAuthorizationCacheAdapterTest.java`
- Test: `admin-service/src/test/java/com/prompthub/admin/auth/infrastructure/persistence/RefreshTokenRepositoryAdapterTest.java`
- Test: `admin-service/src/test/java/com/prompthub/admin/auth/application/service/SessionRevocationApplicationServiceTest.java`

**Interfaces:**
- Produces: `AuthorizationCacheRepository.evict(UUID): void`, `RefreshTokenRepository.deleteByUserId(UUID): void`, `SessionRevocationUseCase.revoke(UUID): void` — Task 3(`UserApplicationService`)과 Task 6(`SellerApplicationService`)이 이 세 포트를 그대로 주입받아 쓴다.

- [x] **Step 1: admin-service build.gradle에 Redis 의존성 추가**

`admin-service/build.gradle`의 `dependencies` 블록에 아래 줄을 추가한다(기존 QueryDSL·springdoc 줄은 그대로 둔다):

```gradle
    // Redis — 어드민 액션(상태변경/판매자 승인) 시 user-service 인가 캐시 무효화용
    implementation 'org.springframework.boot:spring-boot-starter-data-redis'
```

- [x] **Step 2: config 모듈에 admin-service Redis 접속 정보 추가**

`config/src/main/resources/configs/admin-service.yml`의 `server:` 블록 다음에 추가한다(user-service.yml과 동일한 환경변수 플레이스홀더 — 같은 Redis 인스턴스를 공유):

```yaml
  data:
    redis:
      host: ${REDIS_HOST}
      port: ${REDIS_PORT}
```

**남는 범위 밖 선행조건**: 루트 `docker-compose.yml`의 `admin-service` 서비스 `environment` 블록엔 아직 `REDIS_HOST`/`REDIS_PORT`가 없다(user-service 블록엔 이미 있음 — `docker-compose.yml:122`). 위 값을 채워도 컨테이너가 그 환경변수를 실제로 받아야 치환이 값을 갖는다. `docker-compose.yml`은 `user-service`/`admin-service`/`apigateway` 세 모듈 밖의 루트 인프라 파일이라 이 플랜은 손대지 않는다 — 별도로 채워야 한다는 사실만 기록해 둔다.

- [x] **Step 3: 실패하는 RedisAuthorizationCacheAdapterTest 작성**

`admin-service/src/test/java/com/prompthub/admin/auth/infrastructure/redis/RedisAuthorizationCacheAdapterTest.java`:

```java
package com.prompthub.admin.auth.infrastructure.redis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

@ExtendWith(MockitoExtension.class)
class RedisAuthorizationCacheAdapterTest {

	private static final UUID USER_ID = UUID.randomUUID();
	private static final String KEY = "user:authz:" + USER_ID;

	@Mock
	private StringRedisTemplate redisTemplate;

	private RedisAuthorizationCacheAdapter adapter() {
		return new RedisAuthorizationCacheAdapter(redisTemplate);
	}

	@Test
	void evict_해당_유저의_인가_캐시_키를_지운다() {
		adapter().evict(USER_ID);

		then(redisTemplate).should().delete(KEY);
	}

	@Test
	void evict_Redis_장애면_예외를_삼키고_로그만_남긴다() {
		willThrow(new RedisConnectionFailureException("connection refused"))
			.given(redisTemplate).delete(KEY);

		assertThatCode(() -> adapter().evict(USER_ID)).doesNotThrowAnyException();
	}
}
```

- [x] **Step 4: 테스트 실행해서 컴파일 실패 확인**

Run: `./gradlew :admin-service:test --tests "com.prompthub.admin.auth.infrastructure.redis.RedisAuthorizationCacheAdapterTest"`
Expected: FAIL — `AuthorizationCacheRepository`, `RedisAuthorizationCacheAdapter` 클래스가 없어 컴파일 에러.

- [x] **Step 5: AuthorizationCacheRepository 포트 + RedisAuthorizationCacheAdapter 구현**

`admin-service/src/main/java/com/prompthub/admin/auth/domain/repository/AuthorizationCacheRepository.java`:

```java
package com.prompthub.admin.auth.domain.repository;

import java.util.UUID;

public interface AuthorizationCacheRepository {
	void evict(UUID userId);
}
```

`admin-service/src/main/java/com/prompthub/admin/auth/infrastructure/redis/RedisAuthorizationCacheAdapter.java`:

```java
package com.prompthub.admin.auth.infrastructure.redis;

import com.prompthub.admin.auth.domain.repository.AuthorizationCacheRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * user-service 소유 인가 캐시(Redis, 키 "user:authz:{userId}", 60초 TTL)의
 * 무효화 전용 어댑터. gateway forward-auth가 이 캐시를 읽으므로, 어드민이
 * 회원 상태·역할을 바꾸면 여기서 evict해야 최대 60초짜리 stale 인가가 안 생긴다.
 * 캐시 적재/조회(find/save)는 로그인 경로 전용이라 admin-service엔 없다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisAuthorizationCacheAdapter implements AuthorizationCacheRepository {

	private static final String KEY_PREFIX = "user:authz:";

	private final StringRedisTemplate redisTemplate;

	@Override
	public void evict(UUID userId) {
		try {
			redisTemplate.delete(KEY_PREFIX + userId);
		} catch (DataAccessException e) {
			log.warn("authorize 캐시 무효화 실패. userId={}", userId, e);
		}
	}
}
```

- [x] **Step 6: 테스트 실행해서 통과 확인**

Run: `./gradlew :admin-service:test --tests "com.prompthub.admin.auth.infrastructure.redis.RedisAuthorizationCacheAdapterTest"`
Expected: PASS

- [x] **Step 7: 실패하는 RefreshTokenRepositoryAdapterTest 작성**

`admin-service/src/test/java/com/prompthub/admin/auth/infrastructure/persistence/RefreshTokenRepositoryAdapterTest.java`:

```java
package com.prompthub.admin.auth.infrastructure.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;

import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class RefreshTokenRepositoryAdapterTest {

	@Mock
	private RefreshTokenJpaRepository refreshTokenJpaRepository;

	@Mock
	private StringRedisTemplate redisTemplate;

	private RefreshTokenRepositoryAdapter adapter() {
		return new RefreshTokenRepositoryAdapter(refreshTokenJpaRepository, redisTemplate);
	}

	@Test
	void deleteByUserId_RDB와_Redis_모두_삭제() {
		UUID userId = UUID.randomUUID();

		adapter().deleteByUserId(userId);

		then(refreshTokenJpaRepository).should().deleteByUserId(userId);
		then(redisTemplate).should().delete("refresh_token:" + userId);
	}
}
```

- [x] **Step 8: 테스트 실행해서 컴파일 실패 확인**

Run: `./gradlew :admin-service:test --tests "com.prompthub.admin.auth.infrastructure.persistence.RefreshTokenRepositoryAdapterTest"`
Expected: FAIL — `RefreshTokenJpaRepository`, `RefreshTokenRepositoryAdapter` 클래스가 없어 컴파일 에러.

- [x] **Step 9: RefreshToken 엔티티 + 포트 + JpaRepository + Adapter 구현**

`admin-service/src/main/java/com/prompthub/admin/auth/domain/model/RefreshToken.java`:

```java
package com.prompthub.admin.auth.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * user-service 소유 refresh_token 테이블의 읽기+삭제 재매핑. 어드민 세션
 * 폐기(삭제) 전용이라 id·user_id만 매핑한다 — token/epoch/expires_at은
 * 발급·회전 전용 컬럼이라 admin-service가 쓰지 않는다.
 */
@Entity
@Table(name = "refresh_token")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken {

	@Id
	@Column(name = "id", columnDefinition = "uuid")
	private UUID id;

	@Column(name = "user_id", nullable = false, columnDefinition = "uuid")
	private UUID userId;
}
```

`admin-service/src/main/java/com/prompthub/admin/auth/domain/repository/RefreshTokenRepository.java`:

```java
package com.prompthub.admin.auth.domain.repository;

import java.util.UUID;

public interface RefreshTokenRepository {
	void deleteByUserId(UUID userId);
}
```

`admin-service/src/main/java/com/prompthub/admin/auth/infrastructure/persistence/RefreshTokenJpaRepository.java`:

```java
package com.prompthub.admin.auth.infrastructure.persistence;

import com.prompthub.admin.auth.domain.model.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RefreshTokenJpaRepository extends JpaRepository<RefreshToken, UUID> {
	void deleteByUserId(UUID userId);
}
```

`admin-service/src/main/java/com/prompthub/admin/auth/infrastructure/persistence/RefreshTokenRepositoryAdapter.java`:

```java
package com.prompthub.admin.auth.infrastructure.persistence;

import com.prompthub.admin.auth.domain.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class RefreshTokenRepositoryAdapter implements RefreshTokenRepository {

	private static final String CACHE_KEY_PREFIX = "refresh_token:";

	private final RefreshTokenJpaRepository refreshTokenJpaRepository;
	private final StringRedisTemplate redisTemplate;

	@Override
	public void deleteByUserId(UUID userId) {
		refreshTokenJpaRepository.deleteByUserId(userId);
		evictCache(userId);
	}

	private void evictCache(UUID userId) {
		try {
			redisTemplate.delete(CACHE_KEY_PREFIX + userId);
		} catch (DataAccessException e) {
			// RDB 삭제(세션 무효화)는 끝났지만 캐시 삭제가 실패한 상태 — user-service
			// 원본과 동일하게 보안 경보용 error로 남긴다(warn 아님).
			log.error("Redis 캐시 삭제 실패 — 세션 무효화가 캐시에는 반영되지 않았을 수 있음. userId={}", userId, e);
		}
	}
}
```

- [x] **Step 10: 테스트 실행해서 통과 확인**

Run: `./gradlew :admin-service:test --tests "com.prompthub.admin.auth.infrastructure.persistence.RefreshTokenRepositoryAdapterTest"`
Expected: PASS

- [x] **Step 11: 실패하는 SessionRevocationApplicationServiceTest 작성**

`admin-service/src/test/java/com/prompthub/admin/auth/application/service/SessionRevocationApplicationServiceTest.java`:

```java
package com.prompthub.admin.auth.application.service;

import com.prompthub.admin.auth.domain.repository.AuthorizationCacheRepository;
import com.prompthub.admin.auth.domain.repository.RefreshTokenRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class SessionRevocationApplicationServiceTest {

	@Mock
	private RefreshTokenRepository refreshTokenRepository;

	@Mock
	private AuthorizationCacheRepository authorizationCacheRepository;

	@InjectMocks
	private SessionRevocationApplicationService sessionRevocationService;

	@Test
	void revoke_유저의_모든_RT를_삭제한다() {
		UUID userId = UUID.randomUUID();

		sessionRevocationService.revoke(userId);

		then(refreshTokenRepository).should().deleteByUserId(userId);
	}

	@Test
	void revoke_authorize_캐시를_무효화한다() {
		UUID userId = UUID.randomUUID();

		sessionRevocationService.revoke(userId);

		then(authorizationCacheRepository).should().evict(userId);
	}
}
```

- [x] **Step 12: 테스트 실행해서 컴파일 실패 확인**

Run: `./gradlew :admin-service:test --tests "com.prompthub.admin.auth.application.service.SessionRevocationApplicationServiceTest"`
Expected: FAIL — `SessionRevocationUseCase`, `SessionRevocationApplicationService` 클래스가 없어 컴파일 에러.

- [x] **Step 13: SessionRevocationUseCase + SessionRevocationApplicationService 구현**

`admin-service/src/main/java/com/prompthub/admin/auth/application/usecase/SessionRevocationUseCase.java`:

```java
package com.prompthub.admin.auth.application.usecase;

import java.util.UUID;

public interface SessionRevocationUseCase {
	void revoke(UUID userId);
}
```

`admin-service/src/main/java/com/prompthub/admin/auth/application/service/SessionRevocationApplicationService.java`:

```java
package com.prompthub.admin.auth.application.service;

import com.prompthub.admin.auth.application.usecase.SessionRevocationUseCase;
import com.prompthub.admin.auth.domain.repository.AuthorizationCacheRepository;
import com.prompthub.admin.auth.domain.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SessionRevocationApplicationService implements SessionRevocationUseCase {

	private final RefreshTokenRepository refreshTokenRepository;
	private final AuthorizationCacheRepository authorizationCacheRepository;

	@Override
	@Transactional
	public void revoke(UUID userId) {
		refreshTokenRepository.deleteByUserId(userId);
		authorizationCacheRepository.evict(userId);
	}
}
```

- [x] **Step 14: 테스트 실행해서 통과 확인**

Run: `./gradlew :admin-service:test --tests "com.prompthub.admin.auth.application.service.SessionRevocationApplicationServiceTest"`
Expected: PASS

- [x] **Step 15: 커밋**

```bash
git add admin-service/build.gradle \
  config/src/main/resources/configs/admin-service.yml \
  admin-service/src/main/java/com/prompthub/admin/auth \
  admin-service/src/test/java/com/prompthub/admin/auth
git commit -m "$(cat <<'EOF'
feat: 어드민 인가캐시 무효화·세션폐기 인프라를 admin-service에 구축

- user-service auth 도메인의 인가캐시(Redis) evict, refresh_token 삭제,
  세션폐기(SessionRevocationUseCase) 로직을 com.prompthub.admin.auth로
  1:1 복제 — 이후 회원 상태변경/판매자 승인 어드민 액션이 gateway
  forward-auth 인가 캐시(60초 TTL)를 무효화하는 데 쓴다
- 원본과 달리 포트를 실제 쓰는 메서드만 남긴 축소 인터페이스로 정의:
  AuthorizationCacheRepository는 evict만, RefreshTokenRepository는
  deleteByUserId만(발급·회전·조회는 로그인 경로 전용이라 어드민 액션이
  안 씀) — design.md 결정 7
- admin-service에 Redis 의존성 신규 추가, config 모듈에 접속 정보 추가
  (user-service.yml과 동일한 REDIS_HOST/PORT — 같은 Redis 인스턴스 공유)
- 루트 docker-compose.yml의 admin-service 환경변수엔 REDIS_HOST/PORT가
  아직 없음(user-service 블록엔 있음) — 세 모듈 밖 루트 인프라 파일이라
  이 커밋에서 안 건드림, 별도로 채워야 함
EOF
)"
```

---

### Task 2: `com.prompthub.admin.user` 도메인 — User 엔티티 + UserRepository

**Files:**
- Create: `admin-service/src/main/java/com/prompthub/admin/user/domain/model/UserRole.java`
- Create: `admin-service/src/main/java/com/prompthub/admin/user/domain/model/UserStatus.java`
- Create: `admin-service/src/main/java/com/prompthub/admin/user/domain/model/User.java`
- Create: `admin-service/src/main/java/com/prompthub/admin/user/domain/repository/UserRepository.java`
- Create: `admin-service/src/main/java/com/prompthub/admin/user/infrastructure/persistence/UserSpecifications.java`
- Create: `admin-service/src/main/java/com/prompthub/admin/user/infrastructure/persistence/UserJpaRepository.java`
- Create: `admin-service/src/main/java/com/prompthub/admin/user/infrastructure/persistence/UserRepositoryAdapter.java`
- Test: `admin-service/src/test/java/com/prompthub/admin/user/infrastructure/persistence/UserRepositoryAdapterTest.java`
- Create: `admin-service/src/test/resources/sql/users.sql`

**Interfaces:**
- Produces: `UserRepository.findById(UUID): Optional<User>`, `.save(User): User`, `.findUsers(UserStatus, UserRole, String, int, int): List<User>`, `.countUsers(UserStatus, UserRole, String): long`, `.countCreatedBetween(LocalDateTime, LocalDateTime): long`, `.findAllByIds(List<UUID>): List<User>` — Task 3(`UserApplicationService`)과 Task 6(`SellerApplicationService`)이 이 포트를 그대로 소비한다.
- Produces: `User`(도메인 메서드 `activate()`, `block()`, `withdraw()`, `addRole(UserRole)`, `getPrimaryRole(): UserRole`, `getRoles(): Set<UserRole>`, `BaseEntity`로부터 `getCreatedAt()`/`getUpdatedAt()`) — Task 3·6이 그대로 참조한다.

- [x] **Step 1: SQL 픽스처 작성**

`admin-service/src/test/resources/sql/users.sql`:

```sql
-- 어드민 회원 목록/통계/상태변경 쿼리 검증용 픽스처.
-- H2 스키마는 application-test.yml 안내대로 admin 재매핑 엔티티(User) 기준으로 생성된다(ddl-auto: create-drop).
INSERT INTO "user" (id, name, email, status, created_at, updated_at) VALUES
('11111111-0000-0000-0000-000000000001', '김도윤', 'doyoon.kim@gmail.com', 'ACTIVE', '2026-07-01 09:00:00', '2026-07-01 09:00:00'),
('11111111-0000-0000-0000-000000000002', '이서아', 'seoah@example.com', 'ACTIVE', '2026-07-02 10:00:00', '2026-07-02 10:00:00'),
('11111111-0000-0000-0000-000000000003', '박준호', 'junho@example.com', 'BLOCKED', '2026-06-01 08:00:00', '2026-06-01 08:00:00');

INSERT INTO user_role (user_id, role) VALUES
('11111111-0000-0000-0000-000000000001', 'BUYER'),
('11111111-0000-0000-0000-000000000002', 'SELLER'),
('11111111-0000-0000-0000-000000000003', 'BUYER');
```

- [x] **Step 2: 실패하는 UserRepositoryAdapterTest 작성**

`admin-service/src/test/java/com/prompthub/admin/user/infrastructure/persistence/UserRepositoryAdapterTest.java`:

```java
package com.prompthub.admin.user.infrastructure.persistence;

import com.prompthub.admin.user.domain.model.User;
import com.prompthub.admin.user.domain.model.UserRole;
import com.prompthub.admin.user.domain.model.UserStatus;
import com.prompthub.admin.user.domain.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(UserRepositoryAdapter.class)
@ActiveProfiles("test")
@Sql("/sql/users.sql")
class UserRepositoryAdapterTest {

	private static final UUID USER_1 = UUID.fromString("11111111-0000-0000-0000-000000000001");

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private TestEntityManager entityManager;

	@Test
	void 상태와_역할로_필터링해서_목록을_조회한다() {
		List<User> result = userRepository.findUsers(UserStatus.ACTIVE, UserRole.SELLER, null, 0, 20);

		assertThat(result).hasSize(1);
		assertThat(result.getFirst().getUserId()).isEqualTo(UUID.fromString("11111111-0000-0000-0000-000000000002"));
	}

	@Test
	void 키워드로_이름_이메일을_검색한다() {
		long count = userRepository.countUsers(null, null, "김도윤");

		assertThat(count).isEqualTo(1);
	}

	@Test
	void 생성일_구간으로_신규가입자_수를_센다() {
		long count = userRepository.countCreatedBetween(
			LocalDateTime.of(2026, 7, 1, 0, 0),
			LocalDateTime.of(2026, 7, 3, 0, 0)
		);

		assertThat(count).isEqualTo(2);
	}

	@Test
	void 상태변경_저장_후_재조회하면_바뀐_상태가_유지된다() {
		User user = userRepository.findById(USER_1).orElseThrow();

		user.block();
		userRepository.save(user);

		entityManager.flush();
		entityManager.clear();

		Optional<User> reloaded = userRepository.findById(USER_1);
		assertThat(reloaded).isPresent();
		assertThat(reloaded.get().getStatus()).isEqualTo(UserStatus.BLOCKED);
	}

	@Test
	void id_목록으로_여러_유저를_한번에_조회한다() {
		List<User> result = userRepository.findAllByIds(List.of(
			USER_1,
			UUID.fromString("11111111-0000-0000-0000-000000000002"),
			UUID.fromString("11111111-0000-0000-0000-000000000999")
		));

		assertThat(result).hasSize(2);
	}
}
```

- [x] **Step 3: 테스트 실행해서 컴파일 실패 확인**

Run: `./gradlew :admin-service:test --tests "com.prompthub.admin.user.infrastructure.persistence.UserRepositoryAdapterTest"`
Expected: FAIL — `User`, `UserRepository`, `UserRepositoryAdapter` 클래스가 없어 컴파일 에러.

- [x] **Step 4: UserRole/UserStatus enum + User 엔티티 작성**

`admin-service/src/main/java/com/prompthub/admin/user/domain/model/UserRole.java`:

```java
package com.prompthub.admin.user.domain.model;

public enum UserRole {
	BUYER,
	SELLER,
	ADMIN
}
```

`admin-service/src/main/java/com/prompthub/admin/user/domain/model/UserStatus.java`:

```java
package com.prompthub.admin.user.domain.model;

public enum UserStatus {
	ACTIVE,
	BLOCKED,
	WITHDRAWN
}
```

`admin-service/src/main/java/com/prompthub/admin/user/domain/model/User.java`:

```java
package com.prompthub.admin.user.domain.model;

import com.prompthub.admin.global.common.BaseEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * user-service 소유 "user" 테이블(+ user_role)의 읽기+쓰기 재매핑. 어드민
 * 액션(목록/통계/상태변경/판매자 승인 시 역할 부여)이 실제로 참조하는
 * 컬럼만 매핑한다 — profile_image_url·terms_agreed는 이 엔드포인트들이
 * 안 써서 매핑하지 않았다. PK 컬럼명은 실제 DDL 기준 "id"(user_role의
 * FK 컬럼명은 "user_id"라 헷갈리기 쉬우니 주의).
 * 상태·역할 규칙의 소유자는 user-service User — 불변식이 바뀌면 같이 맞춘다.
 */
@Entity
@Table(name = "\"user\"")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseEntity {

	@Id
	@Column(name = "id", columnDefinition = "uuid")
	private UUID userId;

	@Column(name = "name", nullable = false, length = 100)
	private String name;

	@Column(name = "email", nullable = false, length = 255, unique = true)
	private String email;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private UserStatus status;

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "user_role", joinColumns = @JoinColumn(name = "user_id"))
	@Enumerated(EnumType.STRING)
	@Column(name = "role", nullable = false, length = 20)
	@Getter(AccessLevel.NONE)
	private Set<UserRole> roles = new HashSet<>();

	public Set<UserRole> getRoles() {
		return Collections.unmodifiableSet(roles);
	}

	public UserRole getPrimaryRole() {
		if (roles.contains(UserRole.ADMIN)) return UserRole.ADMIN;
		if (roles.contains(UserRole.SELLER)) return UserRole.SELLER;
		return UserRole.BUYER;
	}

	public void addRole(UserRole role) {
		this.roles.add(role);
	}

	public void activate() {
		this.status = UserStatus.ACTIVE;
	}

	public void block() {
		this.status = UserStatus.BLOCKED;
	}

	public void withdraw() {
		this.status = UserStatus.WITHDRAWN;
	}
}
```

- [x] **Step 5: UserRepository 포트 + UserSpecifications + UserJpaRepository + UserRepositoryAdapter 작성**

`admin-service/src/main/java/com/prompthub/admin/user/domain/repository/UserRepository.java`:

```java
package com.prompthub.admin.user.domain.repository;

import com.prompthub.admin.user.domain.model.User;
import com.prompthub.admin.user.domain.model.UserRole;
import com.prompthub.admin.user.domain.model.UserStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository {
	Optional<User> findById(UUID userId);
	User save(User user);
	List<User> findUsers(UserStatus status, UserRole role, String keyword, int page, int size);
	long countUsers(UserStatus status, UserRole role, String keyword);
	long countCreatedBetween(LocalDateTime from, LocalDateTime to);
	List<User> findAllByIds(List<UUID> userIds);
}
```

`admin-service/src/main/java/com/prompthub/admin/user/infrastructure/persistence/UserSpecifications.java`:

```java
package com.prompthub.admin.user.infrastructure.persistence;

import com.prompthub.admin.user.domain.model.User;
import com.prompthub.admin.user.domain.model.UserRole;
import com.prompthub.admin.user.domain.model.UserStatus;
import org.springframework.data.jpa.domain.Specification;

public class UserSpecifications {

	private UserSpecifications() {
	}

	public static Specification<User> withStatus(UserStatus status) {
		if (status == null) return (root, query, cb) -> cb.conjunction();
		return (root, query, cb) -> cb.equal(root.get("status"), status);
	}

	public static Specification<User> withRole(UserRole role) {
		if (role == null) return (root, query, cb) -> cb.conjunction();
		return (root, query, cb) -> cb.isMember(role, root.get("roles"));
	}

	public static Specification<User> withKeyword(String keyword) {
		if (keyword == null || keyword.isBlank()) return (root, query, cb) -> cb.conjunction();
		return (root, query, cb) -> {
			String pattern = "%" + keyword.toLowerCase() + "%";
			return cb.or(
				cb.like(cb.lower(root.get("name")), pattern),
				cb.like(cb.lower(root.get("email")), pattern)
			);
		};
	}
}
```

`admin-service/src/main/java/com/prompthub/admin/user/infrastructure/persistence/UserJpaRepository.java`:

```java
package com.prompthub.admin.user.infrastructure.persistence;

import com.prompthub.admin.user.domain.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.UUID;

public interface UserJpaRepository extends JpaRepository<User, UUID>, JpaSpecificationExecutor<User> {

	@Query("SELECT COUNT(u) FROM User u WHERE u.createdAt >= :from AND u.createdAt < :to")
	long countByCreatedAtBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
```

`admin-service/src/main/java/com/prompthub/admin/user/infrastructure/persistence/UserRepositoryAdapter.java`:

```java
package com.prompthub.admin.user.infrastructure.persistence;

import com.prompthub.admin.user.domain.model.User;
import com.prompthub.admin.user.domain.model.UserRole;
import com.prompthub.admin.user.domain.model.UserStatus;
import com.prompthub.admin.user.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class UserRepositoryAdapter implements UserRepository {

	private final UserJpaRepository userJpaRepository;

	@Override
	public Optional<User> findById(UUID userId) {
		return userJpaRepository.findById(userId);
	}

	@Override
	public User save(User user) {
		return userJpaRepository.save(user);
	}

	@Override
	public List<User> findUsers(UserStatus status, UserRole role, String keyword, int page, int size) {
		Specification<User> spec = buildSpec(status, role, keyword);
		return userJpaRepository.findAll(spec, PageRequest.of(page, size)).getContent();
	}

	@Override
	public long countUsers(UserStatus status, UserRole role, String keyword) {
		Specification<User> spec = buildSpec(status, role, keyword);
		return userJpaRepository.count(spec);
	}

	@Override
	public long countCreatedBetween(LocalDateTime from, LocalDateTime to) {
		return userJpaRepository.countByCreatedAtBetween(from, to);
	}

	@Override
	public List<User> findAllByIds(List<UUID> userIds) {
		return userJpaRepository.findAllById(userIds);
	}

	private Specification<User> buildSpec(UserStatus status, UserRole role, String keyword) {
		return UserSpecifications.withStatus(status)
			.and(UserSpecifications.withRole(role))
			.and(UserSpecifications.withKeyword(keyword));
	}
}
```

- [x] **Step 6: 테스트 실행해서 통과 확인**

Run: `./gradlew :admin-service:test --tests "com.prompthub.admin.user.infrastructure.persistence.UserRepositoryAdapterTest"`
Expected: PASS(5개 테스트)

- [x] **Step 7: 커밋**

```bash
git add admin-service/src/main/java/com/prompthub/admin/user/domain \
  admin-service/src/main/java/com/prompthub/admin/user/infrastructure \
  admin-service/src/test/java/com/prompthub/admin/user/infrastructure/persistence/UserRepositoryAdapterTest.java \
  admin-service/src/test/resources/sql/users.sql
git commit -m "$(cat <<'EOF'
feat: 어드민 회원 관리용 User 읽기+쓰기 엔티티 admin-service에 추가

- user-service User 도메인(activate/block/withdraw/addRole)을
  com.prompthub.admin.user로 1:1 복제 — order/settlement 전례와 달리
  이번엔 쓰기까지 필요해 own read-write entity로 둔다
- 이 6개 어드민 엔드포인트가 실제 참조하는 컬럼만 매핑
  (profile_image_url·terms_agreed 제외)
- 목록 필터링은 원본과 동일하게 QueryDSL이 아니라 Spring Data JPA
  Specification 사용
- 테스트는 SQL 픽스처로 데이터를 넣고 저장 후 재조회로 상태 반영을
  검증(admin-service 기존 SettlementRepositoryAdapterTest 컨벤션과 동일)
EOF
)"
```

---

### Task 3: `com.prompthub.admin.user` Application 레이어 (UserUseCase, UserApplicationService)

**Files:**
- Modify: `admin-service/src/main/java/com/prompthub/admin/global/exception/AdminErrorCode.java`
- Create: `admin-service/src/main/java/com/prompthub/admin/user/application/dto/UserListQuery.java`
- Create: `admin-service/src/main/java/com/prompthub/admin/user/application/dto/UserPageResult.java`
- Create: `admin-service/src/main/java/com/prompthub/admin/user/application/dto/UserSummaryResult.java`
- Create: `admin-service/src/main/java/com/prompthub/admin/user/application/dto/UserStatsResult.java`
- Create: `admin-service/src/main/java/com/prompthub/admin/user/application/dto/ChangeUserStatusCommand.java`
- Create: `admin-service/src/main/java/com/prompthub/admin/user/application/dto/UserStatusResult.java`
- Create: `admin-service/src/main/java/com/prompthub/admin/user/application/usecase/UserUseCase.java`
- Create: `admin-service/src/main/java/com/prompthub/admin/user/application/service/UserApplicationService.java`
- Test: `admin-service/src/test/java/com/prompthub/admin/user/application/service/UserApplicationServiceTest.java`

**Interfaces:**
- Consumes: Task 1의 `AuthorizationCacheRepository.evict`, `SessionRevocationUseCase.revoke`. Task 2의 `UserRepository`.
- Produces: `UserUseCase.listUsers(UserListQuery): UserPageResult`, `.changeUserStatus(ChangeUserStatusCommand): UserStatusResult`, `.getUserStats(): UserStatsResult` — Task 4의 `UserController`가 그대로 주입받아 호출한다. `AdminErrorCode.USER_NOT_FOUND`(A-007) — Task 6도 동일 코드를 재사용한다.

- [x] **Step 1: AdminErrorCode에 USER_NOT_FOUND 추가**

`admin-service/src/main/java/com/prompthub/admin/global/exception/AdminErrorCode.java`에서 enum 상수 목록을 다음으로 교체한다(`SETTLEMENT_ALREADY_CANCELLED` 다음에 콤마 유지, 새 상수 2개 추가 — 두 번째는 Task 6에서 씀):

```java
	INVALID_INPUT_VALUE("A-001", "요청 값이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
	INTERNAL_SERVER_ERROR("A-002", "서버 내부 오류가 발생했습니다.", HttpStatus.INTERNAL_SERVER_ERROR),
	SETTLEMENT_NOT_FOUND("A-003", "정산을 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
	SETTLEMENT_INVALID_STATE("A-004", "현재 상태에서 변경할 수 없는 정산입니다.", HttpStatus.CONFLICT),
	SETTLEMENT_ALREADY_PAID("A-005", "이미 지급 완료된 정산은 취소할 수 없습니다.", HttpStatus.CONFLICT),
	SETTLEMENT_ALREADY_CANCELLED("A-006", "이미 취소된 정산입니다.", HttpStatus.CONFLICT),
	USER_NOT_FOUND("A-007", "사용자를 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
	SELLER_REGISTER_NOT_FOUND("A-008", "판매자 등록 신청 내역이 없습니다.", HttpStatus.NOT_FOUND);
```

- [x] **Step 2: 응용 DTO 작성**

`admin-service/src/main/java/com/prompthub/admin/user/application/dto/UserListQuery.java`:

```java
package com.prompthub.admin.user.application.dto;

import com.prompthub.admin.user.domain.model.UserRole;
import com.prompthub.admin.user.domain.model.UserStatus;

public record UserListQuery(
	UserStatus status,
	UserRole role,
	String keyword,
	int page,
	int size
) {
}
```

`admin-service/src/main/java/com/prompthub/admin/user/application/dto/UserSummaryResult.java`:

```java
package com.prompthub.admin.user.application.dto;

import com.prompthub.admin.user.domain.model.User;
import com.prompthub.admin.user.domain.model.UserRole;
import com.prompthub.admin.user.domain.model.UserStatus;

import java.util.UUID;

public record UserSummaryResult(
	UUID userId,
	String name,
	String email,
	UserRole role,
	UserStatus status
) {
	public static UserSummaryResult from(User user) {
		return new UserSummaryResult(
			user.getUserId(),
			user.getName(),
			user.getEmail(),
			user.getPrimaryRole(),
			user.getStatus()
		);
	}
}
```

`admin-service/src/main/java/com/prompthub/admin/user/application/dto/UserPageResult.java`:

```java
package com.prompthub.admin.user.application.dto;

import java.util.List;

public record UserPageResult(
	List<UserSummaryResult> users,
	int page,
	int size,
	long total,
	boolean hasNext
) {
}
```

`admin-service/src/main/java/com/prompthub/admin/user/application/dto/UserStatsResult.java`:

```java
package com.prompthub.admin.user.application.dto;

public record UserStatsResult(
	long totalUsers,
	long todayNewUsers
) {
}
```

`admin-service/src/main/java/com/prompthub/admin/user/application/dto/ChangeUserStatusCommand.java`:

```java
package com.prompthub.admin.user.application.dto;

import com.prompthub.admin.user.domain.model.UserStatus;

import java.util.UUID;

public record ChangeUserStatusCommand(
	UUID userId,
	UserStatus status
) {
}
```

`admin-service/src/main/java/com/prompthub/admin/user/application/dto/UserStatusResult.java`:

```java
package com.prompthub.admin.user.application.dto;

import com.prompthub.admin.user.domain.model.User;
import com.prompthub.admin.user.domain.model.UserStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record UserStatusResult(
	UUID userId,
	UserStatus status,
	LocalDateTime updatedAt
) {
	public static UserStatusResult from(User user) {
		return new UserStatusResult(
			user.getUserId(),
			user.getStatus(),
			user.getUpdatedAt()
		);
	}
}
```

- [x] **Step 3: UserUseCase 포트 작성**

`admin-service/src/main/java/com/prompthub/admin/user/application/usecase/UserUseCase.java`:

```java
package com.prompthub.admin.user.application.usecase;

import com.prompthub.admin.user.application.dto.ChangeUserStatusCommand;
import com.prompthub.admin.user.application.dto.UserListQuery;
import com.prompthub.admin.user.application.dto.UserPageResult;
import com.prompthub.admin.user.application.dto.UserStatsResult;
import com.prompthub.admin.user.application.dto.UserStatusResult;

public interface UserUseCase {
	UserPageResult listUsers(UserListQuery query);
	UserStatusResult changeUserStatus(ChangeUserStatusCommand command);
	UserStatsResult getUserStats();
}
```

- [x] **Step 4: 실패하는 UserApplicationServiceTest 작성**

`admin-service/src/test/java/com/prompthub/admin/user/application/service/UserApplicationServiceTest.java`:

```java
package com.prompthub.admin.user.application.service;

import com.prompthub.admin.auth.application.usecase.SessionRevocationUseCase;
import com.prompthub.admin.auth.domain.repository.AuthorizationCacheRepository;
import com.prompthub.admin.global.exception.AdminException;
import com.prompthub.admin.user.application.dto.ChangeUserStatusCommand;
import com.prompthub.admin.user.application.dto.UserListQuery;
import com.prompthub.admin.user.application.dto.UserPageResult;
import com.prompthub.admin.user.application.dto.UserStatusResult;
import com.prompthub.admin.user.domain.model.User;
import com.prompthub.admin.user.domain.model.UserStatus;
import com.prompthub.admin.user.domain.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class UserApplicationServiceTest {

	@Mock
	private UserRepository userRepository;

	@Mock
	private AuthorizationCacheRepository authorizationCacheRepository;

	@Mock
	private SessionRevocationUseCase sessionRevocationUseCase;

	@InjectMocks
	private UserApplicationService userApplicationService;

	@Test
	void 목록_조회는_0base_페이지로_변환해서_리포지토리에_전달한다() {
		given(userRepository.findUsers(null, null, null, 0, 20)).willReturn(List.of());
		given(userRepository.countUsers(null, null, null)).willReturn(0L);

		UserPageResult result = userApplicationService.listUsers(new UserListQuery(null, null, null, 1, 20));

		assertThat(result.page()).isEqualTo(1);
		assertThat(result.hasNext()).isFalse();
	}

	@Test
	void WITHDRAWN으로_바꾸면_세션을_전부_폐기한다() throws Exception {
		UUID userId = UUID.randomUUID();
		User user = newUser(userId, UserStatus.ACTIVE);
		given(userRepository.findById(userId)).willReturn(Optional.of(user));
		given(userRepository.save(user)).willReturn(user);

		UserStatusResult result = userApplicationService.changeUserStatus(
			new ChangeUserStatusCommand(userId, UserStatus.WITHDRAWN));

		assertThat(result.status()).isEqualTo(UserStatus.WITHDRAWN);
		then(sessionRevocationUseCase).should().revoke(userId);
		then(authorizationCacheRepository).should(never()).evict(any());
	}

	@Test
	void BLOCKED로_바꾸면_인가캐시만_무효화한다() throws Exception {
		UUID userId = UUID.randomUUID();
		User user = newUser(userId, UserStatus.ACTIVE);
		given(userRepository.findById(userId)).willReturn(Optional.of(user));
		given(userRepository.save(user)).willReturn(user);

		userApplicationService.changeUserStatus(new ChangeUserStatusCommand(userId, UserStatus.BLOCKED));

		then(authorizationCacheRepository).should().evict(userId);
		then(sessionRevocationUseCase).should(never()).revoke(any());
	}

	@Test
	void 대상_사용자가_없으면_USER_NOT_FOUND를_던진다() {
		UUID userId = UUID.randomUUID();
		given(userRepository.findById(userId)).willReturn(Optional.empty());

		assertThatThrownBy(() ->
			userApplicationService.changeUserStatus(new ChangeUserStatusCommand(userId, UserStatus.BLOCKED)))
			.isInstanceOf(AdminException.class);
	}

	// 테스트 전용 헬퍼 — User는 domain-model.md 정책상 public 생성자/빌더가 없으므로
	// 리플렉션으로 픽스처를 만든다(admin-service 기존 컨벤션에 정적 팩토리가 없는
	// 재매핑 엔티티가 없어 참고할 전례가 없다 — 최소한의 리플렉션으로 대체).
	private static User newUser(UUID userId, UserStatus status) throws Exception {
		User user = new User();
		setField(user, "userId", userId);
		setField(user, "name", "테스트유저");
		setField(user, "email", "test@example.com");
		setField(user, "status", status);
		return user;
	}

	private static void setField(Object target, String fieldName, Object value) throws Exception {
		Field field = User.class.getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}
}
```

- [x] **Step 5: 테스트 실행해서 컴파일 실패 확인**

Run: `./gradlew :admin-service:test --tests "com.prompthub.admin.user.application.service.UserApplicationServiceTest"`
Expected: FAIL — `UserApplicationService` 클래스가 없어 컴파일 에러.

- [x] **Step 6: UserApplicationService 구현**

`admin-service/src/main/java/com/prompthub/admin/user/application/service/UserApplicationService.java`:

```java
package com.prompthub.admin.user.application.service;

import com.prompthub.admin.auth.application.usecase.SessionRevocationUseCase;
import com.prompthub.admin.auth.domain.repository.AuthorizationCacheRepository;
import com.prompthub.admin.global.exception.AdminErrorCode;
import com.prompthub.admin.global.exception.AdminException;
import com.prompthub.admin.user.application.dto.ChangeUserStatusCommand;
import com.prompthub.admin.user.application.dto.UserListQuery;
import com.prompthub.admin.user.application.dto.UserPageResult;
import com.prompthub.admin.user.application.dto.UserStatsResult;
import com.prompthub.admin.user.application.dto.UserStatusResult;
import com.prompthub.admin.user.application.dto.UserSummaryResult;
import com.prompthub.admin.user.application.usecase.UserUseCase;
import com.prompthub.admin.user.domain.model.User;
import com.prompthub.admin.user.domain.model.UserStatus;
import com.prompthub.admin.user.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserApplicationService implements UserUseCase {

	private final UserRepository userRepository;
	private final AuthorizationCacheRepository authorizationCacheRepository;
	private final SessionRevocationUseCase sessionRevocationUseCase;

	@Override
	public UserPageResult listUsers(UserListQuery query) {
		int zeroBasedPage = query.page() - 1;

		List<User> users = userRepository.findUsers(
			query.status(), query.role(), query.keyword(), zeroBasedPage, query.size());
		long total = userRepository.countUsers(query.status(), query.role(), query.keyword());

		List<UserSummaryResult> results = users.stream()
			.map(UserSummaryResult::from)
			.toList();

		boolean hasNext = total > (long) query.page() * query.size();

		return new UserPageResult(results, query.page(), query.size(), total, hasNext);
	}

	@Override
	@Transactional
	public UserStatusResult changeUserStatus(ChangeUserStatusCommand command) {
		User user = userRepository.findById(command.userId())
			.orElseThrow(() -> new AdminException(AdminErrorCode.USER_NOT_FOUND));

		applyStatus(user, command.status());

		userRepository.save(user);
		if (command.status() == UserStatus.WITHDRAWN) {
			sessionRevocationUseCase.revoke(user.getUserId());
		} else {
			authorizationCacheRepository.evict(user.getUserId());
		}
		return UserStatusResult.from(user);
	}

	@Override
	public UserStatsResult getUserStats() {
		long totalUsers = userRepository.countUsers(null, null, null);

		LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
		LocalDateTime startOfNextDay = startOfDay.plusDays(1);
		long todayNewUsers = userRepository.countCreatedBetween(startOfDay, startOfNextDay);

		return new UserStatsResult(totalUsers, todayNewUsers);
	}

	private static void applyStatus(User user, UserStatus status) {
		switch (status) {
			case ACTIVE -> user.activate();
			case BLOCKED -> user.block();
			case WITHDRAWN -> user.withdraw();
		}
	}
}
```

- [x] **Step 7: 테스트 실행해서 통과 확인**

Run: `./gradlew :admin-service:test --tests "com.prompthub.admin.user.application.service.UserApplicationServiceTest"`
Expected: PASS(4개 테스트)

- [x] **Step 8: 커밋**

```bash
git add admin-service/src/main/java/com/prompthub/admin/global/exception/AdminErrorCode.java \
  admin-service/src/main/java/com/prompthub/admin/user/application \
  admin-service/src/test/java/com/prompthub/admin/user/application
git commit -m "$(cat <<'EOF'
feat: 어드민 회원 관리 application 레이어 admin-service로 이관

- user-service AdminUserApplicationService의 목록/통계/상태변경 로직을
  UserApplicationService로 1:1 이관 — WITHDRAWN이면 세션 전체 폐기,
  그 외엔 인가캐시만 무효화하는 분기 그대로 유지
- AdminErrorCode에 USER_NOT_FOUND(A-007), SELLER_REGISTER_NOT_FOUND
  (A-008, Task 6에서 사용) 추가
EOF
)"
```

---

### Task 4: `com.prompthub.admin.user` Presentation 레이어 (UserController)

**Files:**
- Create: `admin-service/src/main/java/com/prompthub/admin/user/presentation/dto/request/ChangeUserStatusRequest.java`
- Create: `admin-service/src/main/java/com/prompthub/admin/user/presentation/dto/response/UserResponse.java`
- Create: `admin-service/src/main/java/com/prompthub/admin/user/presentation/dto/response/UserStatsResponse.java`
- Create: `admin-service/src/main/java/com/prompthub/admin/user/presentation/dto/response/UserStatusResponse.java`
- Create: `admin-service/src/main/java/com/prompthub/admin/user/presentation/controller/UserController.java`
- Test: `admin-service/src/test/java/com/prompthub/admin/user/presentation/controller/UserControllerTest.java`

**Interfaces:**
- Consumes: Task 3의 `UserUseCase`(`listUsers`, `changeUserStatus`, `getUserStats`), 공통 모듈의 `ApiResult`, `PageResponse`.
- Produces: `GET ${api.init}/admin/users`, `GET ${api.init}/admin/stats/users`, `PATCH ${api.init}/admin/users/{userId}/status`.

- [x] **Step 1: 요청/응답 DTO 작성**

`admin-service/src/main/java/com/prompthub/admin/user/presentation/dto/request/ChangeUserStatusRequest.java`:

```java
package com.prompthub.admin.user.presentation.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "사용자 상태 변경 요청")
public record ChangeUserStatusRequest(
	@Schema(description = "변경할 계정 상태 (active | suspended | withdrawn)", example = "suspended")
	@NotBlank String status
) {
}
```

`admin-service/src/main/java/com/prompthub/admin/user/presentation/dto/response/UserResponse.java`:

```java
package com.prompthub.admin.user.presentation.dto.response;

import com.prompthub.admin.user.application.dto.UserSummaryResult;
import com.prompthub.admin.user.domain.model.UserStatus;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "관리자 — 사용자 목록 항목")
public record UserResponse(
	@Schema(description = "사용자 ID")
	String id,
	@Schema(description = "이름", example = "김도윤")
	String name,
	@Schema(description = "이메일", example = "doyoon.kim@gmail.com")
	String email,
	@Schema(description = "역할 (buyer | seller)", example = "buyer")
	String role,
	@Schema(description = "계정 상태 (active | suspended | withdrawn)", example = "active")
	String status
) {
	public static UserResponse from(UserSummaryResult result) {
		return new UserResponse(
			result.userId().toString(),
			result.name(),
			result.email(),
			result.role().name().toLowerCase(),
			mapStatus(result.status())
		);
	}

	private static String mapStatus(UserStatus status) {
		return switch (status) {
			case ACTIVE -> "active";
			case BLOCKED -> "suspended";
			case WITHDRAWN -> "withdrawn";
		};
	}
}
```

`admin-service/src/main/java/com/prompthub/admin/user/presentation/dto/response/UserStatsResponse.java`:

```java
package com.prompthub.admin.user.presentation.dto.response;

import com.prompthub.admin.user.application.dto.UserStatsResult;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "회원 통계 응답")
public record UserStatsResponse(
	@Schema(description = "누적 회원 수", example = "1240")
	long totalUsers,
	@Schema(description = "오늘 신규 가입 수", example = "13")
	long todayNewUsers
) {
	public static UserStatsResponse from(UserStatsResult result) {
		return new UserStatsResponse(result.totalUsers(), result.todayNewUsers());
	}
}
```

`admin-service/src/main/java/com/prompthub/admin/user/presentation/dto/response/UserStatusResponse.java`:

```java
package com.prompthub.admin.user.presentation.dto.response;

import com.prompthub.admin.user.application.dto.UserStatusResult;
import com.prompthub.admin.user.domain.model.UserStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "사용자 상태 변경 응답")
public record UserStatusResponse(
	@Schema(description = "사용자 ID")
	String id,
	@Schema(description = "변경된 계정 상태 (active | suspended | withdrawn)", example = "suspended")
	String status,
	@Schema(description = "변경 일시 (ISO 8601)", example = "2026-06-17T10:00:00")
	LocalDateTime updatedAt
) {
	public static UserStatusResponse from(UserStatusResult result) {
		return new UserStatusResponse(
			result.userId().toString(),
			mapStatus(result.status()),
			result.updatedAt()
		);
	}

	private static String mapStatus(UserStatus status) {
		return switch (status) {
			case ACTIVE -> "active";
			case BLOCKED -> "suspended";
			case WITHDRAWN -> "withdrawn";
		};
	}
}
```

- [x] **Step 2: 실패하는 UserControllerTest 작성**

`admin-service/src/test/java/com/prompthub/admin/user/presentation/controller/UserControllerTest.java`:

```java
package com.prompthub.admin.user.presentation.controller;

import com.prompthub.admin.user.application.dto.UserPageResult;
import com.prompthub.admin.user.application.dto.UserStatsResult;
import com.prompthub.admin.user.application.dto.UserStatusResult;
import com.prompthub.admin.user.application.dto.UserSummaryResult;
import com.prompthub.admin.user.application.usecase.UserUseCase;
import com.prompthub.admin.user.domain.model.UserRole;
import com.prompthub.admin.user.domain.model.UserStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
@ActiveProfiles("test")
class UserControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private UserUseCase userUseCase;

	@Test
	void 회원_목록을_조회한다() throws Exception {
		UserSummaryResult summary = new UserSummaryResult(
			UUID.fromString("00000000-0000-0000-0000-000000000001"),
			"김도윤", "doyoon.kim@gmail.com", UserRole.BUYER, UserStatus.ACTIVE);
		when(userUseCase.listUsers(any())).thenReturn(new UserPageResult(List.of(summary), 1, 20, 1, false));

		mockMvc.perform(get("/api/v2/admin/users").param("status", "ALL").param("role", "ALL"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data[0].name").value("김도윤"))
			.andExpect(jsonPath("$.data[0].role").value("buyer"))
			.andExpect(jsonPath("$.meta.total").value(1));
	}

	@Test
	void 회원_통계를_조회한다() throws Exception {
		when(userUseCase.getUserStats()).thenReturn(new UserStatsResult(1240L, 13L));

		mockMvc.perform(get("/api/v2/admin/stats/users"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.totalUsers").value(1240))
			.andExpect(jsonPath("$.data.todayNewUsers").value(13));
	}

	@Test
	void 사용자_상태를_변경한다() throws Exception {
		UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000002");
		when(userUseCase.changeUserStatus(any())).thenReturn(
			new UserStatusResult(userId, UserStatus.BLOCKED, LocalDateTime.of(2026, 7, 20, 10, 0)));

		mockMvc.perform(patch("/api/v2/admin/users/{userId}/status", userId)
				.contentType("application/json")
				.content("{\"status\":\"suspended\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("suspended"));
	}

	@Test
	void 알수없는_상태_문자열은_400을_내려준다() throws Exception {
		UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000003");

		mockMvc.perform(patch("/api/v2/admin/users/{userId}/status", userId)
				.contentType("application/json")
				.content("{\"status\":\"ALL\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("A-001"));
	}
}
```

- [x] **Step 3: 테스트 실행해서 컴파일 실패 확인**

Run: `./gradlew :admin-service:test --tests "com.prompthub.admin.user.presentation.controller.UserControllerTest"`
Expected: FAIL — `UserController` 클래스가 없어 컴파일 에러.

- [x] **Step 4: UserController 구현**

`admin-service/src/main/java/com/prompthub/admin/user/presentation/controller/UserController.java`:

```java
package com.prompthub.admin.user.presentation.controller;

import com.prompthub.admin.global.exception.AdminErrorCode;
import com.prompthub.admin.global.exception.AdminException;
import com.prompthub.admin.user.application.dto.ChangeUserStatusCommand;
import com.prompthub.admin.user.application.dto.UserListQuery;
import com.prompthub.admin.user.application.dto.UserPageResult;
import com.prompthub.admin.user.application.dto.UserStatsResult;
import com.prompthub.admin.user.application.dto.UserStatusResult;
import com.prompthub.admin.user.application.usecase.UserUseCase;
import com.prompthub.admin.user.domain.model.UserRole;
import com.prompthub.admin.user.domain.model.UserStatus;
import com.prompthub.admin.user.presentation.dto.request.ChangeUserStatusRequest;
import com.prompthub.admin.user.presentation.dto.response.UserResponse;
import com.prompthub.admin.user.presentation.dto.response.UserStatsResponse;
import com.prompthub.admin.user.presentation.dto.response.UserStatusResponse;
import com.prompthub.exception.response.ErrorResponse;
import com.prompthub.presentation.dto.ApiResult;
import com.prompthub.presentation.dto.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("${api.init}/admin")
@RequiredArgsConstructor
@Tag(name = "Admin User", description = "관리자 회원 관리 API (user-service 에서 이관)")
@SecurityRequirement(name = "gatewayHeaders")
public class UserController {

	private final UserUseCase userUseCase;

	@GetMapping("/users")
	@Operation(summary = "전체 사용자 목록 조회", description = "상태·역할·키워드 필터, 페이지네이션 지원. 역할: ADMIN")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "조회 성공"),
		@ApiResponse(responseCode = "401", description = "인증 정보 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "403", description = "ADMIN 권한 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	})
	public PageResponse<UserResponse> listUsers(
		@Parameter(description = "계정 상태 필터 (active | suspended | withdrawn | ALL)", example = "ALL")
		@RequestParam(defaultValue = "ALL") String status,
		@Parameter(description = "역할 필터 (buyer | seller | ALL)", example = "ALL")
		@RequestParam(defaultValue = "ALL") String role,
		@Parameter(description = "이름·이메일 검색 키워드")
		@RequestParam(required = false) String keyword,
		@Parameter(description = "페이지 번호 (1부터 시작)", example = "1")
		@RequestParam(defaultValue = "1") int page,
		@Parameter(description = "페이지당 항목 수", example = "20")
		@RequestParam(defaultValue = "20") int size
	) {
		UserListQuery query = new UserListQuery(parseStatusFilter(status), parseRoleFilter(role), keyword, page, size);
		UserPageResult result = userUseCase.listUsers(query);

		List<UserResponse> responseData = result.users().stream()
			.map(UserResponse::from)
			.toList();

		return PageResponse.success(responseData, result.page(), result.size(), result.total(), result.hasNext());
	}

	@GetMapping("/stats/users")
	@Operation(summary = "회원 통계 조회", description = "누적 회원 수 및 오늘 신규 가입 수 반환. 역할: ADMIN")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "조회 성공"),
		@ApiResponse(responseCode = "401", description = "인증 정보 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "403", description = "ADMIN 권한 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	})
	public ApiResult<UserStatsResponse> getUserStats() {
		UserStatsResult result = userUseCase.getUserStats();
		return ApiResult.success(UserStatsResponse.from(result));
	}

	@PatchMapping("/users/{userId}/status")
	@Operation(summary = "사용자 상태 변경", description = "active | suspended | withdrawn 으로 변경. 역할: ADMIN")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "변경 성공"),
		@ApiResponse(responseCode = "400", description = "요청 값 오류",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "401", description = "인증 정보 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "403", description = "ADMIN 권한 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "404", description = "사용자를 찾을 수 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	})
	public ApiResult<UserStatusResponse> changeUserStatus(
		@Parameter(description = "대상 사용자 ID") @PathVariable UUID userId,
		@Valid @RequestBody ChangeUserStatusRequest request
	) {
		UserStatus targetStatus = parseStatusCommand(request.status());
		ChangeUserStatusCommand command = new ChangeUserStatusCommand(userId, targetStatus);

		UserStatusResult result = userUseCase.changeUserStatus(command);
		return ApiResult.success(UserStatusResponse.from(result));
	}

	// 목록 필터용 — "ALL"은 필터 없음(null)으로 취급.
	private static UserStatus parseStatusFilter(String statusParam) {
		return switch (statusParam) {
			case "active" -> UserStatus.ACTIVE;
			case "suspended" -> UserStatus.BLOCKED;
			case "withdrawn" -> UserStatus.WITHDRAWN;
			case "ALL" -> null;
			default -> throw new AdminException(AdminErrorCode.INVALID_INPUT_VALUE);
		};
	}

	// 상태변경 커맨드용 — "ALL"/미인식 값은 전부 400(원본 user-service엔 이 가드가
	// 없어 "ALL" 입력 시 서비스 계층에서 NullPointerException·500이 났던 버그를 고쳤다).
	private static UserStatus parseStatusCommand(String statusParam) {
		return switch (statusParam) {
			case "active" -> UserStatus.ACTIVE;
			case "suspended" -> UserStatus.BLOCKED;
			case "withdrawn" -> UserStatus.WITHDRAWN;
			default -> throw new AdminException(AdminErrorCode.INVALID_INPUT_VALUE);
		};
	}

	private static UserRole parseRoleFilter(String roleParam) {
		return switch (roleParam) {
			case "buyer" -> UserRole.BUYER;
			case "seller" -> UserRole.SELLER;
			case "ALL" -> null;
			default -> throw new AdminException(AdminErrorCode.INVALID_INPUT_VALUE);
		};
	}
}
```

- [x] **Step 5: 테스트 실행해서 통과 확인**

Run: `./gradlew :admin-service:test --tests "com.prompthub.admin.user.presentation.controller.UserControllerTest"`
Expected: PASS(4개 테스트)

- [x] **Step 6: 커밋**

```bash
git add admin-service/src/main/java/com/prompthub/admin/user/presentation \
  admin-service/src/test/java/com/prompthub/admin/user/presentation
git commit -m "$(cat <<'EOF'
feat: 어드민 회원 관리 컨트롤러 admin-service로 이관(api/v2)

- user-service AdminUserController의 엔드포인트 3개(목록/통계/상태변경)를
  UserController로 이관, "Admin" 접두사 제거
- 경로는 원본과 동일하게 "${api.init}/admin" 하위 — user-service도 이미
  api/v2였으므로 이번엔 외부 경로·버전 자체는 안 바뀜(design.md 결정 6)
- 상태변경 경로에서 "ALL"/미인식 문자열을 전부 400으로 막도록 원본 버그
  수정(Global Constraints 참고) — 목록 필터의 "ALL" 동작은 그대로 유지
EOF
)"
```

---

### Task 5: `com.prompthub.admin.seller` 도메인 — SellerRegister 엔티티 + SellerRegisterRepository

**Files:**
- Create: `admin-service/src/main/java/com/prompthub/admin/seller/domain/model/SellerRegisterStatus.java`
- Create: `admin-service/src/main/java/com/prompthub/admin/seller/domain/model/SellerRegister.java`
- Create: `admin-service/src/main/java/com/prompthub/admin/seller/domain/repository/SellerRegisterRepository.java`
- Create: `admin-service/src/main/java/com/prompthub/admin/seller/infrastructure/persistence/SellerRegisterJpaRepository.java`
- Create: `admin-service/src/main/java/com/prompthub/admin/seller/infrastructure/persistence/SellerRegisterRepositoryAdapter.java`
- Test: `admin-service/src/test/java/com/prompthub/admin/seller/infrastructure/persistence/SellerRegisterRepositoryAdapterTest.java`
- Create: `admin-service/src/test/resources/sql/seller_registers.sql`

**Interfaces:**
- Produces: `SellerRegisterRepository.findById(UUID): Optional<SellerRegister>`, `.save(SellerRegister): SellerRegister`, `.findAll(SellerRegisterStatus, int, int): List<SellerRegister>`, `.count(SellerRegisterStatus): long` — Task 6이 그대로 소비한다.
- Produces: `SellerRegister`(도메인 메서드 `approve()`, `reject(String)`, getter로 `getSellerRegisterId`/`getUserId`/`getStatus`/`getCategories`/`getIntroduction`/`getPortfolioUrl`/`getSubmittedAt`/`getReviewedAt`/`getRejectReason`) — Task 6이 그대로 참조한다.

- [x] **Step 1: SQL 픽스처 작성**

`admin-service/src/test/resources/sql/seller_registers.sql`:

```sql
-- 어드민 판매자 등록 신청 목록/승인/반려 쿼리 검증용 픽스처.
INSERT INTO seller_register (id, user_id, status, introduction, portfolio_url, submitted_at) VALUES
('22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000001', 'PENDING', '마케팅 카피 전문', 'https://blog.example.com', '2026-07-10 09:00:00'),
('22222222-0000-0000-0000-000000000002', '11111111-0000-0000-0000-000000000002', 'APPROVED', '이미지 생성 전문', NULL, '2026-07-05 09:00:00');

INSERT INTO seller_register_category (seller_register_id, category) VALUES
('22222222-0000-0000-0000-000000000001', '마케팅'),
('22222222-0000-0000-0000-000000000002', '이미지 생성');
```

- [x] **Step 2: 실패하는 SellerRegisterRepositoryAdapterTest 작성**

`admin-service/src/test/java/com/prompthub/admin/seller/infrastructure/persistence/SellerRegisterRepositoryAdapterTest.java`:

```java
package com.prompthub.admin.seller.infrastructure.persistence;

import com.prompthub.admin.seller.domain.model.SellerRegister;
import com.prompthub.admin.seller.domain.model.SellerRegisterStatus;
import com.prompthub.admin.seller.domain.repository.SellerRegisterRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(SellerRegisterRepositoryAdapter.class)
@ActiveProfiles("test")
@Sql("/sql/seller_registers.sql")
class SellerRegisterRepositoryAdapterTest {

	private static final UUID PENDING_ID = UUID.fromString("22222222-0000-0000-0000-000000000001");

	@Autowired
	private SellerRegisterRepository sellerRegisterRepository;

	@Autowired
	private TestEntityManager entityManager;

	@Test
	void 상태로_필터링해서_목록을_조회한다() {
		List<SellerRegister> result = sellerRegisterRepository.findAll(SellerRegisterStatus.PENDING, 0, 20);

		assertThat(result).hasSize(1);
		assertThat(result.getFirst().getSellerRegisterId()).isEqualTo(PENDING_ID);
		assertThat(result.getFirst().getCategories()).containsExactly("마케팅");
	}

	@Test
	void 상태별_건수를_센다() {
		assertThat(sellerRegisterRepository.count(SellerRegisterStatus.APPROVED)).isEqualTo(1);
		assertThat(sellerRegisterRepository.count(null)).isEqualTo(2);
	}

	@Test
	void 승인_후_저장하면_재조회시_승인상태가_유지된다() {
		SellerRegister register = sellerRegisterRepository.findById(PENDING_ID).orElseThrow();

		register.approve();
		sellerRegisterRepository.save(register);

		entityManager.flush();
		entityManager.clear();

		SellerRegister reloaded = sellerRegisterRepository.findById(PENDING_ID).orElseThrow();
		assertThat(reloaded.getStatus()).isEqualTo(SellerRegisterStatus.APPROVED);
		assertThat(reloaded.getReviewedAt()).isNotNull();
	}

	@Test
	void 반려_후_저장하면_사유가_유지된다() {
		SellerRegister register = sellerRegisterRepository.findById(PENDING_ID).orElseThrow();

		register.reject("포트폴리오 미확인");
		sellerRegisterRepository.save(register);

		entityManager.flush();
		entityManager.clear();

		SellerRegister reloaded = sellerRegisterRepository.findById(PENDING_ID).orElseThrow();
		assertThat(reloaded.getStatus()).isEqualTo(SellerRegisterStatus.REJECTED);
		assertThat(reloaded.getRejectReason()).isEqualTo("포트폴리오 미확인");
	}
}
```

- [x] **Step 3: 테스트 실행해서 컴파일 실패 확인**

Run: `./gradlew :admin-service:test --tests "com.prompthub.admin.seller.infrastructure.persistence.SellerRegisterRepositoryAdapterTest"`
Expected: FAIL — `SellerRegister`, `SellerRegisterRepository`, `SellerRegisterRepositoryAdapter` 클래스가 없어 컴파일 에러.

- [x] **Step 4: SellerRegisterStatus enum + SellerRegister 엔티티 작성**

`admin-service/src/main/java/com/prompthub/admin/seller/domain/model/SellerRegisterStatus.java`:

```java
package com.prompthub.admin.seller.domain.model;

public enum SellerRegisterStatus {
	PENDING,
	APPROVED,
	REJECTED
}
```

`admin-service/src/main/java/com/prompthub/admin/seller/domain/model/SellerRegister.java`:

```java
package com.prompthub.admin.seller.domain.model;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * user-service 소유 "seller_register"(+ seller_register_category) 테이블의
 * 읽기+쓰기 재매핑. 어드민 심사(목록/승인/반려)가 실제로 참조하는 컬럼만
 * 매핑한다 — agreed_to_terms는 신청 시점 동의 여부라 심사 화면이 안 써서
 * 매핑하지 않았다.
 */
@Entity
@Table(name = "seller_register")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SellerRegister {

	@Id
	@Column(name = "id", columnDefinition = "uuid")
	private UUID sellerRegisterId;

	@Column(name = "user_id", nullable = false, columnDefinition = "uuid")
	private UUID userId;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private SellerRegisterStatus status;

	@ElementCollection(fetch = FetchType.LAZY)
	@CollectionTable(name = "seller_register_category", joinColumns = @JoinColumn(name = "seller_register_id"))
	@Column(name = "category", nullable = false, length = 100)
	private List<String> categories = new ArrayList<>();

	@Column(name = "introduction", columnDefinition = "TEXT")
	private String introduction;

	@Column(name = "portfolio_url", length = 500)
	private String portfolioUrl;

	@Column(name = "submitted_at", nullable = false)
	private LocalDateTime submittedAt;

	@Column(name = "reviewed_at")
	private LocalDateTime reviewedAt;

	@Column(name = "reject_reason", columnDefinition = "TEXT")
	private String rejectReason;

	public void approve() {
		this.status = SellerRegisterStatus.APPROVED;
		this.reviewedAt = LocalDateTime.now();
	}

	public void reject(String reason) {
		this.status = SellerRegisterStatus.REJECTED;
		this.reviewedAt = LocalDateTime.now();
		this.rejectReason = reason;
	}
}
```

- [x] **Step 5: SellerRegisterRepository 포트 + JpaRepository + Adapter 작성**

`admin-service/src/main/java/com/prompthub/admin/seller/domain/repository/SellerRegisterRepository.java`:

```java
package com.prompthub.admin.seller.domain.repository;

import com.prompthub.admin.seller.domain.model.SellerRegister;
import com.prompthub.admin.seller.domain.model.SellerRegisterStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SellerRegisterRepository {
	Optional<SellerRegister> findById(UUID registerId);
	SellerRegister save(SellerRegister sellerRegister);
	List<SellerRegister> findAll(SellerRegisterStatus status, int page, int size);
	long count(SellerRegisterStatus status);
}
```

`admin-service/src/main/java/com/prompthub/admin/seller/infrastructure/persistence/SellerRegisterJpaRepository.java`:

```java
package com.prompthub.admin.seller.infrastructure.persistence;

import com.prompthub.admin.seller.domain.model.SellerRegister;
import com.prompthub.admin.seller.domain.model.SellerRegisterStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SellerRegisterJpaRepository extends JpaRepository<SellerRegister, UUID> {
	Page<SellerRegister> findAllByStatus(SellerRegisterStatus status, Pageable pageable);
	long countByStatus(SellerRegisterStatus status);
}
```

`admin-service/src/main/java/com/prompthub/admin/seller/infrastructure/persistence/SellerRegisterRepositoryAdapter.java`:

```java
package com.prompthub.admin.seller.infrastructure.persistence;

import com.prompthub.admin.seller.domain.model.SellerRegister;
import com.prompthub.admin.seller.domain.model.SellerRegisterStatus;
import com.prompthub.admin.seller.domain.repository.SellerRegisterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class SellerRegisterRepositoryAdapter implements SellerRegisterRepository {

	private final SellerRegisterJpaRepository jpaRepository;

	@Override
	public Optional<SellerRegister> findById(UUID registerId) {
		return jpaRepository.findById(registerId);
	}

	@Override
	public SellerRegister save(SellerRegister sellerRegister) {
		return jpaRepository.save(sellerRegister);
	}

	@Override
	public List<SellerRegister> findAll(SellerRegisterStatus status, int page, int size) {
		PageRequest pageable = PageRequest.of(page, size, Sort.by("submittedAt").descending());
		if (status == null) {
			return jpaRepository.findAll(pageable).getContent();
		}
		return jpaRepository.findAllByStatus(status, pageable).getContent();
	}

	@Override
	public long count(SellerRegisterStatus status) {
		if (status == null) {
			return jpaRepository.count();
		}
		return jpaRepository.countByStatus(status);
	}
}
```

- [x] **Step 6: 테스트 실행해서 통과 확인**

Run: `./gradlew :admin-service:test --tests "com.prompthub.admin.seller.infrastructure.persistence.SellerRegisterRepositoryAdapterTest"`
Expected: PASS(4개 테스트)

- [x] **Step 7: 커밋**

```bash
git add admin-service/src/main/java/com/prompthub/admin/seller/domain \
  admin-service/src/main/java/com/prompthub/admin/seller/infrastructure \
  admin-service/src/test/java/com/prompthub/admin/seller/infrastructure/persistence/SellerRegisterRepositoryAdapterTest.java \
  admin-service/src/test/resources/sql/seller_registers.sql
git commit -m "$(cat <<'EOF'
feat: 어드민 판매자 등록심사용 SellerRegister 읽기+쓰기 엔티티 추가

- user-service SellerRegister 도메인(approve/reject)을
  com.prompthub.admin.seller로 1:1 복제 — own read-write entity
- 심사 화면이 실제 참조하는 컬럼만 매핑(agreed_to_terms 제외)
- 테스트는 SQL 픽스처 + 저장 후 재조회로 승인/반려 상태 반영을 검증
EOF
)"
```

---

### Task 6: `com.prompthub.admin.seller` Application 레이어 (SellerUseCase, SellerApplicationService)

**Files:**
- Create: `admin-service/src/main/java/com/prompthub/admin/seller/application/dto/SellerRegisterListQuery.java`
- Create: `admin-service/src/main/java/com/prompthub/admin/seller/application/dto/SellerRegisterPageResult.java`
- Create: `admin-service/src/main/java/com/prompthub/admin/seller/application/dto/SellerRegisterSummaryResult.java`
- Create: `admin-service/src/main/java/com/prompthub/admin/seller/application/dto/ApproveSellerCommand.java`
- Create: `admin-service/src/main/java/com/prompthub/admin/seller/application/dto/RejectSellerCommand.java`
- Create: `admin-service/src/main/java/com/prompthub/admin/seller/application/dto/SellerRegisterReviewResult.java`
- Create: `admin-service/src/main/java/com/prompthub/admin/seller/application/usecase/SellerUseCase.java`
- Create: `admin-service/src/main/java/com/prompthub/admin/seller/application/service/SellerApplicationService.java`
- Test: `admin-service/src/test/java/com/prompthub/admin/seller/application/service/SellerApplicationServiceTest.java`

**Interfaces:**
- Consumes: Task 1의 `AuthorizationCacheRepository.evict`. Task 2의 `UserRepository`(`findById`, `findAllByIds`, `save`), `User.addRole(UserRole)`. Task 5의 `SellerRegisterRepository`.
- Produces: `SellerUseCase.listSellerRegisters(SellerRegisterListQuery): SellerRegisterPageResult`, `.approve(ApproveSellerCommand): SellerRegisterReviewResult`, `.reject(RejectSellerCommand): SellerRegisterReviewResult` — Task 7의 `SellerController`가 그대로 주입받아 호출한다.

- [x] **Step 1: 응용 DTO 작성**

`admin-service/src/main/java/com/prompthub/admin/seller/application/dto/SellerRegisterListQuery.java`:

```java
package com.prompthub.admin.seller.application.dto;

import com.prompthub.admin.seller.domain.model.SellerRegisterStatus;

public record SellerRegisterListQuery(
	SellerRegisterStatus status,
	int page,
	int size
) {
}
```

`admin-service/src/main/java/com/prompthub/admin/seller/application/dto/SellerRegisterSummaryResult.java`:

```java
package com.prompthub.admin.seller.application.dto;

import com.prompthub.admin.seller.domain.model.SellerRegister;
import com.prompthub.admin.seller.domain.model.SellerRegisterStatus;
import com.prompthub.admin.user.domain.model.User;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record SellerRegisterSummaryResult(
	UUID registerId,
	UUID userId,
	String name,
	String email,
	String introduction,
	List<String> categories,
	String portfolioUrl,
	SellerRegisterStatus status,
	LocalDateTime submittedAt
) {
	public static SellerRegisterSummaryResult of(SellerRegister register, User user) {
		return new SellerRegisterSummaryResult(
			register.getSellerRegisterId(),
			register.getUserId(),
			user.getName(),
			user.getEmail(),
			register.getIntroduction(),
			List.copyOf(register.getCategories()),
			register.getPortfolioUrl(),
			register.getStatus(),
			register.getSubmittedAt()
		);
	}
}
```

`admin-service/src/main/java/com/prompthub/admin/seller/application/dto/SellerRegisterPageResult.java`:

```java
package com.prompthub.admin.seller.application.dto;

import java.util.List;

public record SellerRegisterPageResult(
	List<SellerRegisterSummaryResult> items,
	int page,
	int size,
	long total,
	boolean hasNext
) {
}
```

`admin-service/src/main/java/com/prompthub/admin/seller/application/dto/ApproveSellerCommand.java`:

```java
package com.prompthub.admin.seller.application.dto;

import java.util.UUID;

public record ApproveSellerCommand(UUID registerId) {
}
```

`admin-service/src/main/java/com/prompthub/admin/seller/application/dto/RejectSellerCommand.java`:

```java
package com.prompthub.admin.seller.application.dto;

import java.util.UUID;

public record RejectSellerCommand(UUID registerId, String rejectReason) {
}
```

`admin-service/src/main/java/com/prompthub/admin/seller/application/dto/SellerRegisterReviewResult.java`:

```java
package com.prompthub.admin.seller.application.dto;

import com.prompthub.admin.seller.domain.model.SellerRegister;
import com.prompthub.admin.seller.domain.model.SellerRegisterStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record SellerRegisterReviewResult(
	UUID registerId,
	UUID userId,
	SellerRegisterStatus status,
	String rejectReason,
	LocalDateTime reviewedAt
) {
	public static SellerRegisterReviewResult from(SellerRegister register) {
		return new SellerRegisterReviewResult(
			register.getSellerRegisterId(),
			register.getUserId(),
			register.getStatus(),
			register.getRejectReason(),
			register.getReviewedAt()
		);
	}
}
```

- [x] **Step 2: SellerUseCase 포트 작성**

`admin-service/src/main/java/com/prompthub/admin/seller/application/usecase/SellerUseCase.java`:

```java
package com.prompthub.admin.seller.application.usecase;

import com.prompthub.admin.seller.application.dto.ApproveSellerCommand;
import com.prompthub.admin.seller.application.dto.RejectSellerCommand;
import com.prompthub.admin.seller.application.dto.SellerRegisterListQuery;
import com.prompthub.admin.seller.application.dto.SellerRegisterPageResult;
import com.prompthub.admin.seller.application.dto.SellerRegisterReviewResult;

public interface SellerUseCase {
	SellerRegisterPageResult listSellerRegisters(SellerRegisterListQuery query);
	SellerRegisterReviewResult approve(ApproveSellerCommand command);
	SellerRegisterReviewResult reject(RejectSellerCommand command);
}
```

- [x] **Step 3: 실패하는 SellerApplicationServiceTest 작성**

`admin-service/src/test/java/com/prompthub/admin/seller/application/service/SellerApplicationServiceTest.java`:

```java
package com.prompthub.admin.seller.application.service;

import com.prompthub.admin.auth.domain.repository.AuthorizationCacheRepository;
import com.prompthub.admin.global.exception.AdminException;
import com.prompthub.admin.seller.application.dto.ApproveSellerCommand;
import com.prompthub.admin.seller.application.dto.RejectSellerCommand;
import com.prompthub.admin.seller.application.dto.SellerRegisterReviewResult;
import com.prompthub.admin.seller.domain.model.SellerRegister;
import com.prompthub.admin.seller.domain.model.SellerRegisterStatus;
import com.prompthub.admin.seller.domain.repository.SellerRegisterRepository;
import com.prompthub.admin.user.domain.model.User;
import com.prompthub.admin.user.domain.model.UserRole;
import com.prompthub.admin.user.domain.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class SellerApplicationServiceTest {

	@Mock
	private SellerRegisterRepository sellerRegisterRepository;

	@Mock
	private UserRepository userRepository;

	@Mock
	private AuthorizationCacheRepository authorizationCacheRepository;

	@InjectMocks
	private SellerApplicationService sellerApplicationService;

	@Test
	void 승인하면_유저에게_SELLER_역할을_주고_인가캐시를_무효화한다() throws Exception {
		UUID registerId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		SellerRegister register = newRegister(registerId, userId, SellerRegisterStatus.PENDING);
		User user = newUser(userId);
		given(sellerRegisterRepository.findById(registerId)).willReturn(Optional.of(register));
		given(userRepository.findById(userId)).willReturn(Optional.of(user));
		given(sellerRegisterRepository.save(register)).willReturn(register);
		given(userRepository.save(user)).willReturn(user);

		SellerRegisterReviewResult result = sellerApplicationService.approve(new ApproveSellerCommand(registerId));

		assertThat(result.status()).isEqualTo(SellerRegisterStatus.APPROVED);
		assertThat(user.getRoles()).contains(UserRole.SELLER);
		then(authorizationCacheRepository).should().evict(userId);
	}

	@Test
	void 반려하면_인가캐시를_건드리지_않는다() throws Exception {
		UUID registerId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		SellerRegister register = newRegister(registerId, userId, SellerRegisterStatus.PENDING);
		given(sellerRegisterRepository.findById(registerId)).willReturn(Optional.of(register));
		given(sellerRegisterRepository.save(register)).willReturn(register);

		SellerRegisterReviewResult result = sellerApplicationService.reject(
			new RejectSellerCommand(registerId, "포트폴리오 미확인"));

		assertThat(result.status()).isEqualTo(SellerRegisterStatus.REJECTED);
		assertThat(result.rejectReason()).isEqualTo("포트폴리오 미확인");
		then(authorizationCacheRepository).shouldHaveNoInteractions();
	}

	@Test
	void 이미_심사된_신청은_승인할_수_없다() throws Exception {
		UUID registerId = UUID.randomUUID();
		SellerRegister register = newRegister(registerId, UUID.randomUUID(), SellerRegisterStatus.APPROVED);
		given(sellerRegisterRepository.findById(registerId)).willReturn(Optional.of(register));

		assertThatThrownBy(() -> sellerApplicationService.approve(new ApproveSellerCommand(registerId)))
			.isInstanceOf(AdminException.class);
	}

	@Test
	void 존재하지_않는_신청은_SELLER_REGISTER_NOT_FOUND를_던진다() {
		UUID registerId = UUID.randomUUID();
		given(sellerRegisterRepository.findById(registerId)).willReturn(Optional.empty());

		assertThatThrownBy(() -> sellerApplicationService.approve(new ApproveSellerCommand(registerId)))
			.isInstanceOf(AdminException.class);
	}

	// User/SellerRegister는 domain-model.md 정책상 public 생성자/빌더가 없어
	// 테스트 전용 리플렉션 헬퍼로 픽스처를 만든다(Task 3과 동일한 전례).
	private static SellerRegister newRegister(UUID registerId, UUID userId, SellerRegisterStatus status) throws Exception {
		SellerRegister register = new SellerRegister();
		setField(register, SellerRegister.class, "sellerRegisterId", registerId);
		setField(register, SellerRegister.class, "userId", userId);
		setField(register, SellerRegister.class, "status", status);
		return register;
	}

	private static User newUser(UUID userId) throws Exception {
		User user = new User();
		setField(user, User.class, "userId", userId);
		setField(user, User.class, "name", "테스트유저");
		setField(user, User.class, "email", "test@example.com");
		return user;
	}

	private static void setField(Object target, Class<?> type, String fieldName, Object value) throws Exception {
		Field field = type.getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}
}
```

- [x] **Step 4: 테스트 실행해서 컴파일 실패 확인**

Run: `./gradlew :admin-service:test --tests "com.prompthub.admin.seller.application.service.SellerApplicationServiceTest"`
Expected: FAIL — `SellerApplicationService` 클래스가 없어 컴파일 에러.

- [x] **Step 5: SellerApplicationService 구현**

`admin-service/src/main/java/com/prompthub/admin/seller/application/service/SellerApplicationService.java`:

```java
package com.prompthub.admin.seller.application.service;

import com.prompthub.admin.auth.domain.repository.AuthorizationCacheRepository;
import com.prompthub.admin.global.exception.AdminErrorCode;
import com.prompthub.admin.global.exception.AdminException;
import com.prompthub.admin.seller.application.dto.ApproveSellerCommand;
import com.prompthub.admin.seller.application.dto.RejectSellerCommand;
import com.prompthub.admin.seller.application.dto.SellerRegisterListQuery;
import com.prompthub.admin.seller.application.dto.SellerRegisterPageResult;
import com.prompthub.admin.seller.application.dto.SellerRegisterReviewResult;
import com.prompthub.admin.seller.application.dto.SellerRegisterSummaryResult;
import com.prompthub.admin.seller.application.usecase.SellerUseCase;
import com.prompthub.admin.seller.domain.model.SellerRegister;
import com.prompthub.admin.seller.domain.model.SellerRegisterStatus;
import com.prompthub.admin.seller.domain.repository.SellerRegisterRepository;
import com.prompthub.admin.user.domain.model.User;
import com.prompthub.admin.user.domain.model.UserRole;
import com.prompthub.admin.user.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SellerApplicationService implements SellerUseCase {

	private final SellerRegisterRepository sellerRegisterRepository;
	private final UserRepository userRepository;
	private final AuthorizationCacheRepository authorizationCacheRepository;

	@Override
	public SellerRegisterPageResult listSellerRegisters(SellerRegisterListQuery query) {
		int zeroBasedPage = query.page() - 1;

		List<SellerRegister> registers = sellerRegisterRepository.findAll(
			query.status(), zeroBasedPage, query.size());
		long total = sellerRegisterRepository.count(query.status());

		List<UUID> userIds = registers.stream()
			.map(SellerRegister::getUserId)
			.toList();

		Map<UUID, User> userMap = userRepository.findAllByIds(userIds).stream()
			.collect(Collectors.toMap(User::getUserId, Function.identity()));

		List<SellerRegisterSummaryResult> items = registers.stream()
			.filter(r -> userMap.containsKey(r.getUserId()))
			.map(r -> SellerRegisterSummaryResult.of(r, userMap.get(r.getUserId())))
			.toList();

		boolean hasNext = total > (long) query.page() * query.size();

		return new SellerRegisterPageResult(items, query.page(), query.size(), total, hasNext);
	}

	@Override
	@Transactional
	public SellerRegisterReviewResult approve(ApproveSellerCommand command) {
		SellerRegister register = findRegister(command.registerId());
		guardPending(register);

		register.approve();
		sellerRegisterRepository.save(register);

		User user = userRepository.findById(register.getUserId())
			.orElseThrow(() -> new AdminException(AdminErrorCode.USER_NOT_FOUND));
		user.addRole(UserRole.SELLER);
		userRepository.save(user);
		authorizationCacheRepository.evict(user.getUserId());

		return SellerRegisterReviewResult.from(register);
	}

	@Override
	@Transactional
	public SellerRegisterReviewResult reject(RejectSellerCommand command) {
		SellerRegister register = findRegister(command.registerId());
		guardPending(register);

		register.reject(command.rejectReason());
		sellerRegisterRepository.save(register);

		return SellerRegisterReviewResult.from(register);
	}

	private SellerRegister findRegister(UUID registerId) {
		return sellerRegisterRepository.findById(registerId)
			.orElseThrow(() -> new AdminException(AdminErrorCode.SELLER_REGISTER_NOT_FOUND));
	}

	private static void guardPending(SellerRegister register) {
		if (register.getStatus() != SellerRegisterStatus.PENDING) {
			throw new AdminException(AdminErrorCode.INVALID_INPUT_VALUE);
		}
	}
}
```

- [x] **Step 6: 테스트 실행해서 통과 확인**

Run: `./gradlew :admin-service:test --tests "com.prompthub.admin.seller.application.service.SellerApplicationServiceTest"`
Expected: PASS(4개 테스트)

- [x] **Step 7: 커밋**

```bash
git add admin-service/src/main/java/com/prompthub/admin/seller/application \
  admin-service/src/test/java/com/prompthub/admin/seller/application
git commit -m "$(cat <<'EOF'
feat: 어드민 판매자 등록심사 application 레이어 admin-service로 이관

- user-service AdminSellerApplicationService의 목록/승인/반려 로직을
  SellerApplicationService로 1:1 이관 — 승인 시 SELLER 역할 부여 후
  인가캐시 무효화, 반려는 캐시 무관 로직 그대로 유지
- com.prompthub.admin.user의 UserRepository를 그대로 참조(닉네임·이메일
  enrichment, 역할 부여) — 두 어드민 도메인이 같은 User 포트를 공유한다
EOF
)"
```

---

### Task 7: `com.prompthub.admin.seller` Presentation 레이어 (SellerController)

**Files:**
- Create: `admin-service/src/main/java/com/prompthub/admin/seller/presentation/dto/request/RejectSellerRegisterRequest.java`
- Create: `admin-service/src/main/java/com/prompthub/admin/seller/presentation/dto/response/SellerRegisterResponse.java`
- Create: `admin-service/src/main/java/com/prompthub/admin/seller/presentation/dto/response/SellerRegisterReviewResponse.java`
- Create: `admin-service/src/main/java/com/prompthub/admin/seller/presentation/controller/SellerController.java`
- Test: `admin-service/src/test/java/com/prompthub/admin/seller/presentation/controller/SellerControllerTest.java`

**Interfaces:**
- Consumes: Task 6의 `SellerUseCase`(`listSellerRegisters`, `approve`, `reject`), 공통 모듈의 `ApiResult`, `PageResponse`.
- Produces: `GET ${api.init}/admin/sellers/register`, `PATCH ${api.init}/admin/sellers/register/{registerId}/approve`, `PATCH ${api.init}/admin/sellers/register/{registerId}/reject` — 이 태스크가 마지막 신규 코드 레이어이며, 이후 태스크는 게이트웨이 라우팅과 user-service 삭제만 다룬다.

- [x] **Step 1: 요청/응답 DTO 작성**

`admin-service/src/main/java/com/prompthub/admin/seller/presentation/dto/request/RejectSellerRegisterRequest.java`:

```java
package com.prompthub.admin.seller.presentation.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "판매자 신청 반려 요청")
public record RejectSellerRegisterRequest(
	@Schema(description = "반려 사유", example = "포트폴리오가 확인되지 않습니다. 샘플을 보완 후 재신청해 주세요.")
	@NotBlank String rejectReason
) {
}
```

`admin-service/src/main/java/com/prompthub/admin/seller/presentation/dto/response/SellerRegisterResponse.java`:

```java
package com.prompthub.admin.seller.presentation.dto.response;

import com.prompthub.admin.seller.application.dto.SellerRegisterSummaryResult;
import com.prompthub.admin.seller.domain.model.SellerRegisterStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "관리자 — 판매자 신청 목록 항목")
public record SellerRegisterResponse(
	@Schema(description = "판매자 등록 신청 ID")
	String registerId,
	@Schema(description = "신청자 ID")
	String userId,
	@Schema(description = "신청자 이름", example = "이서아")
	String name,
	@Schema(description = "신청자 이메일", example = "seoah@example.com")
	String email,
	@Schema(description = "판매자 소개", nullable = true)
	String introduction,
	@Schema(description = "주력 카테고리", example = "[\"이미지 생성\"]")
	List<String> categories,
	@Schema(description = "포트폴리오 URL", nullable = true)
	String portfolioUrl,
	@Schema(description = "신청 상태 (pending | approved | rejected)", example = "pending")
	String status,
	@Schema(description = "신청 일시 (ISO 8601)", example = "2026-06-14T00:00:00")
	LocalDateTime submittedAt
) {
	public static SellerRegisterResponse from(SellerRegisterSummaryResult result) {
		return new SellerRegisterResponse(
			result.registerId().toString(),
			result.userId().toString(),
			result.name(),
			result.email(),
			result.introduction(),
			result.categories(),
			result.portfolioUrl(),
			mapStatus(result.status()),
			result.submittedAt()
		);
	}

	private static String mapStatus(SellerRegisterStatus status) {
		return switch (status) {
			case PENDING -> "pending";
			case APPROVED -> "approved";
			case REJECTED -> "rejected";
		};
	}
}
```

`admin-service/src/main/java/com/prompthub/admin/seller/presentation/dto/response/SellerRegisterReviewResponse.java`:

```java
package com.prompthub.admin.seller.presentation.dto.response;

import com.prompthub.admin.seller.application.dto.SellerRegisterReviewResult;
import com.prompthub.admin.seller.domain.model.SellerRegisterStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "판매자 신청 심사 결과 응답")
public record SellerRegisterReviewResponse(
	@Schema(description = "판매자 등록 신청 ID")
	String registerId,
	@Schema(description = "대상 사용자 ID")
	String userId,
	@Schema(description = "처리 상태 (approved | rejected)", example = "approved")
	String status,
	@Schema(description = "반려 사유 — 반려 시에만 포함", nullable = true)
	String rejectReason,
	@Schema(description = "심사 완료 일시 (ISO 8601)", example = "2026-06-17T10:00:00")
	LocalDateTime reviewedAt
) {
	public static SellerRegisterReviewResponse from(SellerRegisterReviewResult result) {
		return new SellerRegisterReviewResponse(
			result.registerId().toString(),
			result.userId().toString(),
			mapStatus(result.status()),
			result.rejectReason(),
			result.reviewedAt()
		);
	}

	private static String mapStatus(SellerRegisterStatus status) {
		return switch (status) {
			case PENDING -> "pending";
			case APPROVED -> "approved";
			case REJECTED -> "rejected";
		};
	}
}
```

- [x] **Step 2: 실패하는 SellerControllerTest 작성**

`admin-service/src/test/java/com/prompthub/admin/seller/presentation/controller/SellerControllerTest.java`:

```java
package com.prompthub.admin.seller.presentation.controller;

import com.prompthub.admin.seller.application.dto.SellerRegisterPageResult;
import com.prompthub.admin.seller.application.dto.SellerRegisterReviewResult;
import com.prompthub.admin.seller.application.dto.SellerRegisterSummaryResult;
import com.prompthub.admin.seller.application.usecase.SellerUseCase;
import com.prompthub.admin.seller.domain.model.SellerRegisterStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SellerController.class)
@ActiveProfiles("test")
class SellerControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private SellerUseCase sellerUseCase;

	@Test
	void 판매자_신청_목록을_조회한다() throws Exception {
		SellerRegisterSummaryResult summary = new SellerRegisterSummaryResult(
			UUID.fromString("00000000-0000-0000-0000-000000000101"),
			UUID.fromString("00000000-0000-0000-0000-000000000102"),
			"이서아", "seoah@example.com", "이미지 생성 전문",
			List.of("이미지 생성"), null, SellerRegisterStatus.PENDING,
			LocalDateTime.of(2026, 6, 14, 0, 0));
		when(sellerUseCase.listSellerRegisters(any()))
			.thenReturn(new SellerRegisterPageResult(List.of(summary), 1, 20, 1, false));

		mockMvc.perform(get("/api/v2/admin/sellers/register").param("status", "ALL"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data[0].name").value("이서아"))
			.andExpect(jsonPath("$.data[0].status").value("pending"));
	}

	@Test
	void 판매자_신청을_승인한다() throws Exception {
		UUID registerId = UUID.fromString("00000000-0000-0000-0000-000000000201");
		UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000202");
		when(sellerUseCase.approve(any())).thenReturn(new SellerRegisterReviewResult(
			registerId, userId, SellerRegisterStatus.APPROVED, null,
			LocalDateTime.of(2026, 7, 20, 10, 0)));

		mockMvc.perform(patch("/api/v2/admin/sellers/register/{registerId}/approve", registerId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("approved"));
	}

	@Test
	void 판매자_신청을_반려한다() throws Exception {
		UUID registerId = UUID.fromString("00000000-0000-0000-0000-000000000301");
		UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000302");
		when(sellerUseCase.reject(any())).thenReturn(new SellerRegisterReviewResult(
			registerId, userId, SellerRegisterStatus.REJECTED, "사유 불충분",
			LocalDateTime.of(2026, 7, 20, 10, 0)));

		mockMvc.perform(patch("/api/v2/admin/sellers/register/{registerId}/reject", registerId)
				.contentType("application/json")
				.content("{\"rejectReason\":\"사유 불충분\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("rejected"))
			.andExpect(jsonPath("$.data.rejectReason").value("사유 불충분"));
	}

	@Test
	void 반려_사유가_비어있으면_400을_내려준다() throws Exception {
		UUID registerId = UUID.fromString("00000000-0000-0000-0000-000000000401");

		mockMvc.perform(patch("/api/v2/admin/sellers/register/{registerId}/reject", registerId)
				.contentType("application/json")
				.content("{\"rejectReason\":\"\"}"))
			.andExpect(status().isBadRequest());
	}
}
```

- [x] **Step 3: 테스트 실행해서 컴파일 실패 확인**

Run: `./gradlew :admin-service:test --tests "com.prompthub.admin.seller.presentation.controller.SellerControllerTest"`
Expected: FAIL — `SellerController` 클래스가 없어 컴파일 에러.

- [x] **Step 4: SellerController 구현**

`admin-service/src/main/java/com/prompthub/admin/seller/presentation/controller/SellerController.java`:

```java
package com.prompthub.admin.seller.presentation.controller;

import com.prompthub.admin.seller.application.dto.ApproveSellerCommand;
import com.prompthub.admin.seller.application.dto.RejectSellerCommand;
import com.prompthub.admin.seller.application.dto.SellerRegisterListQuery;
import com.prompthub.admin.seller.application.dto.SellerRegisterPageResult;
import com.prompthub.admin.seller.application.dto.SellerRegisterReviewResult;
import com.prompthub.admin.seller.application.usecase.SellerUseCase;
import com.prompthub.admin.seller.domain.model.SellerRegisterStatus;
import com.prompthub.admin.seller.presentation.dto.request.RejectSellerRegisterRequest;
import com.prompthub.admin.seller.presentation.dto.response.SellerRegisterResponse;
import com.prompthub.admin.seller.presentation.dto.response.SellerRegisterReviewResponse;
import com.prompthub.admin.global.exception.AdminErrorCode;
import com.prompthub.admin.global.exception.AdminException;
import com.prompthub.exception.response.ErrorResponse;
import com.prompthub.presentation.dto.ApiResult;
import com.prompthub.presentation.dto.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("${api.init}/admin")
@RequiredArgsConstructor
@Tag(name = "Admin Seller", description = "관리자 판매자 등록 신청 심사 API (user-service 에서 이관)")
@SecurityRequirement(name = "gatewayHeaders")
public class SellerController {

	private final SellerUseCase sellerUseCase;

	@GetMapping("/sellers/register")
	@Operation(summary = "판매자 신청 목록 조회", description = "상태 필터 및 페이지네이션 지원. 역할: ADMIN")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "조회 성공"),
		@ApiResponse(responseCode = "401", description = "인증 정보 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "403", description = "ADMIN 권한 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	})
	public PageResponse<SellerRegisterResponse> listSellerRegisters(
		@Parameter(description = "신청 상태 필터 (PENDING | APPROVED | REJECTED | ALL)", example = "ALL")
		@RequestParam(defaultValue = "ALL") String status,
		@Parameter(description = "페이지 번호 (1부터 시작)", example = "1")
		@RequestParam(defaultValue = "1") int page,
		@Parameter(description = "페이지당 항목 수", example = "20")
		@RequestParam(defaultValue = "20") int size
	) {
		SellerRegisterListQuery query = new SellerRegisterListQuery(parseStatus(status), page, size);

		SellerRegisterPageResult result = sellerUseCase.listSellerRegisters(query);

		List<SellerRegisterResponse> responseData = result.items().stream()
			.map(SellerRegisterResponse::from)
			.toList();

		return PageResponse.success(responseData, result.page(), result.size(), result.total(), result.hasNext());
	}

	@PatchMapping("/sellers/register/{registerId}/approve")
	@Operation(summary = "판매자 신청 승인", description = "승인 시 SELLER 역할 부여. 역할: ADMIN")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "승인 성공"),
		@ApiResponse(responseCode = "400", description = "이미 심사된 신청",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "401", description = "인증 정보 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "403", description = "ADMIN 권한 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "404", description = "신청 내역을 찾을 수 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	})
	public ApiResult<SellerRegisterReviewResponse> approveSeller(
		@Parameter(description = "판매자 등록 신청 ID") @PathVariable UUID registerId
	) {
		SellerRegisterReviewResult result = sellerUseCase.approve(new ApproveSellerCommand(registerId));
		return ApiResult.success(SellerRegisterReviewResponse.from(result));
	}

	@PatchMapping("/sellers/register/{registerId}/reject")
	@Operation(summary = "판매자 신청 반려", description = "반려 사유를 포함하여 반려 처리. 역할: ADMIN")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "반려 성공"),
		@ApiResponse(responseCode = "400", description = "요청 값 오류 또는 이미 심사된 신청",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "401", description = "인증 정보 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "403", description = "ADMIN 권한 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "404", description = "신청 내역을 찾을 수 없음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	})
	public ApiResult<SellerRegisterReviewResponse> rejectSeller(
		@Parameter(description = "판매자 등록 신청 ID") @PathVariable UUID registerId,
		@Valid @RequestBody RejectSellerRegisterRequest request
	) {
		SellerRegisterReviewResult result = sellerUseCase.reject(
			new RejectSellerCommand(registerId, request.rejectReason()));
		return ApiResult.success(SellerRegisterReviewResponse.from(result));
	}

	private static SellerRegisterStatus parseStatus(String statusParam) {
		return switch (statusParam.toUpperCase()) {
			case "PENDING" -> SellerRegisterStatus.PENDING;
			case "APPROVED" -> SellerRegisterStatus.APPROVED;
			case "REJECTED" -> SellerRegisterStatus.REJECTED;
			case "ALL" -> null;
			default -> throw new AdminException(AdminErrorCode.INVALID_INPUT_VALUE);
		};
	}
}
```

- [x] **Step 5: 테스트 실행해서 통과 확인**

Run: `./gradlew :admin-service:test --tests "com.prompthub.admin.seller.presentation.controller.SellerControllerTest"`
Expected: PASS(4개 테스트)

- [x] **Step 6: 커밋**

```bash
git add admin-service/src/main/java/com/prompthub/admin/seller/presentation \
  admin-service/src/test/java/com/prompthub/admin/seller/presentation
git commit -m "$(cat <<'EOF'
feat: 어드민 판매자 등록심사 컨트롤러 admin-service로 이관(api/v2)

- user-service AdminSellerController의 엔드포인트 3개(목록/승인/반려)를
  SellerController로 이관, "Admin" 접두사 제거
- 이 커밋으로 admin-service의 신규 코드(Task 1~7)가 전부 갖춰짐 —
  이후 태스크는 게이트웨이 라우팅 이전과 user-service 삭제만 다룬다
EOF
)"
```

---

### Task 8: 게이트웨이 라우트 소유권 이전

**Files:**
- Modify: `apigateway/src/main/java/com/prompthub/apigateway/route/VersionedServiceRoute.java`
- Modify: `apigateway/src/test/java/com/prompthub/apigateway/route/VersionedRouteDefinitionLocatorTest.java`

**Interfaces:**
- Consumes: 없음(순수 라우팅 설정 변경, admin-service/user-service 코드와 컴파일 의존성 없음).
- Produces: 없음(최종 라우팅 동작 변경).

- [x] **Step 1: 실패하는 라우트 테스트 추가**

`VersionedRouteDefinitionLocatorTest.java`의 마지막 테스트 뒤에 아래 테스트를 추가한다(클래스 닫는 `}` 바로 앞):

```java
	@Test
	void 어드민_회원_판매자심사_경로는_admin_service가_소유하고_user_service는_소유하지_않는다() {
		Map<String, List<String>> config = new LinkedHashMap<>();
		config.put("admin-service", List.of("v2"));
		config.put("user-service", List.of("v1", "v2"));

		List<RouteDefinition> definitions = VersionedRouteDefinitionLocator.buildRouteDefinitions(propertiesOf(config));

		String adminPattern = pathPredicateValue(routeById(definitions, "admin-service"));
		String userPattern = pathPredicateValue(routeById(definitions, "user-service"));
		assertThat(adminPattern).contains("/api/v2/admin/users");
		assertThat(adminPattern).contains("/api/v2/admin/sellers/register");
		assertThat(userPattern).doesNotContain("/admin/");
	}
```

- [x] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew :apigateway:test --tests "com.prompthub.apigateway.route.VersionedRouteDefinitionLocatorTest"`
Expected: FAIL — `admin-service` 패턴에 아직 `/admin/users`·`/admin/sellers/register`가 없고, `user-service` 패턴엔 여전히 `/admin/**`가 남아있어 assertion 실패.

- [x] **Step 3: VersionedServiceRoute.java에서 경로 소유권 이전**

`apigateway/src/main/java/com/prompthub/apigateway/route/VersionedServiceRoute.java`에서 `admin-service`와 `user-service` 항목을 다음으로 교체한다(다른 항목은 그대로 둔다):

```java
        new VersionedServiceRoute(
            "admin-service",
            "lb://ADMIN-SERVICE",
            List.of(
                "/admin/settlements/**", "/admin/orders", "/admin/orders/**",
                "/admin/users", "/admin/users/**", "/admin/stats/users",
                "/admin/sellers/register", "/admin/sellers/register/**"
            ),
            1
        ),
```

```java
        new VersionedServiceRoute(
            "user-service",
            "lb://USER-SERVICE",
            List.of(
                "/auth/**", "/users/**",
                "/seller/**", "/sellers/**",
                "/wishlists/**"
            ),
            5
        )
```

(`user-service` 항목에서 `"/admin/**"`만 제거 — 나머지 `pathSuffixes`는 그대로.)

- [x] **Step 4: 테스트 실행해서 통과 확인**

Run: `./gradlew :apigateway:test --tests "com.prompthub.apigateway.route.VersionedRouteDefinitionLocatorTest"`
Expected: PASS(전체 테스트)

- [x] **Step 5: 커밋**

```bash
git add apigateway/src/main/java/com/prompthub/apigateway/route/VersionedServiceRoute.java \
  apigateway/src/test/java/com/prompthub/apigateway/route/VersionedRouteDefinitionLocatorTest.java
git commit -m "$(cat <<'EOF'
feat: 어드민 회원·판매자심사 경로 라우팅을 user-service에서 admin-service로 이전

- VersionedServiceRoute.ALL에서 "/admin/users", "/admin/users/**",
  "/admin/stats/users", "/admin/sellers/register",
  "/admin/sellers/register/**"를 user-service 항목의 pathSuffixes에서
  제거하고 admin-service 항목(order=1)으로 이동
- user-service 항목엔 이제 "/admin/**" catch-all이 완전히 사라짐 —
  이관 후 user-service에 남는 어드민 경로가 없으므로 무의미해진 규칙 삭제
- 이 커밋 이후 게이트웨이는 어드민 회원·판매자심사 요청을 전부
  admin-service로 넘긴다 — 이 태스크와 Task 9(user-service 코드 삭제)는
  같은 배포 단위로 함께 나가야 한다(하나만 나가면 API가 끊김)
- 회귀 방지 테스트 1개 추가
EOF
)"
```

---

### Task 9: user-service 어드민 코드 삭제

**Files:**
- Delete: `user-service/src/main/java/com/prompthub/user/admin/**`(패키지 전체 — controller 2개, usecase 2개, service 2개, dto 전부)
- Delete: `user-service/src/test/java/com/prompthub/user/admin/**`(대응 테스트 전체, 있는 경우)

**Interfaces:**
- Consumes: 없음(삭제 전용 태스크, Task 1~8이 admin-service·apigateway에 동등 기능을 이미 제공한 뒤에만 실행).
- Produces: 없음.

- [x] **Step 1: user-service 어드민 패키지 전체 삭제**

```bash
rm -rf user-service/src/main/java/com/prompthub/user/admin
rm -rf user-service/src/test/java/com/prompthub/user/admin
```

- [x] **Step 2: user-service 전체 빌드로 삭제 이후 컴파일·테스트 확인**

Run: `./gradlew :user-service:build`
Expected: BUILD SUCCESSFUL — 삭제한 클래스를 참조하는 곳이 남아있지 않고(다른 도메인이 `com.prompthub.user.admin`을 참조하는 곳이 없음은 이 플랜 작성 시점에 grep으로 사전 확인함), 남은 일반 회원/판매자/인증/찜 테스트가 전부 통과한다. 실패하면 에러가 가리키는 참조를 찾아 정리한다(단, 일반 회원/판매자/인증 도메인 코드는 변경하지 않는다).

- [x] **Step 3: 커밋**

```bash
git add -A user-service
git commit -m "$(cat <<'EOF'
refactor: user-service 어드민 API 제거(admin-service로 이관 완료)

- com.prompthub.user.admin 패키지 전체(컨트롤러 2개, 유스케이스 2개,
  서비스 2개, DTO 전부)와 대응 테스트를 삭제 — admin-service가
  Task 1~8에서 동등 기능을 이미 제공하므로 user-service 쪽 구현은
  더 이상 필요 없음
- 일반 회원 프로필(/users/me)·판매자 자기신청(/seller/register)·인증
  (로그인/OAuth/토큰재발급/로그아웃)·찜 도메인과 그 소유 User/
  SellerRegister/RefreshToken 엔티티, auth 패키지는 전혀 건드리지
  않음 — 삭제 대상은 com.prompthub.user.admin 하위뿐
- 이 커밋은 Task 8(게이트웨이 라우트 이전)과 같은 배포 단위여야 한다
EOF
)"
```

---

## 최종 확인

모든 태스크 완료 후:

- [x] `./gradlew :admin-service:test :apigateway:test :user-service:test` 전체 통과 확인
- [x] `admin-service/works/user/design.md`의 결정 사항 7개가 실제 구현과 일치하는지 재확인
- [ ] admin-service가 Redis에 실제로 붙는지 확인 — config 모듈 값은 이 플랜에서 채웠지만(Task 1 Step 2), 루트 `docker-compose.yml`의 admin-service `environment`에 `REDIS_HOST`/`REDIS_PORT`를 추가하는 건 세 모듈(`user-service`/`admin-service`/`apigateway`) 밖의 별도 작업으로 남아있다
- [x] 기존 `SellerNickname` 컬럼 매핑 이슈(`user_id` vs 실제 `id`) — Task 2 진행 중 User 엔티티 추가로 H2 테스트가 실제로 깨져(두 엔티티가 같은 `"user"` 테이블에 매핑되며 컬럼이 병합) 드러났다. 사용자 컨펌 후 `SellerNickname.java`의 `@Column(name = "user_id")` → `"id"`로 수정, `seller_nicknames.sql` 픽스처도 갱신(Task 2 커밋에 포함).

## 해결된 이슈

~~기존 `SellerNickname` 컬럼 매핑 오류~~ — 위 체크리스트 항목 참고, Task 2 커밋에서 수정 완료.
