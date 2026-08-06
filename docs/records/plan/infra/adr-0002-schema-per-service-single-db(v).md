# ADR-0002: 단일 DB 안에서 서비스별 스키마와 전용 계정으로 데이터 경계를 강제한다

- 상태: accepted
- 날짜: 2026-07-05
- 관련: `docker-compose.yml`, 각 서비스 `application.yml`의 datasource, DB init 스크립트

## 컨텍스트

5개 서비스(user·product·order·payment·settlement)가 Postgres 컨테이너 1개의
단일 데이터베이스, 단일 `public` 스키마, 단일 계정을 공유하고 있었다.
코드 레벨에서는 이미 분리되어 있었다 — 서비스 간 JPA 연관관계와 native query가 없고,
타 도메인 참조는 UUID 컬럼 + gRPC/Feign 조회로 처리한다. 그러나 물리 레벨에서는
어떤 서비스든 타 도메인 테이블을 직접 읽고 쓸 수 있어 MSA의 데이터 소유권 원칙이
DB에서 강제되지 않았다.

분리 수준으로 세 가지를 검토했다.

1. **단일 DB + 서비스별 PG 스키마 + 서비스별 계정** — 권한으로 경계 강제
2. 단일 인스턴스 + 서비스별 데이터베이스 — DB 간 조인이 구조적으로 불가능
3. 서비스별 Postgres 컨테이너 — 완전한 물리 분리

현재 인프라는 단일 EC2 + docker-compose이며, 상시 트래픽이 없는 교육 프로젝트다.

## 결정

**옵션 1을 채택한다. 데이터베이스는 1개 유지, 서비스마다 전용 스키마와 전용 계정을
만들고 권한으로 타 스키마 접근을 차단한다.**

- 스키마명 = 계정명 = 서비스명 스네이크 (`user_service`, `product_service`,
  `order_service`, `payment_service`, `settlement_service`)
- 각 계정은 자기 스키마만 소유·접근. `public` 및 타 스키마는 REVOKE.
- 스키마 지정은 JDBC URL `?currentSchema=` (설정 파일에서 명시적으로 보이게) +
  init 스크립트의 `ALTER ROLE ... SET search_path` (psql 직접 접속 시에도 일치) 이중화.
  Hibernate·Flyway·Spring Batch 메타테이블·JdbcTemplate이 모두 동일 스키마를 향한다.
- 스키마/계정 생성은 `docker-entrypoint-initdb.d` init SQL이 담당한다
  (Flyway는 자기 계정으로 접속한 뒤에야 실행되므로 role 생성을 맡을 수 없다).

옵션 3(컨테이너 분리)은 단일 EC2에서 메모리·볼륨·백업 부담이 5배가 되어 기각.
옵션 2(DB 분리)는 경계 강제력은 더 강하지만, init·백업·로컬 온보딩이 DB 수만큼
늘어나는 데 비해 옵션 1의 권한 차단으로도 동일한 실질 경계를 얻을 수 있어 기각.

기존 `public` 스키마의 데이터는 새 스키마로 이전하지 않는다(테스트 데이터).
단, 볼륨 삭제 전 `pg_dump` 전체 백업을 EC2 밖에 보관하고, `public` 스키마 및
볼륨 삭제는 팀 공지 후 별도 승인 단계로 진행한다.

## 결과

**구현 현황 (2026-07-22 갱신):**
- ✅ 스키마 분리: 6개 스키마 생성됨 (user_service, product_service, order_service, payment_service, settlement_service, public)
- ✅ JDBC 설정: 각 서비스의 `currentSchema` 파라미터로 의도된 스키마를 명시적으로 지정
- ✅ 역할(계정) 생성: `docker-entrypoint-initdb.d/01-init-schemas-and-roles.sh`가 스키마별
  전용 역할(user_service, product_service 등)을 이미 생성·GRANT까지 완료
- ❌ 역할 실사용: **여전히 미흡** — 각 서비스 datasource(`configs/*.yml`)는 아직
  `${POSTGRES_USER}` 슈퍼유저 계정으로 접속 중. 역할은 만들어졌지만 아무 서비스도 쓰지 않는다.
  → 남은 작업은 "역할 생성"이 아니라 "각 서비스 datasource의 계정명·password를 전용
    역할로 교체"뿐.
- ⚠ **admin-service 예외 (작성 당시 미존재)**: admin-service는 5개 스키마 전부에
  `currentSchema`를 걸고 `ddl-auto: none`으로 cross-schema 직접 조회한다. 역할 분리를
  실제로 완료하면(위 항목) admin-service의 이 접근 방식이 곧바로 막히므로, 그 시점에
  admin-service 전용 역할(5개 스키마 읽기 권한)을 별도 설계해야 한다.

**남은 실현 경로:**
1. ~~init SQL에서 각 스키마용 역할 생성 + GRANT 권한 설정~~ — 완료
2. 각 서비스의 datasource 설정(config 서버 yml)에서 password 추가, 계정명 변경 (다음 단계)
3. admin-service 전용 역할(cross-schema 읽기) 설계
4. docker-compose 재시작 + 기존 볼륨 삭제 후 init SQL 재실행 (팀 공지 후)

**장기 이점:**
- 서비스가 타 도메인 데이터를 필요로 하면 SQL이 아니라 gRPC/Kafka로만 얻을 수 있다.
  경계 위반이 코드 리뷰가 아니라 DB 권한 오류로 즉시 드러난다.
- 향후 특정 서비스의 DB를 물리적으로 분리(옵션 2·3)할 때, 해당 스키마만
  `pg_dump -n <schema>`로 들어내면 되므로 이 결정은 상위 분리의 중간 단계로 호환된다.
- 로컬 개발도 동일 구조가 필요하므로 로컬용 docker-compose + 동일 init SQL을
  팀 표준으로 제공한다. 재검토 트리거: 서비스별 부하 격차로 인스턴스 분리가
  필요해지는 시점, 또는 EC2 단일 호스트 탈피.
