# bootRun 시 local 프로필 강제해도 Config Server fail-fast로 기동 실패

**날짜**: 2026-07-20
**상태**: Investigating (우회만 확인, 근본 원인은 미확정)
**분류**: Build & Dependencies / Spring Cloud Config

## 환경

Spring Boot 4.1, Spring Cloud Config Client 5.0.4, Gradle 9.5.1, 로컬 macOS.

## 증상

V5 Flyway 마이그레이션을 로컬 DB에 실제로 적용해 검증하려고 `../gradlew :payment-service:bootRun`을 실행했으나, Config Server(`http://localhost:8888`, 미기동 상태)에 연결을 시도하다 아래 예외로 기동 자체가 실패했다.

```
org.springframework.cloud.config.client.ConfigClientFailFastException: Could not locate PropertySource and the fail fast property is set, failing
Caused by: org.springframework.web.client.ResourceAccessException: I/O error on GET request for "http://localhost:8888/payment-service/default": Connection refused
```

`application-local.yml`에는 `spring.cloud.config.enabled: false`가 이미 있어 `local` 프로필만 활성화하면 우회될 것으로 예상했으나, 아래 세 가지 방법 모두 동일하게 실패했다.

1. `SPRING_PROFILES_ACTIVE=local ../gradlew :payment-service:bootRun`
2. `../gradlew :payment-service:bootRun -Dspring-boot.run.profiles=local`
3. `../gradlew :payment-service:bootRun --args="--spring.profiles.active=local"`

## 핵심

세 가지 서로 다른 프로필 지정 방식이 전부 동일한 실패로 이어진 것으로 보아, 단순 환경변수 전달 문제(예: Gradle 데몬이 최초 기동 시점의 env를 캐싱)가 아니라 `application.yml`의 `spring.config.import: - ${CONFIG_IMPORT:optional:configserver:}` + `spring.cloud.config.fail-fast: true` 조합 자체가 문제로 추정된다. `optional:` 접두사는 위치가 아예 없을 때(404 등)만 실패를 억제하며, `fail-fast: true` 상태에서 서버가 connection refused로 응답하면 재시도 후 `ConfigClientFailFastException`이 그대로 전파되는 것으로 보인다 — `application-local.yml`의 `spring.cloud.config.enabled: false`가 병합되는 시점보다 config-import 시도가 먼저 실행되는 것으로 추정(정확한 Boot 내부 처리 순서는 미확인).

## 조사 과정

### 1. `SPRING_PROFILES_ACTIVE=local` 환경변수로 재시도

동일하게 실패. Gradle 데몬이 이전 무프로필 실행 때의 프로세스 env를 그대로 쓰고 있을 가능성을 의심.

### 2. Gradle 시스템 프로퍼티(`-Dspring-boot.run.profiles=local`)로 재시도

데몬 env 캐싱을 우회하는 방법이지만 동일하게 실패 — 데몬 캐싱 가설 기각.

### 3. Spring Boot 커맨드라인 인자(`--args="--spring.profiles.active=local"`)로 재시도

커맨드라인 인자는 최우선 프로퍼티 소스라 확실히 반영될 것으로 예상했으나 동일하게 실패 — 프로필 자체는 반영됐을 가능성이 높고, config-import 단계가 프로필 문서 병합보다 먼저 실행되는 구조적 문제로 판단.

## 해결 (우회)

bootRun으로 실제 앱을 띄우는 대신, `.claude/rules/flyway-migration.md`가 이미 제시하는 방법대로 로컬 Postgres 컨테이너에 스키마를 만들고 `psql -f`로 V1~V5를 순서대로 직접 적용해 검증했다.

```bash
docker exec payment-db-dev psql -U anjinpyo -d prompthub_payment_dev -c "CREATE SCHEMA IF NOT EXISTS payment_service;"
# V1 → V5 순서대로 psql -f 로 적용
docker exec payment-db-dev psql -U anjinpyo -d prompthub_payment_dev -c "\d payment_service.payment"
```

## 검증

`\d payment_service.payment` 결과 `refunded_at` 컬럼이 사라졌고, `payment_status_check` 제약이 `READY/REQUESTED/PAID/FAILED/UNKNOWN` 5개 값으로 정상 축소된 것을 확인했다.

## 교훈 / 재발 방지

- 로컬에서 `bootRun`으로 실제 기동 검증이 필요하면, Config Server를 먼저 띄우거나(`docker-compose`에 별도 서비스가 없다면 스킵) `CONFIG_IMPORT=` 환경변수를 빈 값으로 명시적으로 오버라이드해 config-import 자체를 끄는 방법을 먼저 시도한다.
- 마이그레이션 SQL만 빠르게 검증하고 싶을 때는 `bootRun` 대신 `flyway-migration.md`에 이미 문서화된 `psql -f` 방식이 더 빠르고 이 문제에서 자유롭다 — 이번처럼 Config Server 이슈와 무관하게 마이그레이션 자체를 검증할 수 있다.
- 이 문제는 이번 브랜치(PaymentStatus 리팩터링)와 무관한 기존 로컬 환경 이슈로 보인다. 근본 원인(Boot의 config-data 처리 순서)은 확정하지 못했으니, 재발 시 `--debug` 옵션으로 `ConfigDataEnvironment` 처리 순서를 추적하는 것부터 시작할 것.
