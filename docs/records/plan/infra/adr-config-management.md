# 설정 관리 (Config Management)

> 설계 결정의 배경과 트레이드오프는 `docs/records/plan/infra/adr-0004-centralized-config.md`(설정
> 중앙화 — native 채택)와 `docs/records/plan/infra/adr-0005-develop-deploy-main-freeze(v).md`(develop 직배포·main
> 동결) 참조. 이 문서는 **확정된 규칙과 마이그레이션 절차**를 담는다.
>
> ⚠ 2026-07-07 팀 결정으로 **git 백엔드·`cloud-config/`·브랜치 라벨 설계는 폐기**됐다.
> 설정은 config 모듈의 classpath `configs/`(native)로 서빙한다.
>
> ⚠ **2026-07-09 ADR-0004 3차 개정으로 이 문서의 프로파일 2단(`local`/`dev`) 분리와
> `{service}-dev.yml` 파일명 규칙은 보류됐다.** 사유: 아직 dev 외 다른 배포 환경이 없어
> 지금 분리할 대상이 없고, 단순화·이행 비용 절감을 우선했다. **실제 현재 상태**:
> `configs/`에는 프로파일 접미사 없는 기본형태 파일(`application.yml`, `user-service.yml`
> 등) 하나씩만 있고, 어떤 profile로 요청해도 이 파일이 그대로 서빙된다. 아래 §2~§3의
> `-dev.yml`·`application-local.yml` 관련 서술은 **당시 설계 의도의 기록**이며, 두 번째
> 배포 환경이 생기기 전까지는 적용되지 않는다. 마이그레이션 절차(§9)도 이 파일명 개명
> 단계는 보류 대상이다.
>
> ⚠ **2026-07-22 갱신 — 배포 인프라 전제 변화**: 이 문서 전체가 "물리 환경은 개인 PC와
> AWS 개발서버(단일 EC2 + docker compose) 둘뿐"을 전제한다. 그런데
> `.github/workflows/cd-selfhosted-kubernetes.yml`이 이미 `push: [develop]`로
> 활성화되어 있고, `cd-selfhosted-compose.yml`의 develop push 트리거는 주석 처리되어
> 수동 실행으로만 남아있다 — Kubernetes 전환이 이미 시작됐다. 이 전환을 다루는 ADR은
> 아직 없다. §8의 "CD 트리거: main→develop"·§7의 compose env 규칙은 compose가 배포
> 경로로 남아있는 동안은 유효하지만, 조만간 재검토가 필요하다.

---

## 0. 용어 — "설정이 반영됐다"의 의미

설정 파일은 **`config/src/main/resources/configs/`** 에 있고, config server 이미지에
**구워져서** 서빙된다. 그래서 같은 파일이 세 가지 상태로 존재한다:

| 상태 | 위치 | 의미 |
|---|---|---|
| **로컬 체크아웃** | 각자 clone 받은 폴더의 `config/src/main/resources/configs/` | 편집하는 곳. 로컬에서 config server를 띄우면 **이게 그대로 서빙된다** (머지 전 검증에 활용 — §5) |
| **GitHub 원격** | `github.com/prgrms-be-adv-devcourse/beadv6_6_3JMT_BE`의 develop 브랜치 | 원본·공유본. 단, 여기 있다고 개발서버에 반영된 것은 아니다 |
| **배포된 이미지** | 개발서버에서 실행 중인 config server 컨테이너의 classpath | **개발서버가 실제로 서빙하는 내용** — 마지막 CD 배포 시점의 스냅샷 |

> **커밋 ≠ 반영.** 개발서버의 설정은 "마지막으로 배포된 config 이미지"다. 파일을 고치고
> 커밋·push·머지해도, **CD가 config 모듈을 재빌드·재배포하고 값을 쓰는 서비스가 재시작될
> 때까지** 반영되지 않는다. (git 백엔드였다면 push만으로 서빙이 바뀌었지만, 그 대신
> GitHub 런타임 의존을 안아야 했다 — 트레이드오프는 ADR-0004 참조.)

