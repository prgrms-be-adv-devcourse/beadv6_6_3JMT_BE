# 5개 마이크로서비스가 baseline SQL을 공유(전체 DB pg_dump)해서 쓰다가 생긴 스키마 오염과 복구

**날짜**: 2026-07-14
**상태**: Resolved
**분류**: Database / Flyway / MSA 스키마 분리

## 환경

PostgreSQL 18.4, 스키마 분리형 멀티테넌트(서비스당 전용 스키마: `user_service`, `payment_service`, `product_service`, `order_service`, `settlement_service`), Flyway

## 증상

[#0003](adr-0003-spring-boot-4-flyway-autoconfigure-modularization.md) 이슈를 고쳐 Flyway가 실제로 실행되기 시작하자 새로운 에러가 나타났다.

```
Caused by: org.flywaydb.core.internal.exception.FlywayMigrateException:
Failed to execute script V1__baseline.sql
SQL State  : 42P06
Message    : ERROR: schema "order_service" already exists
```

Flyway가 baseline을 실행하려다 스키마 충돌로 실패했다.

## 핵심 원인: baseline SQL이 서비스별로 분리되어 있지 않았다

`payment-service`의 `V1__baseline.sql`을 열어보니, 이 서비스가 실제로 소유한 테이블(`payment`, `refund`, `order_snapshot` 단 3개)뿐 아니라 `order_service`·`payment_service`·`product_service`·`settlement_service`·`user_service` **5개 스키마를 전부 `CREATE SCHEMA`로 만들려고 시도**하고 있었다.

```sql
CREATE SCHEMA order_service;
CREATE SCHEMA payment_service;
CREATE SCHEMA product_service;
CREATE SCHEMA settlement_service;
CREATE SCHEMA user_service;
```

게다가 그 안의 `CREATE TABLE`들은 전부 스키마 지정 없이 `public.xxx`로 되어 있었다 — `auth`, `cart`, `order`, `product`, `user`, `settlement` 등 5개 서비스의 모든 테이블이 이 파일 하나에 다 들어있었다.

```bash
$ grep -n "^CREATE TABLE\|^CREATE SCHEMA" payment-service/src/main/resources/db/migration/V1__baseline.sql
24:CREATE SCHEMA order_service;
33:CREATE SCHEMA payment_service;
42:CREATE SCHEMA product_service;
51:CREATE SCHEMA settlement_service;
60:CREATE SCHEMA user_service;
73:CREATE TABLE public.auth (
...
426:CREATE TABLE public.order_snapshot (
...
(34개 테이블 전부 public.* 로)
```

5개 서비스의 `V1__baseline.sql`을 MD5로 비교해보니, 사실상 완전히 동일한 파일 2세트(3개 서비스가 한 세트, 2개가 다른 세트, 내용은 사실상 동일)였다. 즉 "각 서비스가 실제 소유한 테이블만 추려서 자기 스키마에 넣는" 필터링 단계가 통째로 빠진 채, 로컬 개발 DB(스키마 분리 이전 시절의 전체 DB) 전체를 `pg_dump`로 한 번 뜬 결과물을 5개 서비스 마이그레이션 폴더에 그대로 복사한 것으로 보였다.

이 결함이 그동안 발견되지 않았던 이유는 [#0003](adr-0003-spring-boot-4-flyway-autoconfigure-modularization.md) 때문이었다 — Flyway 자동설정 자체가 안 걸려서 baseline SQL이 한 번도 실제로 실행된 적이 없었다. 자동설정 문제를 고치자마자 바로 다음 레이어의 버그가 튀어나온 것이다.

## 조사 과정

### 1. 서비스별 실제 소유 테이블 확정

5개 서비스의 `src/main/java`를 각각 뒤져 `@Entity` 클래스 기준으로 실제 소유 테이블 목록을 확정했다.

| 서비스 | 소유 테이블 |
|---|---|
| user-service | auth, refresh_token, seller_register, seller_register_category, seller_settlement, user, user_role, wishlist (8개) |
| payment-service | payment, refund, order_snapshot (3개) |
| product-service | product, review, product_processed_event (3개) |
| order-service | cart, cart_product, order, order_product, order_payment, order_processed_event, order_outbox_event (7개) |
| settlement-service | settlement, settlement_batch, settlement_detail, settlement_outbox_event, settlement_source_line + Spring Batch 인프라 테이블 6개 (11개) |

pg_dump 원본에 있던 `order_refund`, `order_refund_product` 두 테이블은 5개 서비스 어디에도 대응하는 `@Entity`가 없었다(order-service엔 `OrderRefundPayload`라는 Kafka 이벤트 record만 존재) — 과거 설계의 잔재로 판단하고 재작성한 baseline에서 제외했다.

### 2. 서비스 내부 FK만 남기고 크로스 서비스 FK 제거

원본에 걸려있던 FK 제약을 전부 조사해, 서비스 내부 참조(`cart_product.cart_id → cart.id`, `review.product_id → product.id`, `settlement_detail.settlement_id → settlement.settlement_id` 등)만 남기고, 서비스 경계를 넘는 컬럼(`order_id`, `user_id`, `seller_id` 등)은 실제 FK 제약 없이 순수 컬럼값으로만 유지했다(MSA 원칙).

### 3. 로컬에서 재작성한 baseline 검증

배포 전 로컬 postgres 컨테이너에 5개 스키마를 만들고, 서비스별로 `search_path`만 바꿔가며 재작성한 SQL을 실행해 정확히 예상 테이블만 생성되는지 확인했다.

```bash
docker run -d --name baseline-verify -e POSTGRES_USER=prompthub \
  -e POSTGRES_PASSWORD=test -e POSTGRES_DB=prompthub -p 15432:5432 postgres:18.4-alpine

docker exec baseline-verify psql -U prompthub -d prompthub -c "
CREATE SCHEMA order_service; CREATE SCHEMA payment_service;
CREATE SCHEMA product_service; CREATE SCHEMA settlement_service; CREATE SCHEMA user_service;
"

for svc in user payment product order settlement; do
  docker exec -e PGOPTIONS="-c search_path=${svc}_service" baseline-verify \
    psql -U prompthub -d prompthub -v ON_ERROR_STOP=1 -f /tmp/${svc}.sql
done

docker exec baseline-verify psql -U prompthub -d prompthub -c "\dt payment_service.*"
#  payment_service | order_snapshot | table | prompthub
#  payment_service | payment        | table | prompthub
#  payment_service | refund         | table | prompthub
```

5개 서비스 전부 에러 없이 통과했고, 각 스키마에 정확히 예상한 테이블만 생성됨을 확인했다.

### 4. 파생된 2차 문제: public 스키마에 남아있던 실제 데이터

잘못된 baseline이 딱 한 번 성공적으로 실행됐던 시점이 있어(여러 서비스가 동시에 배포를 시도하는 과정에서 먼저 성공한 하나가 `public`에 전체 테이블을 만들어 버림), `public` 스키마에 실제 서비스 데이터가 남아있었다.

```sql
SELECT relname, n_live_tup FROM pg_stat_user_tables WHERE schemaname='public' ORDER BY n_live_tup DESC;
--  payment        | 132
--  order_payment  | 132
--  order_product  |  94
--  ...
```

각 서비스가 새 스키마(`?currentSchema=xxx_service`)로 접속하도록 바뀌면서 이 데이터는 `search_path`에서 벗어나 앱에서 보이지 않게 됐다(삭제된 것은 아니었다). FK 의존순서(부모→자식)를 지켜 트랜잭션 하나로 정식 스키마에 이관했다.

```sql
BEGIN;
INSERT INTO order_service."order" (id, ...) SELECT id, ... FROM public."order";
INSERT INTO order_service.order_product (id, ...) SELECT id, ... FROM public.order_product;
-- ... (부모 먼저, 자식 나중)
COMMIT;
```

`GENERATED ALWAYS AS IDENTITY` 컬럼이 있는 테이블(`order_processed_event` 등)은 데이터가 0건이어도 `OVERRIDING SYSTEM VALUE` 없이 PK 값을 직접 넣는 INSERT 문 자체가 문법 오류로 거부된다는 것도 이 과정에서 확인했다.

## 해결

각 서비스의 `V1__baseline.sql`을 서비스별 실제 소유 테이블만 담도록 전면 재작성했다. 스키마 접두사(`public.xxx`)는 쓰지 않고 테이블명만 써서(`CREATE TABLE xxx (...)`) JDBC URL의 `?currentSchema=`에 스키마 결정을 위임했다.

## 검증

각 서비스 스키마에 `\dt {schema}.*`로 예상 테이블 목록과 정확히 일치하는지 확인. 실제 개발서버 배포 후 5개 서비스 전부 크래시 없이 기동, `public`에서 이관한 데이터의 row count가 원본과 정확히 일치하는 것까지 재확인했다.

## 교훈 / 재발 방지

- **baseline SQL을 pg_dump로 뽑을 때 "전체 DB"가 아니라 "이 서비스가 소유한 테이블만" 필터링하는 단계를 절대 생략하면 안 된다.** 여러 서비스가 원래 스키마 분리 없이 개발되다가 나중에 스키마별로 나뉘는 마이그레이션 상황에서는 "예전에 한 DB를 같이 쓰던 코드"를 그대로 복사하고 싶은 유혹이 생기기 쉬운데, 이게 이번 사고의 직접 원인이었다.
- 여러 개의 독립된 버그가 겹쳐 있을 때는 하나를 고치면 반드시 다음 레이어의 버그가 드러난다([#0003](adr-0003-spring-boot-4-flyway-autoconfigure-modularization.md)이 가리고 있던 이 문제가 그 예다). "이제 진짜 원인을 찾았다"고 확신하기보다, 고친 다음 실제로 끝까지 도는지 계속 검증하는 태도가 필요하다.
- MSA에서 서비스별 스키마 분리를 도입할 때, 각 서비스가 실제 소유하는 테이블 목록을 엔티티 코드 기준으로 미리 문서화해두면 이런 필터링 실수를 방지할 수 있다.
