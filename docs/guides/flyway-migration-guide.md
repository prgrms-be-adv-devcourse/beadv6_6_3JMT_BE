# Flyway 마이그레이션 가이드

> **관련 ADR:** [ADR-0003: 스키마 변경은 Flyway 마이그레이션으로 관리하고 Hibernate는 validate만 한다](../adr/0003-flyway-migrations.md)

## 이 프로젝트에서 Flyway가 하는 일

- 스키마 변경 이력을 SQL 파일로 버전 관리한다. Hibernate는 스키마를 만들지 않고 `ddl-auto: validate`로 엔티티-스키마 일치만 검증한다.
- 서비스마다 자기 소유 스키마를 쓴다(`user_service`, `payment_service`, `product_service`, `order_service`, `settlement_service`). JDBC URL의 `?currentSchema=xxx_service`로 접속한다.
- 마이그레이션 파일 위치: `{service}/src/main/resources/db/migration/V{n}__설명.sql`. 서버가 기동될 때 Flyway가 아직 적용 안 된 버전을 번호 순서대로 실행한다.

## 새 마이그레이션 작성하기

**엔티티(`@Entity`)를 추가/변경하는 PR은 반드시 대응하는 `V{n}` SQL을 같은 PR에 동반한다.** 마이그레이션 없이 엔티티만 바꾸면 배포 시 Hibernate `validate`가 컬럼 불일치로 서비스 기동을 실패시킨다.

1. **파일명**: `V{n}__설명_스네이크케이스.sql`
   예: `V2__add_refresh_token_epoch.sql`. `n`은 그 서비스 마이그레이션 폴더에서 가장 큰 버전 번호 다음 값.

2. **이미 배포된 버전 파일은 절대 수정하지 않는다.**
   Flyway가 각 버전의 체크섬을 기록해두기 때문에, 배포된 파일 내용을 바꾸면 다음 배포 때 체크섬 불일치로 서비스가 기동 실패한다. 컬럼을 빠뜨렸거나 실수가 있으면 그 버전을 고치지 말고 `V{n+1}`로 새로 추가한다.

3. **SQL 작성 규칙**
   - 스키마 접두사를 쓰지 않는다. `payment_service.payment`가 아니라 그냥 `CREATE TABLE payment (...)`, `ALTER TABLE payment ...`. JDBC `currentSchema`가 알아서 그 스키마에 적용한다.
   - 다른 서비스가 소유한 테이블을 참조하는 실제 FK(`REFERENCES`)는 걸지 않는다. `order_id`, `user_id` 같은 크로스 서비스 참조 컬럼은 값만 저장하는 순수 컬럼으로 둔다 — MSA 서비스 경계를 DB 레벨에서도 지킨다.
   - 서비스 내부 테이블 간 FK(예: `cart_product.cart_id → cart.id`)는 자유롭게 건다.

```sql
-- V2__add_refresh_token_epoch.sql 예시
ALTER TABLE refresh_token
    ADD COLUMN epoch bigint NOT NULL DEFAULT 0;
```

## 로컬에서 검증하기

로컬 `docker-compose up -d`로 postgres를 띄우고 서비스를 기동하면 Flyway가 자동으로 새 `V{n}` 파일을 실행한다. 문제가 있으면 그 자리에서 기동 실패로 바로 확인된다.

SQL 문법/제약조건만 빠르게 따로 검증하고 싶으면 임시 컨테이너에 대고 실행해본다.

```bash
docker run -d --name flyway-verify \
  -e POSTGRES_USER=prompthub -e POSTGRES_PASSWORD=test -e POSTGRES_DB=prompthub \
  -p 15432:5432 postgres:18.4-alpine

docker exec flyway-verify psql -U prompthub -d prompthub -c "CREATE SCHEMA payment_service;"

docker exec -e PGOPTIONS="-c search_path=payment_service" flyway-verify \
  psql -U prompthub -d prompthub -v ON_ERROR_STOP=1 -f V2__xxx.sql
```

## 테스트에서는 Flyway가 꺼져 있다

각 서비스 `src/test/resources/application-test.yml`에 `spring.flyway.enabled: false`가 설정되어 있다. 테스트(H2 또는 Testcontainers)는 Hibernate `ddl-auto: create`/`create-drop`으로 스키마를 자동 생성한다.

**테스트 통과와 마이그레이션 파일 작성은 별개다.** 테스트가 통과해도 `V{n}` SQL을 안 만들었으면 실제 배포 환경에서는 실패한다. 엔티티를 바꿨으면 테스트만 믿지 말고 마이그레이션 파일도 챙긴다.

## Spring Batch를 쓰는 서비스 (현재 settlement-service)

`spring.batch.jdbc.initialize-schema`가 기본값이면 Spring Batch가 스키마를 자동 생성하지 않는다. 배치 인프라 테이블(`batch_job_instance`, `batch_job_execution`, `batch_job_execution_context`, `batch_job_execution_params`, `batch_step_execution`, `batch_step_execution_context`)과 시퀀스(`batch_job_instance_seq`, `batch_job_execution_seq`, `batch_step_execution_seq`)가 baseline에 이미 포함되어 있다. 이 서비스에서 배치 관련 스키마를 바꿀 일이 있으면 이 부분도 마이그레이션 대상이다.

## 배포 흐름

1. PR이 `develop`에 머지되면 CD가 각 서비스 이미지를 빌드해 개발서버(dev)에 배포한다.
2. 컨테이너가 뜰 때 Flyway가 미적용 마이그레이션을 순서대로 실행한다.
3. 그 다음 Hibernate가 `validate`로 엔티티-스키마 일치를 검증한다. 불일치가 있으면 서비스가 기동 실패한다.

운영(prod) 환경은 없다 — `dev`가 최종 배포 환경이다([ADR-0005](../adr/0005-develop-deploy-main-freeze.md) 참고).

---

Flyway 도입 과정에서 겪은 구체적인 장애 사례는 [`docs/adr/troubleshooting/`](../adr/troubleshooting/)를 참고한다.