**환경 명칭**: 배포 환경은 **AWS 개발서버**(단일 EC2 + docker compose) 하나다. 이전 명칭
"운영 서버"는 폐기됐다 — 운영(prod) 환경은 존재하지 않는다 (ADR-0005).

## 1. 한눈에 보기

| 항목 | 확정안 |
|---|---|
| 백엔드 | **native(classpath)** — config 모듈의 `src/main/resources/configs/` 서빙. git 백엔드·라벨 미사용 |
| 반영 시점 | develop 머지 → **CD가 config 재빌드·재배포 + 매핑된 서비스 재시작** (라벨 아님) |
| 프로파일 | **보류(3차 개정)** — `test`(빌드 테스트 전용)만 실제로 존재·동작. `local`/`dev` 2단 분리와 prod 미사용 방침은 설계 의도로 남아 있으나 파일로 구현되지 않았다 |
| 서비스 application.yml | name·import + 환경 불변·비밀 아닌 값. `default profile: local` 선언은 일부 서비스에 있지만 대응 오버레이 파일이 없어 현재는 효과 없음 |
| 공통 설정 | 최소주의 — Eureka 클라이언트, actuator 노출만 |
| local 기본값 | **보류** — `application-local.yml` 오버레이는 어느 서비스에도 없음 |
| dev(개발서버) 값 | **보류** — `configs/{service}-dev.yml`은 아직 만들지 않음. 현재는 `configs/{service}.yml`(프로파일 접미사 없음) 하나가 모든 profile 요청에 공통으로 서빙된다 |
| gRPC | 정적 주소(디스커버리 미사용), 서버 포트는 환경 불변, 클라이언트 주소는 리터럴 |
| 시크릿 | 플레이스홀더만 커밋(이미지에 박제 방지). 루트 `.env`(local, Gradle 주입) / EC2 `.env`(dev, CD 관리) |
| 검증 | CI(yamllint+파일명) / test 격리 / **머지 전 로컬 config server 기동 확인(git 불필요)** / 배포 후 health + revert PR |
| GIT_TOKEN | **불필요** (git 백엔드 폐기로 org 관리자 의존 소멸) |
| GW 라우트 | Gateway 모듈 유지 (인증 정책과 한 몸) |
| Discovery | config client 안 붙임 (자체 완결) |
| 동적 refresh | 미도입 (재시작 기반) |

## 2. 프로파일 정의 (⚠ 보류 — 아래 표는 설계 의도이며 실제로 구현되지 않았다. 맨 위 공지 참고)

| 프로파일 | 물리 환경 | config server | 설정 출처 | 호스트명 |
|---|---|---|---|---|
| `local` | 개인 PC, 단일 서비스 개발 | 없어도 기동 (optional) | 서비스 `application.yml` + `application-local.yml` | `localhost` |
| `dev` | **AWS 개발서버** (EC2 + docker compose, CD 배포) | **필수** (없으면 기동 실패) | `configs/{service}-dev.yml` (배포된 config 이미지) | 컨테이너 이름 (`postgres`, `kafka`, `discovery`, `config`) |
| `test` | Gradle 빌드 테스트 (CI 포함) | **안 붙음** (`spring.cloud.config.enabled: false`) | 서비스 `application.yml` + `src/test/resources/application-test.yml` | — |

- **로컬 docker compose 통합 환경은 폐지됐다.** 로컬에서는 local 프로파일 개별 기동만 한다.
  통합 확인은 develop 머지 → 개발서버 배포로 이뤄진다(§5).
- **local과 dev가 갈라지는 축**: 호스트명(localhost ↔ 컨테이너 이름), **HTTP 포트 체계**
  (예: user 8081 ↔ 18081 — gRPC 포트는 환경 불변, §3-1), `ddl-auto`(local: update /
  dev: validate), 로깅 레벨, `show-sql`, CORS origin, 외부 연동 테스트 모드(Toss), 시크릿 실제값.
