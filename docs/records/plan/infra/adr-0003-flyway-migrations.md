# ADR-0003: 스키마 변경은 Flyway 마이그레이션으로 관리하고 Hibernate는 validate만 한다

- 상태: **완료** (2026-07-22 갱신: 5개 서비스 전부 Flyway 마이그레이션 적용·배포 완료)
  - ddl-auto: validate는 config 서버에 반영됨 ✅
  - flyway 의존성/마이그레이션 폴더: 5개 서비스(user·product·order·payment·settlement)
    전부 `db/migration/` 존재, root `build.gradle`에 `spring-boot-starter-flyway` 공통 적용 ✅
  - V1 baseline 추출 및 이후 마이그레이션(order V1~V6, payment V1~V9, product V1~V2,
    settlement V1~V2, user V1~V2) 완료 ✅
  - 관련 후속 트러블슈팅: `../records/troubleshooting/infra/adr-0003-spring-boot-4-flyway-autoconfigure-modularization.md`,
    `../records/troubleshooting/infra/adr-0004-shared-baseline-sql-schema-pollution.md` (둘 다 Resolved, docs/records로 이동됨)
- 날짜: 2026-07-05 (연기 결정: 2026-07-10)
- 관련: ADR-0002(스키마 분리), 각 서비스 `application.yml`의 jpa/flyway 설정,
  각 서비스 `src/main/resources/db/migration/`

## 컨텍스트

전 서비스가 `ddl-auto: update`로 운영 DB 스키마를 Hibernate에 맡기고 있었다.
이 방식은 추가만 하고 삭제·이름변경·제약 강화를 못 하며, 변경 이력이 없고,
엔티티 수정이 리뷰 없이 운영 DB를 조용히 바꾼다. ADR-0002의 스키마 분리로
어차피 새 스키마에 테이블을 처음부터 만들어야 하므로, "최초 생성 SQL = V1 baseline"이
되는 지금이 마이그레이션 도구 도입 비용이 가장 낮은 시점이다. 팀 합의 완료.

## 결정

**전 서비스에 Flyway를 도입한다. `ddl-auto`는 `validate`로 전환한다.**

- V1 baseline은 현재 운영 DB를 `pg_dump --schema-only`로 추출(백업 겸)한 뒤
  테이블→서비스 매핑으로 서비스별로 분리해 만든다. Hibernate가 실제로 만들어 쓰던
  DDL 그대로이므로 엔티티 정합이 보장된다. 각 도메인 담당자는 자기 서비스 V1만 리뷰한다.
- **이후 규칙: 엔티티를 변경하는 PR은 반드시 `V{n}__<설명>.sql`을 동반한다.**
  누락 시 배포 환경에서 `validate`가 기동 실패로 막는다.
- 테스트는 전 서비스 테스트 프로파일에서 `flyway.enabled=false` + `ddl-auto: create-drop`을
  유지한다(기존 테스트 방식 보존 — order의 H2, payment의 Testcontainers 등).
  V1 정합 검증은 로컬 compose에서 전 서비스 기동 + validate 통과로 확인한다.

대안으로 `ddl-auto: update` 유지(도구 없음)와 담당자 수기 DDL 작성을 검토했다.
전자는 위 컨텍스트의 문제를 존치하고 baseline 추출 기회를 잃어 기각,
후자는 5명 일정에 묶여 완료 시점을 통제할 수 없어 기각.

## 구현 체크리스트 (완료 — 2026-07-22 갱신)

**1단계: Init SQL 작성**
- [x] ADR-0002 역할/권한 설정(각 서비스별 전용 역할 생성 + GRANT) SQL 작성
  (`docker-entrypoint-initdb.d/01-init-schemas-and-roles.sh`)
- [x] `docker-entrypoint-initdb.d/` 폴더 생성, init SQL 마운트 설정 추가 (docker-compose.yml)
- [x] 로컬 테스트 (기존 볼륨 삭제 후 docker-compose up -d postgres로 init 재실행 확인)

**2단계: V1 Baseline 추출**
- [x] `pg_dump --schema-only -n user_service,product_service,order_service,payment_service,settlement_service`로 현재 스키마 덤프
- [x] 테이블→서비스 매핑에 따라 각 서비스별로 분리해 `src/main/resources/db/migration/V1__baseline.sql` 작성
  (user-service는 auth, refresh_token, seller_*, user, user_role, wishlist 등)

**3단계: Flyway 의존성 추가**
- [x] root `build.gradle`에 `spring-boot-starter-flyway`(+ `flyway-database-postgresql` runtimeOnly)를
  6개 Spring Boot 앱 공통으로 적용 (개별 서비스 build.gradle 추가 방식 대신 루트 공통화로 처리)
- [x] `application.yml`에 flyway 활성화 설정 추가 (기본값은 대부분 활성화)

**4단계: 테스트 설정 확인**
- [x] 각 서비스 test 프로필: `flyway.enabled=false` + `ddl-auto: create-drop` 유지 (이미 구성됨) ✅

**이후 실제 진행**: V1 이후로도 마이그레이션이 누적됐다(order V1~V6, payment V1~V9,
product V1~V2, settlement V1~V2, user V1~V2) — "엔티티 변경 PR은 `V{n}__*.sql` 동반" 규칙이
실제로 팀 워크플로에 정착된 상태.

**결과 기대값:**
- 스키마 변경이 PR 리뷰 대상이 되고, `flyway_schema_history`로 모든 환경(로컬/운영)이 같은 경로로 같은 스키마에 도달한다.
- 팀원 전원의 워크플로가 바뀐다(엔티티 변경 = V{n}__*.sql 동반). 온보딩 문서 제공 필요.
- V1 SQL 정합성은 로컬 compose 기동 + validate 통과로 확인 (CI 자동화는 후속).
