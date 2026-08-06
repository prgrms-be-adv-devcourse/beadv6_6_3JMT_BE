payment-service DB 마이그레이션 규칙. `@Entity` 추가/변경, DB 스키마 변경 작업 시 따른다. 모노레포 공통 가이드는 [`../../../docs/guides/flyway-migration-guide.md`](../../../docs/guides/flyway-migration-guide.md)([ADR-0003](../../../docs/records/plan/infra/adr-0003-flyway-migrations.md)) 참조 — 이 문서는 그중 payment-service에 적용되는 부분만 발췌·구체화한다.

## 위치 / 파일명

`payment-service/src/main/resources/db/migration/V{n}__설명_스네이크케이스.sql` (파일명·버전 규칙 자체는 가이드 §1과 동일, 서비스 경로만 대입).

## PR 동반 필수

**`@Entity`를 추가/변경하는 PR은 반드시 대응하는 `V{n}` SQL을 같은 PR에 동반한다.** 이미 배포된 버전 파일을 수정하지 않고 `V{n+1}`로 추가하는 규칙도 가이드 §2를 그대로 따른다.

- **CHECK 제약처럼 Hibernate `validate`가 못 잡는 값 목록 불일치는 배포 시 기동 실패가 아니라 런타임에 제약 위반으로 나타난다.** 컬럼 존재 여부만 보는 `validate`의 한계이니 더 주의한다.
- **NOT NULL·UNIQUE 등 제약은 `V{n}` SQL과 `@Entity` 양쪽에 함께 반영한다.** `V{n}`에만 쓰면 테스트(Testcontainers, Hibernate `ddl-auto: create`가 엔티티 기준으로 스키마 생성)가 그 제약을 검증하지 못한다. `@Entity`에만 쓰면(예: `@Column(nullable = false)`) dev(`ddl-auto: validate`)가 컬럼 존재만 확인할 뿐 실제 DB 제약은 생기지 않아 운영 데이터 무결성이 뚫린다.

## SQL 작성 규칙

가이드 §3(스키마 접두사 미사용, 크로스 서비스 FK 금지, 서비스 내부 FK는 자유)을 그대로 따른다. 이 서비스의 스키마명은 `payment_service`.

## 로컬 검증

`application-local.yml`도 배포(Config Server, `payment-service.yml`)와 동일하게 `ddl-auto: validate`다 — 로컬·dev 모두 순수하게 `V{n}` SQL에만 의존한다(`schema.sql`/Hibernate `update` 보정 없음). 로컬 기동 성공이 곧 `V{n}` SQL의 완결성을 의미한다.

SQL 문법·제약조건만 빠르게 확인하고 싶으면 가이드의 임시 컨테이너 검증을 스키마명 `payment_service`로 대입해 쓴다.

## 테스트

`src/test/resources/application-test.yml`은 `spring.flyway.enabled: false` — 테스트는 (가이드가 언급하는 H2가 아니라) **Testcontainers PostgreSQL** 위에서 Hibernate `ddl-auto: create`(`AbstractIntegrationTest`/`AbstractJpaTest`가 프로퍼티로 지정)로 스키마를 자동 생성한다. **테스트 통과와 `V{n}` 작성 여부는 무관하다**(가이드 참고).