- prod 프로파일·`-prod.yml` 파일은 만들지 않는다. 운영 환경이 생기면 파일 쌍을 복원한다.

## 3. 파일 배치 (⚠ 보류 — §3-1·§3-2는 설계 의도. 실제 파일 배치는 §3-2 끝의 "실제 현재 상태" 참고)

### 3-1. 각 서비스 모듈 (`src/main/resources/`)

```
application.yml          # name + import + default profile + 환경 불변 값
application-local.yml    # local 오버레이 (config/eureka off, localhost 값)
```

`application.yml`에 남기는 것 — **모든 환경에서 같고 비밀이 아닌 값**:

```yaml
spring:
  application:
    name: user-service
  config:
    import: ${CONFIG_IMPORT:optional:configserver:http://localhost:8888}
  profiles:
    default: local
  grpc:
    server:
      port: 9081          # gRPC 서버 포트 — 환경 불변 → 잔류

jwt:
  access-token-expire-seconds: 3600    # 환경 불변 (키 자체는 시크릿 → 플레이스홀더)

management:
  endpoints:
    web:
      exposure:
        include: health, info
```

> "3줄만 남기기"는 채택하지 않았다 — 불변 값을 local/test/dev 여러 곳에 복제시켜 드리프트를
> 재생산한다. `configs/` 파일이 서비스 yml보다 **우선**하므로(§3-3), 예외적으로 환경별로
> 갈라야 할 때는 dev 파일에서 덮어쓰면 된다.

`configs/{service}-dev.yml`로 보내는 것 — **개발서버에서만 쓰는 값**:

- **HTTP 서버 포트** — 환경별로 다르다(예: user local 8081 ↔ 개발서버 18081). local 포트는
  `application-local.yml`, 개발서버 포트는 `{service}-dev.yml`에 고정값으로 둔다.
  `application.yml`에는 두지 않는다.
- datasource(호스트·스키마), Kafka 주소, gRPC 클라이언트 주소 — 호스트는 컨테이너 이름
- `ddl-auto`, 로깅 레벨, `show-sql`, CORS origin, 외부 연동 모드

**gRPC 주소 규칙** (2026-07-07 확정):

- **주소 해석은 정적이다** — Eureka 등 디스커버리를 태우지 않는다. compose 내부 네트워크의
  컨테이너 이름이 안정적 DNS 이름이므로 단일 인스턴스 구조에서는 이득이 없다.
  인스턴스 다중화 시 재검토(ADR-0004).
- **gRPC 서버 포트는 환경 불변** — 내부 네트워크 전용·호스트 미노출이라 HTTP처럼 이원화할
  이유가 없다(user 9081, product 9082 유지). `application.yml`에 남긴다.
- **클라이언트 주소는 리터럴로** — 시크릿이 아니므로 env 플레이스홀더가 필요 없다.
  local은 `application-local.yml`에 `localhost:9082`, dev는 `{service}-dev.yml`에
  `product-service:9082`. compose의 gRPC 주소 env 주입(`PRODUCT_SERVICE_GRPC_ADDRESS`,
  `SELLER_GRPC_HOST` 등 목적지 하나를 서비스마다 다른 이름으로 부르던 변수들)은 이전과
  함께 전부 제거된다.
- **설정 키 통일은 권고** — 현재 4종이다(user: spring-grpc `spring.grpc.client.channel.{name}.target`
  / settlement: `grpc.client.{name}.address` / product: 자체 host·port 키 / order:
  `prompthub.grpc.*` + deadline). 값 이전은 각 서비스의 현행 키 그대로 하고, spring-grpc
  (공식 스타터)로의 통일은 담당자 판단에 맡긴다(§10).

동작 규칙:

- `CONFIG_IMPORT` 환경변수가 없으면(=local) optional import라 config server 없이 기동된다.
- 개발서버 compose는 `CONFIG_IMPORT=configserver:http://config:8888`(non-optional)을 주입한다.
  → dev에서 config server가 없으면 **기동이 실패한다** (조용히 로컬 값으로 뜨는 사고 방지).
