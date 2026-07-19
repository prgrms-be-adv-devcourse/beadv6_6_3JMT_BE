payment-service DB 마이그레이션 규칙. `@Entity` 추가/변경, DB 스키마 변경 작업 시 따른다. 모노레포 공통 가이드는 [`../../../docs/guides/flyway-migration-guide.md`](../../../docs/guides/flyway-migration-guide.md)([ADR-0003](../../../docs/adr/0003-flyway-migrations.md)) 참조 — 이 문서는 그중 payment-service에 적용되는 부분만 발췌·구체화한다.

## 위치 / 파일명

`payment-service/src/main/resources/db/migration/V{n}__설명_스네이크케이스.sql`

- 현재 최신 버전은 `V2__update_payment_status_and_refund_constraints.sql`. 다음 마이그레이션은 `V3`부터 시작한다.
- `n`은 이 폴더에서 가장 큰 버전 번호 다음 값.

## PR 동반 필수

**`@Entity`를 추가/변경하는 PR은 반드시 대응하는 `V{n}` SQL을 같은 PR에 동반한다.** 마이그레이션 없이 엔티티만 바꾸면 배포 환경(dev, `ddl-auto: validate`)에서 Hibernate가 컬럼 불일치로 기동을 실패시키거나, CHECK 제약처럼 validate가 못 잡는 값 목록 불일치는 **런타임에 제약 위반**으로 나타난다.

- **이미 배포된 버전 파일은 절대 수정하지 않는다.** 체크섬 불일치로 다음 배포 시 기동 실패. 실수가 있으면 새 `V{n+1}`로 추가한다.
- **NOT NULL·UNIQUE 등 제약은 `V{n}` SQL과 `@Entity` 양쪽에 함께 반영한다.** `V{n}`에만 쓰면 테스트(Testcontainers, Hibernate `ddl-auto: create`가 엔티티 기준으로 스키마 생성)가 그 제약을 검증하지 못한다. `@Entity`에만 쓰면(예: `@Column(nullable = false)`) dev(`ddl-auto: validate`)가 컬럼 존재만 확인할 뿐 실제 DB 제약은 생기지 않아 운영 데이터 무결성이 뚫린다.

## SQL 작성 규칙

- 스키마 접두사를 쓰지 않는다. `payment_service.payment`가 아니라 `CREATE TABLE payment (...)`. JDBC `currentSchema=payment_service`가 처리한다.
- 다른 서비스 소유 테이블에 대한 실제 FK(`REFERENCES`)를 걸지 않는다. `order_id`, `user_id` 등 크로스 서비스 참조 컬럼은 값만 저장하는 순수 컬럼으로 둔다.
- `payment` ↔ `refund` 같은 서비스 내부 테이블 간 FK는 자유롭게 건다.

## 로컬 검증

`application-local.yml`도 배포(Config Server, `payment-service.yml`)와 동일하게 `ddl-auto: validate`다 — 로컬·dev 모두 순수하게 `V{n}` SQL에만 의존한다(`schema.sql`/Hibernate `update` 보정 없음). 로컬 기동 성공이 곧 `V{n}` SQL의 완결성을 의미한다.

가이드가 제시하는 임시 컨테이너 검증(`docker exec ... -c "CREATE SCHEMA payment_service;"` 후 `psql -f V{n}.sql`)은 DB만 띄우고 SQL 문법·제약조건을 빠르게 확인하고 싶을 때 쓴다.

## 테스트

`src/test/resources/application-test.yml`은 `spring.flyway.enabled: false` — 테스트는 Testcontainers PostgreSQL 위에서 Hibernate `ddl-auto: create`(`AbstractIntegrationTest`/`AbstractJpaTest`가 프로퍼티로 지정)로 스키마를 자동 생성한다. **테스트 통과와 `V{n}` 작성 여부는 무관하다.**