- 테스트 설정(`src/test/resources/application-test.yml`)은 config·eureka를 끄는 오버레이로
  유지한다.
- **기존 `application-prod.yml`은 삭제한다** (prod 환경 없음 — §9 체크리스트).

`application-local.yml` 필수 항목:

```yaml
server:
  port: 8081        # 로컬 HTTP 포트 (개발서버 포트 18081은 configs/{service}-dev.yml 소관)
spring:
  cloud:
    config:
      enabled: false
eureka:
  client:
    enabled: false
# + localhost DB·gRPC 주소 등 이 서비스의 로컬 전용 값 (비밀값은 루트 .env에서 주입 — §4)
```

### 3-2. `config/src/main/resources/configs/` — 설정의 단일 저장소

```
config/src/main/resources/configs/          # 설계 의도 (보류) — 아래 "실제 현재 상태" 참고
├─ application.yml           # 공통: eureka client 설정, actuator 노출 (health, info)
├─ application-dev.yml       # 공통 dev: (필요 시) 개발서버 공통 플래그
├─ user-service-dev.yml      # 개발서버 값 — 컨테이너 호스트, HTTP 포트 18081, gRPC 주소 등
├─ apigateway-dev.yml
├─ order-service-dev.yml
├─ product-service-dev.yml
├─ payment-service-dev.yml
└─ settlement-service-dev.yml
```

**실제 현재 상태** (ADR-0004 3차 개정 — 위 구조는 아직 만들지 않았다. 2026-07-22 갱신):

```
config/src/main/resources/configs/
├─ application.yml           # 공통: eureka client 설정, actuator 노출
├─ user-service.yml          # 프로파일 접미사 없음 — 모든 profile 요청에 이 파일이 그대로 나감
├─ order-service.yml
├─ product-service.yml
├─ payment-service.yml
├─ settlement-service.yml
└─ admin-service.yml         # 작성 당시(2026-07-09) admin-service 미존재 — 이후 추가됨
                              # apigateway는 전용 파일 없음(공통 application.yml만 받음)
```

두 번째 배포 환경이 생기기 전까지는 이 기본형태를 유지한다.

- **`configs/`는 config 모듈 안에 있지만 전원 공유 영역이다.** 각 서비스 담당자가 자기
  `{service}-dev.yml`을 직접 수정한다 — "다른 모듈을 수정하지 않는다"는 팀 관례의
  **명시적 예외**다(`configs/` 디렉토리 한정. config server의 코드·설정은 여전히 config
  담당자 소관).
- **파일명 규칙이 계약이다**: config server는 `{spring.application.name}-{profile}.yml`
  규칙으로 파일을 찾고, **오타 난 파일명은 에러 없이 무시**한다. 허용 패턴은
  `application(-dev)?.yml` 또는 `{실존 서비스명}-dev.yml`뿐 — CI가 검사한다(§5).

**공통 `application.yml`에 넣어도 되는 것** — "모든 서비스가 반드시 동일해야 하고, 바뀔 일이
거의 없고, 달라도 될 이유가 없는 것"만:

- Eureka 클라이언트 (`service-url`, `instance-id`, `prefer-ip-address`)
- actuator 노출 (`health`, `info`)

**공통에 넣으면 안 되는 것** (서비스별 파일로):

- Kafka 직렬화 — 서비스마다 의도적으로 다르다 (order: Jackson+ErrorHandling, payment: Json,
  settlement: 코드 지정 원칙)
- JPA / datasource — ddl-auto·스키마가 서비스별로 다르다
- 도메인 설정 전부

> 공통 파일의 한 줄은 6개 서비스에 즉시 적용되고 각 서비스 yml보다 **우선**한다.
> "내 설정이 왜 안 먹지"의 대부분은 여기서 나온다. 추가는 보수적으로.

### 3-3. 우선순위 (디버깅용)

높은 것이 이긴다:

```
환경변수 (compose environment / EC2 .env)
  > configs/{service}-dev.yml        (config server가 서빙)
  > configs/application-dev.yml      (config server가 서빙)
  > configs/application.yml          (config server가 서빙, 공통)
  > 서비스 모듈의 application-{profile}.yml
  > 서비스 모듈의 application.yml
```

## 4. 시크릿 규칙

`configs/`에는 실제 비밀값을 **절대 커밋하지 않는다.** `${DB_PASSWORD}` 플레이스홀더만
둔다. 플레이스홀더는 config server가 아니라 **각 서비스 프로세스에서** 해석되므로, 리포에도
config server 응답에도 비밀값이 존재하지 않는다. **native에서는 이 원칙이 더 무겁다** —
`configs/`는 config server 이미지에 포함되므로, 비밀값을 커밋하면 리포와 이미지 양쪽에
박제된다.

시크릿 통로는 환경마다 정확히 1개다:

| 환경 | 주입 통로 | 비고 |
|---|---|---|
| `local` | **루트 `.env`** (gitignore, `.env.example`로 키 목록 공유) — 루트 Gradle 공통 스크립트가 bootRun/test 태스크에서 환경변수로 주입 | user-service의 기존 모듈 로컬 `.env` 로딩을 루트 참조로 일반화 |
| `dev` | **EC2의 `${DEPLOY_DIR}/.env`** (CD 관리) — 개발서버 compose가 읽음 | 다중화 시 SSM 승격 검토 |

**폐기하는 패턴**:

- 서비스별 `.env`를 `spring.config.import: optional:file:.env[.properties]`로 읽는 방식
  (payment·order·product). Spring 설정 소스에 끼어들어 컨테이너 환경까지 오염 가능.
- user-service의 **모듈 로컬** `.env` Gradle 로딩 — 메커니즘(Gradle 환경변수 주입)은 local
  공식 통로로 승격하되, 읽는 파일을 루트 `.env`로 통일하고 로직은 루트 build.gradle
  공통 스크립트로 이동.

## 5. 검증 — 설정 변경은 어떻게 테스트하나

머지 전 2층 + 로컬 서빙 확인, 머지 후 확인 1단계:

| 층 | 검증 대상 | 시점 |
|---|---|---|
| **CI (`configs/` 검사 job)** | YAML 문법·중복 키(yamllint), 파일명 규칙 | PR 순간 |
| **단위/슬라이스 테스트 (test 프로파일)** | 비즈니스 로직 — config server 의도적 격리 | 빌드마다 |
| **로컬 config server 기동 확인** | "config server가 이 파일들로 뭘 서빙하나" — 병합 결과 눈 확인 | **머지 전, 커밋 불필요** |
| **배포 후 health 확인** | "그 값으로 실제로 뜨고 도나" — Eureka 등록·배선·Kafka 연결 | develop 머지 → 개발서버 배포 직후 |

- **머지 전 로컬 확인 — native의 이점**: config server는 자기 classpath를 서빙하므로,
  로컬 체크아웃에서 `configs/` 파일을 고치고 config 모듈만 `bootRun`하면 **커밋·push 없이**
  `curl localhost:8888/{service}/dev`로 병합 결과(공통+서비스별 오버레이)를 확인할 수 있다.
  (git 백엔드였다면 커밋+push+라벨 오버라이드가 필요했다.) 단, 전체 스택 기동 검증은
  아니다 — 로컬 compose가 없으므로 "무엇을 서빙하는지"까지만.
- **파일명 규칙 검사가 필수인 이유**: `user-service-dev.yml`을 오타 내면 config server는
  **에러 없이 그 파일을 무시**하고, 서비스는 공통 설정만 받은 채 뜨거나 죽는다. CI만이
  자동 방어선. 허용 패턴: `application(-dev)?.yml` 또는 `{실존 서비스명}-dev.yml`.
  이 화이트리스트는 §8의 CD 재시작 매핑과 같은 규칙을 공유한다.
- **머지 전 통합 기동 검증 층은 없다** — 로컬 compose 폐지로 삭제됐다(ADR-0005의 수용
  리스크). 머지한 사람이 배포 후 각 서비스 `actuator/health`를 확인하고, 깨졌으면
  원인 수정보다 **revert PR을 먼저** 올린다(revert 머지 = 재배포 트리거).
- **설정 값 실험은 env로, 확정은 git으로 (2단계 규칙)**: 개발서버에서 값을 탐색하는 단계
  (Kafka 튜닝, 로깅 레벨, 타임아웃 등)에는 커밋 대신 **개발서버의 `${DEPLOY_DIR}/.env`
  (또는 compose override)에 한 줄 얹어 해당 서비스만 재기동**한다 — 환경변수는 우선순위
  최상위(§3-3)라 config 재빌드 없이 즉시 반영된다. 값이 확정되면 `configs/`에 커밋하고
  **서버의 실험 줄은 반드시 제거**한다. 서버 측 오버라이드는 CD가 추적하지 않는
  드리프트이므로, 눌러앉으면 "리포에 없는 설정으로 도는 서버"가 된다(§7 원칙 위반).
  native에서는 반영에 config 재빌드가 필요하므로 이 탈출구의 가치가 더 크다.

## 6. 인프라 컴포넌트 규칙

- **Config Server**: native 프로파일 유지, `spring.cloud.config.server.native.search-locations:
  classpath:/configs/`. **git 백엔드·라벨·GIT_TOKEN 미사용.** 포트 8888은 내부 네트워크
  한정(현 compose의 loopback 바인딩 유지). config server 자체의 설정(포트 등)은 자기
  모듈 소관.
- **클라이언트 공통**: `spring.cloud.config.fail-fast: true` + `spring-retry` — 기동 순서
  경합(config 컨테이너보다 서비스가 먼저 뜨는 레이스)을 재시도로 방어. 백엔드가 native여도
  이 필요성은 동일하다.
- **Discovery**: config client를 붙이지 않는다. 부트스트랩 인프라는 의존 최소화.
- **API Gateway**: 일반 서비스처럼 config client로 붙지만, **라우트 정의는 Gateway 모듈에
  유지한다.** 라우트와 인증 화이트리스트(`SecurityConfig`)는 같은 엔드포인트에 대한 한 쌍의
  결정이라 한 PR·한 CI로 움직여야 한다.
- **기동 순서 불변**: Config → Discovery → Gateway → 서비스.

## 7. docker-compose 변경 (env 다이어트)

compose는 **개발서버 전용 배포 산출물**이다(로컬 compose 환경 폐지 — §2). 현재 compose는
`SPRING_DATASOURCE_URL` 등 스프링 설정을 environment로 직접 주입한다 — 사실상 설정 중앙화의
경쟁자다. 이전 후 compose의 environment에는 다음만 남긴다:

```yaml
# 각 서비스 블록
environment:
  SPRING_PROFILES_ACTIVE: dev            # 필수 — 현행 compose는 미지정(default로 뜨는 중)
  CONFIG_IMPORT: configserver:http://config:8888
  DB_PASSWORD: ${POSTGRES_PASSWORD}      # 시크릿만 EC2 .env에서 전달
  JWT_PRIVATE_KEY: ${JWT_PRIVATE_KEY}
  # ... 기타 시크릿

# config 서비스 블록
environment: {}                          # native — GIT_TOKEN도 라벨도 불필요
```

DB 호스트, Kafka 주소, gRPC 주소, HTTP 포트 등 비밀 아닌 값은 전부
`configs/{service}-dev.yml`로 이동.

> 예외: **일시적 실험용 env 오버라이드**(§5의 2단계 규칙)는 서버 측 `.env`/override로 허용된다.
> 단, 값 확정 후 반드시 제거한다 — 남아 있으면 CD가 모르는 드리프트다.

## 8. CI/CD 변경

- **CI**: 기존 워크플로에 `config/src/main/resources/configs/` 검사 job 추가 —
  yamllint(`key-duplicates` 켬) + 파일명 화이트리스트(§5). required check로 지정.
- **CD 트리거: main → develop 교체** (ADR-0005). `cd-selfhosted-compose.yml`의
  `branches: [main]`을 `[develop]`으로 바꾼다. **main을 남겨두면 안 된다** — 최종
  develop→main 머지(v2.0.0 태그 시점)가 오배포를 일으킨다. `ci-main.yml`은 그 최종 머지
  PR 1회를 위해 잔류.
- **CD 감지 규칙 추가**: `configs/`는 config 모듈 안이라 변경 시 **config server
  재빌드·재배포는 현행 모듈 감지로 자동**이다. 그러나 값을 쓰는 서비스는 재시작되지 않는다
  — 규칙 추가:
  - `configs/{service}-dev.yml` 변경 → config 재빌드·재기동 **후** 해당 서비스 재시작
  - `configs/application*.yml` 변경 → config 재빌드·재기동 **후** 전체 서비스 재시작
  - (순서 주의: 서비스 재시작은 새 config server가 healthy가 된 다음이어야 한다 —
    현행 compose의 `depends_on: config: service_healthy`가 이 순서를 보장)
- **concurrency 권장**: 머지 = 배포가 되면서 연속 머지 시 self-hosted 러너에 배포가 큐잉된다.
  CD 워크플로에 `concurrency` 그룹(진행 중 배포 취소 또는 순차화)을 건다.

## 9. 마이그레이션 순서 (⚠ 프로파일 분리 관련 단계는 보류 — 아래 참고)

> **2026-07-09 3차 개정으로 보류된 부분**: 1단계의 `user-service.yml` → `user-service-dev.yml`
> 개명, `application-local.yml` 신설, `SPRING_PROFILES_ACTIVE: dev` 주입 등 `-dev.yml`/
> profile 분리와 직접 관련된 단계는 두 번째 배포 환경이 생기기 전까지 진행하지 않는다.
> 그 외 항목(시크릿 플레이스홀더화, 죽은 gRPC 설정 정리, `application-prod.yml` 삭제,
> compose env 다이어트 등 profile 분리와 무관한 정리 작업)은 이 문서 작성 시점 기준 아직
> 실행 여부가 확인되지 않았으므로 착수 전 현재 코드 상태를 다시 확인할 것.

각 단계는 별도 PR. 서비스 이전 PR은 **해당 서비스 담당자 리뷰 필수**.

0. **브랜치·태그 정리 (ADR-0005)** — main HEAD에 `v1.0.0` 태그, CD 트리거 main→develop
   교체, CD concurrency 설정. 이 단계가 가장 먼저다 — 이후 모든 PR이 develop 머지 즉시
   배포되기 때문이다.
1. **`configs/` 정비 + 클라이언트 강제 + CI** — 파일명 규칙 확정(현 `user-service.yml`은
   `user-service-dev.yml`로 프로파일 명시), 클라이언트 fail-fast+retry 도입, CI 검사 job
   추가. ~~deploy key/PAT 발급~~ — **불필요해짐**.
2. **user-service 파일럿** — §3-1 기준으로 yml 재편(불변 값 잔류 / local 오버레이 / 개발서버
   값은 `configs/user-service-dev.yml`로, HTTP 포트 18081 포함). **`application-prod.yml`
   삭제**(`spring:` 키 중복 버그도 파일 삭제로 함께 해소). gRPC 죽은 설정 정리(§9 체크리스트).
   루트 Gradle 공통 스크립트(루트 `.env` 주입) 도입.
3. **compose env 다이어트 + CD 감지 규칙** — user-service 블록부터 §7 형태로 축소
   (`SPRING_PROFILES_ACTIVE: dev` 추가 필수), §8의 CD 변경 반영, 개발서버 배포로 기동 검증.
4. **나머지 서비스 순차 이전** — 아래 체크리스트로 서비스별 PR.
5. **문서 동기화** — `docs/architecture/spring-cloud.md`, 루트·각 서비스 CLAUDE.md
   (특히 **`configs/` 수정을 모듈 경계 예외로 명시**), `user-service/.claude/rules/git-convention.md`의
   환경 정의("prod — 운영" 삭제), `config/CLAUDE.md`.

### 서비스별 이전 체크리스트

공통:

- [ ] `application.yml`을 §3-1 기준으로 재편 (환경 불변 값 잔류, 가변 값 제거)
- [ ] `application-local.yml` 정비 (config/eureka off + localhost 값 오버레이)
- [ ] `application-prod.yml`이 있으면 삭제, 개발서버 값은 `configs/{service}-dev.yml`로 이동
- [ ] gRPC 클라이언트 주소를 리터럴로 이전 — local yml에 `localhost:포트`, `-dev.yml`에
  `컨테이너이름:포트`. env 플레이스홀더·compose 주입 제거. gRPC 서버 포트는 `application.yml` 잔류 (§3-1 gRPC 규칙)
- [ ] 시크릿은 플레이스홀더화, 실제 값은 루트 `.env`(local) / EC2 `.env`(dev)로

서비스별 추가 항목 (현재 코드 기준 발견 사항):

| 서비스 | 정리할 것 |
|---|---|
| user | `application-prod.yml` 삭제 (`spring:` 키 중복 버그 포함 — 파일 삭제로 함께 해소) · 모듈 로컬 `.env` Gradle 로딩 → 루트 공통 스크립트로 전환 · **죽은 gRPC 설정 정리**: #197 spring-grpc 교체 후 `application.yml`의 최상위 `grpc.client.product-service.address` 블록(net.devh 네임스페이스)은 읽히지 않는다 — 삭제하고, `@ImportGrpcClients(target="product-service")`가 읽는 `spring.grpc.client.channel.product-service.target`을 local(-local.yml: `localhost:9082`)/dev(`configs/user-service-dev.yml`: `product-service:9082`)에 정의. compose의 `PRODUCT_SERVICE_GRPC_ADDRESS` 주입은 죽은 키 대상이라 제거. **배포 환경에서 user→product gRPC가 실제 붙는 주소 검증 필요** |
| order | `optional:file:.env` import 제거, Eureka 주소 하드코딩 제거 (config server가 조용히 덮어쓰는 중) |
| payment | `optional:file:.env` import 제거, Kafka `localhost:9092` 하드코딩 → 플레이스홀더 |
| product | `optional:file:.env` import 2건 제거 |
| settlement | DB 계정·비밀번호 평문 기본값(`promptHub`) 제거 |
| apigateway | 라우트는 유지, JWT 공개키 등 환경값만 `configs/`로 |
| config | 기존 `configs/user-service.yml` → `user-service-dev.yml`로 개명(프로파일 명시). native search-locations 명시 확인 |

## 10. 열린 항목 (사람 손 필요)

- 태그 네이밍 합의 — 이 문서는 `v1.0.0`/`v2.0.0`을 가정 (`api-v1`/`api-v2` 등 다른 안이면
  ADR-0005와 함께 갱신)
- **`configs/` = 모듈 경계 예외** 팀 합의 — 6명 전원이 config 모듈 내부(`configs/` 한정)를
  수정하게 되는 규칙 변화. 각 서비스 CLAUDE.md의 "다른 모듈 수정 금지"에 예외 명시 필요
- 팀원 4명 합의 — 이 문서를 근거로 공유. 특히 §4의 루트 `.env`(local 통로)와 §8의 CD 트리거
  교체는 전원의 워크플로 변화
- `configs/` 변경 PR의 리뷰 규칙 — 해당 서비스 담당자를 리뷰어로 지정하는 관례 합의
- api/v1 → api/v2 경로 전환 — Gateway 라우트·컨트롤러 `@RequestMapping`·`docs/api-spec/*`
  Base 경로가 함께 움직여야 한다. 설정 관리 범위 밖이라 별도 이슈로
- gRPC 설정 키 통일(spring-grpc 권고, §3-1) — 4종으로 갈라진 클라이언트 스택을 통일할지는
  각 서비스 담당자 판단. 강제 아님
