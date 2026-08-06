# ADR-0004: 설정은 Config Server의 classpath `configs/`(native)로 중앙화한다 — 프로파일별 파일 분리는 아직 보류

- 상태: accepted (2026-07-06 개정 — 별도 설정 리포안 폐기 · 2026-07-07 1차 개정 — prod 환경
  제거, 로컬 compose 폐지 · 2026-07-07 2차 개정 — 팀 결정으로 git 백엔드·`cloud-config/`·
  라벨 전략 폐기, native(classpath) 서빙 채택 · **2026-07-09 3차 개정 — 팀 결정으로
  프로파일별 파일 분리(`{service}-dev.yml` 등)는 보류하고 기본형태(`{service}.yml`) 단일
  파일을 유지한다. 사유: (1) 현재 dev 외 다른 배포 환경이 없어 지금 시점에는 분리 대상
  자체가 없음, (2) 단순화·이행 비용 절감. §"3차 개정" 참조.**)
- 날짜: 2026-07-06 (최종 개정 2026-07-09)
- 관련: `config/src/main/resources/configs/`, 각 서비스 `application*.yml`,
  `docker-compose.yml`, `.github/workflows/`, `docs/records/plan/infra/adr-config-management.md`,
  `docs/records/plan/infra/adr-0005-develop-deploy-main-freeze(v).md`

> ⚠ **이 문서 본문(§결정, §결과)의 프로파일 2단(local/dev)·파일명 규칙(`{service}-dev.yml`)
> 서술은 2026-07-09 3차 개정으로 보류됐다.** 실제 구현 상태는 `configs/`에 프로파일 접미사
> 없는 기본형태 파일(`application.yml`, `user-service.yml` 등) 하나씩만 있고, 어떤 profile로
> 요청해도 이 파일이 그대로 서빙된다. 아래 본문은 2차 개정 시점의 설계 의도를 남겨두기 위해
> 그대로 두되, 실제로 채택된 내용은 맨 아래 "## 3차 개정" 절을 최종 근거로 본다.

## 컨텍스트

Config Server는 native 프로파일로 classpath의 `configs/`를 서빙하고 있었으나, 그 안에는
Eureka 공통 설정뿐이었다. 6개 서비스 전부 자기 `application.yml`에 DB·Kafka·gRPC·JWT
설정을 들고 있었고, config server import는 `optional:`이라 없어도 기동됐다 — 사실상 장식이었다.
설정의 실제 출처도 3곳으로 흩어져 있었다: 서비스 yml, 서비스별 `.env` 파일(payment·order·product는
`spring.config.import`로, user는 Gradle bootRun/test 태스크로 로딩), 그리고 docker-compose의
environment 블록(스프링 설정 전반을 env로 직접 주입).

제약과 환경:

- **팀 지시사항: 별도 리포를 만들 수 없다.** 모든 산출물은 이 리포
  (GitHub 원격: `github.com/prgrms-be-adv-devcourse/beadv6_6_3JMT_BE`) 안에서 동작해야 한다.
- **운영(prod) 서버는 없다.** 물리 환경은 개인 PC와 **AWS 개발서버**(단일 EC2 + docker
  compose) 둘뿐이다. CD는 **develop 푸시 → EC2 self-hosted 러너가 변경 모듈만 빌드·교체**,
  main은 완성 스냅샷 보관 브랜치로 동결한다 (ADR-0005).
- **로컬 docker compose 통합 환경은 운영하지 않는다** (ADR-0005의 검증 리스크 수용).

검토한 대안:

1. **Git 백엔드 + 별도 설정 리포** — 설정 변경 경계가 가장 깨끗하지만, 팀 지시사항(단일 리포)에
   위배되어 기각.
2. **Git 백엔드 + 리포 루트의 `cloud-config/` 디렉토리** (`search-paths` + 브랜치 라벨) —
   한때 채택했으나 **2차 개정에서 팀 결정으로 기각**. 장점(설정 변경을 config server
   재빌드 없이 반영, 서빙 내용 = 브랜치 HEAD 1:1 추적, 라벨로 머지 전 서빙 검증)보다
   비용(GitHub 런타임 의존, GIT_TOKEN 발급·관리 — org 관리자 필요, clone 실패라는 새 장애
   지점, 라벨 운영 개념 학습)이 팀 규모에 과하다고 판단했다. 설정 변경 빈도가 낮다는
   전제가 깔려 있다 — 전제가 깨지면 재검토(아래 결과 참조).
3. **Native 유지(classpath `configs/`)** — **채택.** 설정 한 줄 변경에 config server
   재빌드·재배포가 필요하다는 비용을 수용한다. 대신 git 런타임 의존·토큰 관리가 없고,
   현재 이미 동작 중인 구조의 연장이라 이행 비용이 최소다.
4. **Native + 볼륨 마운트**(`file:` search-location) — 재빌드 없이 반영되지만, 서빙 내용이
   "EC2 파일시스템 상태"가 되어 git 커밋과의 추적성을 잃고 CD가 모르는 드리프트를
   허용하게 되어 기각.

## 결정

**옵션 3을 채택한다.** Config Server는 native 프로파일로 자기 모듈의
`config/src/main/resources/configs/`를 서빙한다. 6개 서비스의 환경별 설정을 이 디렉토리로
모으고, **git 백엔드·브랜치 라벨은 사용하지 않는다.** 세부 결정:

- **설정은 빌드 산출물이다 — "커밋 ≠ 반영"**: `configs/` 파일은 config server 이미지에
  구워진다. 설정 변경의 반영 경로는 **PR → develop 머지 → CD가 config 모듈 재빌드·재배포 →
  값을 쓰는 서비스 재시작**이다. 로컬에서 파일을 고쳐도, 커밋·push해도, CD 배포 전에는
  개발서버에 반영되지 않는다. 반영 시점을 가르는 것은 브랜치 라벨이 아니라 **CD 배포**다.
- **프로파일 2단 + test**: `local`(개인 PC, config server 없이 개별 기동) /
  `dev`(**AWS 개발서버** compose 전용). 로컬 compose 통합 환경은 폐지됐다. `test`는 빌드
  테스트 전용으로 config·eureka를 끄고 서비스 모듈 yml만 상속한다. **prod 프로파일과
  `-prod.yml` 파일들은 만들지 않는다** (기존 `application-prod.yml`은 삭제 대상).
- **파일명 규칙**: `configs/application.yml`(전 서비스 공통), `configs/application-dev.yml`
  (공통 dev 플래그), `configs/{service}-dev.yml`(서비스별 dev 값). native도 git 백엔드와
  동일한 `{application}-{profile}` 해석 규칙을 쓰며, **파일명 오타는 에러 없이 무시**되므로
  CI 화이트리스트 검사가 방어선이다.
- **서비스 `application.yml` 축소 기준은 "환경 가변성"이다**: name·config.import·default
  profile에 더해, **모든 환경에서 같고 비밀이 아닌 값(JWT 만료시간, actuator 노출 등)은
  서비스 `application.yml`에 남긴다.** 환경별로 갈라지는 값만 `configs/`로 간다.
  **HTTP 포트는 환경별로 다르다**(예: user local 8081 ↔ 개발서버 18081) — local 포트는
  `application-local.yml`, 개발서버 포트는 `configs/{service}-dev.yml`에 고정값으로.
  단 **gRPC 서버 포트는 환경 불변이다**(내부 네트워크 전용·호스트 미노출 — 예: user 9081)
  — `application.yml`에 남긴다. "3줄만 남기기"는 불변 값 복제로 드리프트를 재생산하므로 기각.
- **공통 설정은 최소주의**: `configs/application.yml`에는 Eureka 클라이언트·actuator
  노출처럼 "모든 서비스가 반드시 동일해야 하고 달라질 이유가 없는 것"만 둔다.
  Kafka 직렬화·JPA·DB는 서비스별 파일로 — 서비스마다 의도적으로 다른 설계가 있기 때문이다.
- **`configs/`는 config 모듈 안에 있지만 전원 공유 영역이다**: 각 서비스 담당자가 자기
  `{service}-dev.yml`을 직접 수정한다. "다른 모듈을 수정하지 않는다"는 팀 관례의 **명시적
  예외**로 규정한다(`configs/` 한정, config server 코드는 여전히 config 담당자 소관).
  이 소유권 꼬임은 native 채택으로 수용한 트레이드오프다.
- **local 기본값은 서비스 모듈에 남는다**(`application-local.yml`). local이 config server
  없이 돌아야 하므로 논리적 귀결이다.
- **강제 수단**: 서비스 yml은 `spring.config.import: ${CONFIG_IMPORT:optional:configserver:http://localhost:8888}`.
  개발서버 compose가 `CONFIG_IMPORT=configserver:http://config:8888`(non-optional)과
  `SPRING_PROFILES_ACTIVE=dev`를 주입해 dev에서는 config server 없이 기동이 실패하게 한다.
  클라이언트에 `fail-fast: true` + `spring-retry`로 기동 순서 경합을 방어한다(이 필요성은
  백엔드와 무관하게 유지).
- **검증은 2층 + 머지 전 로컬 확인 + 배포 후 확인**: ① CI에 `configs/` 검사 job —
  yamllint(중복 키) + 파일명 화이트리스트. ② test 프로파일 격리. ③ **native의 이점: 머지 전
  검증에 git이 필요 없다** — 로컬 체크아웃에서 config server를 `bootRun`으로 띄우면 로컬
  classpath의 `configs/`를 그대로 서빙하므로, `curl localhost:8888/{service}/dev`로 커밋
  없이 병합 결과를 확인할 수 있다(git 백엔드에서는 커밋+push+라벨이 필요했다).
  ④ 통합 기동 오류는 develop 머지 → 개발서버 배포 후 health 확인으로 발견하고, 깨지면
  revert PR로 복구한다(ADR-0005).
- **CD 감지 규칙**: `configs/`는 config 모듈 안이므로 변경 시 CD가 config server를
  재빌드·재배포하는 것은 현행 모듈 감지로 자동이다. 그러나 **값을 쓰는 서비스는 재시작되지
  않는다** — 감지 규칙 추가: `configs/{service}-dev.yml` 변경 → config 재빌드·재기동 후
  해당 서비스 재시작, `configs/application*.yml` 변경 → 전체 서비스 재시작.
- **서비스 간 gRPC 주소는 정적 주소로 두고 `configs/`로 중앙화한다**: 단일 EC2 compose에서는
  컨테이너 이름이 안정적 DNS 이름이라 Eureka client-side discovery는 기각(다중화 시 재검토).
  클라이언트 주소는 시크릿이 아니므로 리터럴로 — local은 `application-local.yml`
  (`localhost:9082`), dev는 `configs/{service}-dev.yml`(`product-service:9082`).
  설정 키 4종의 통일은 spring-grpc 권고로만 남긴다.
- **시크릿은 플레이스홀더 + 환경별 단일 통로**: 설정 파일에는 `${PLACEHOLDER}`만 둔다
  (각 서비스 프로세스에서 해석 — 리포·이미지·config server 응답 어디에도 실제 비밀값이
  없다. **native에서는 configs/가 이미지에 포함되므로 이 원칙이 더 중요하다** — 비밀값을
  커밋하면 이미지에도 박제된다). 루트 `.env`는 local 전용(Gradle 공통 스크립트 주입),
  개발서버는 EC2의 `${DEPLOY_DIR}/.env`(CD 관리). 서비스별 `.env` 패턴은 전부 폐기.
- **Gateway 라우트는 Gateway 모듈에 유지한다.** 라우트와 인증 화이트리스트는 한 PR·한 CI로
  움직여야 한다.
- **Discovery는 config client를 붙이지 않는다.**
- **동적 refresh(`/actuator/refresh`)는 도입하지 않는다.** 재시작 기반으로 시작한다.

## 결과

- 설정 변경 흐름: "**`configs/` PR → develop 머지 → CD가 config 재빌드·재배포 + 매핑된
  서비스 재시작**". 설정 한 줄에도 config 모듈 재빌드가 발생하는 것은 수용한 비용이다.
- **GIT_TOKEN·deploy key가 필요 없어졌다** — org 관리자 의존 항목이 사라진다. GitHub
  장애·clone 실패라는 장애 시나리오도 소멸한다(retry는 기동 순서 경합용으로만 유지).
- 개발서버가 서빙하는 설정 = "마지막으로 배포된 config 이미지의 configs/"다. 어느 커밋인지는
  CD 실행 이력으로 역추적한다 — git 백엔드의 브랜치 HEAD 1:1 추적성보다 약하다(수용).
- docker-compose의 environment에는 프로파일(`SPRING_PROFILES_ACTIVE=dev`) +
  `CONFIG_IMPORT` + 시크릿만 남는다(env 다이어트 — 변경 없음).
- 6개 서비스 담당자가 `configs/`(config 모듈 내부)를 직접 수정하게 된다 — 모듈 경계 관례의
  예외 규정과 팀 합의 필요. 이전은 서비스별 PR로 쪼개 각 담당자 리뷰를 받는다.
- 일상 개발 루프(local)는 config server와 무관하게 돈다. 개발서버에서의 설정 값 실험은
  **env 오버라이드 → 확정 시 커밋**의 2단계 규칙 유지(`docs/records/plan/infra/adr-config-management.md` §5).
- 재검토 트리거: **설정 변경 빈도가 높아져 config 재빌드·재배포 비용이 실제 마찰이 되면
  옵션 2(git 백엔드) 또는 옵션 4(볼륨 마운트)를 재검토** — 옵션 2의 상세 설계(uri·
  search-paths·라벨 매핑·검증 절차)는 본 ADR의 개정 전 이력에 남아 있다. 설정 변경 반영이
  재시작 비용 문제가 되면 refresh/Bus 검토, 인스턴스 다중화 시 시크릿 SSM 승격 검토,
  운영(prod) 환경이 생기면 `-prod.yml` 파일 쌍 복원.

## 3차 개정 (2026-07-09): 프로파일별 파일 분리 보류

**변경**: §결정의 "프로파일 2단 + test"와 "파일명 규칙"(`configs/application-dev.yml`,
`configs/{service}-dev.yml` 등 `-dev` 접미사 파일 체계)은 실제로 구현하지 않는다. 대신
`configs/`는 서비스당 프로파일 접미사 없는 기본형태 파일 하나만 둔다
(`application.yml`, `user-service.yml`, `product-service.yml`, `order-service.yml`,
`payment-service.yml`, `settlement-service.yml`, `admin-service.yml` — `apigateway`는
전용 파일 없이 공통 `application.yml`만 받는다). Config Server(native 백엔드)는 `{application}.yml`을
요청 profile과 무관하게 항상 서빙하므로, profile별 오버레이 파일이 없는 지금은 어떤
profile로 요청해도 결과가 같다.

> **2026-07-22 갱신**: 위 목록 중 `admin-service.yml`은 이 개정 당시(2026-07-09)에는
> 없었으나 이후 실제로 추가됐다(작성 시점에는 admin-service가 아직 없었음). 전용 파일이
> 없는 쪽은 `apigateway`뿐이다.

**사유**:
1. **분리 대상이 아직 없다** — 현재 배포 환경은 AWS 개발서버(dev) 하나뿐이고 로컬(`local`)은
   config server 자체가 없어도 기동되므로(§`optional:` import), "환경별로 값이 갈리는" 실제
   케이스가 지금 시점엔 없다. 두 번째 배포 환경이 생기기 전에 분리 규칙을 먼저 만드는 것은
   과설계다.
2. **단순화·이행 비용 절감** — 파일명 화이트리스트 CI, `{service}-dev.yml` 개명 마이그레이션,
   `application-local.yml` 오버레이 작성 등 §결정에 나열된 부대 작업 없이 지금 당장 동작하는
   가장 단순한 형태로 시작한다.

**결과**:
- §결정·§결과의 profile 관련 서술(2단 분리, `-dev.yml` 파일명 규칙, `application-local.yml`
  필수화)은 **미채택**으로 취소한다. 나머지 결정(native/classpath 채택, git 백엔드 폐기,
  단일 리포 유지, Gateway 라우트는 Gateway 모듈 유지, Discovery는 config client 미부착,
  동적 refresh 미도입)은 그대로 유효하다.
- `docker-compose.yml`에는 실제로 `SPRING_PROFILES_ACTIVE`가 어디에도 설정되어 있지 않다 —
  이 역시 §결정의 서술과 다른 실제 상태다(2026-07-22 재확인, 변동 없음).
- 마찬가지로 §결정의 "`CONFIG_IMPORT=configserver:http://config:8888`(non-optional)"도
  실제와 다르다 — compose는 여전히 `optional:configserver:http://config:8888`을 쓴다
  (2026-07-22 재확인). fail-fast 강제는 미구현 상태.
- **재검토 트리거**: 두 번째 배포 환경(예: 실제 운영 서버, 또는 dev와 값이 달라야 하는
  스테이징)이 생기면 이 개정을 다시 열어 profile별 파일 분리를 재검토한다.

## 현황 업데이트 (2026-07-22): 배포 인프라 전제 변화

이 문서(및 config-management.md)는 "물리 환경은 개인 PC와 AWS 개발서버(단일 EC2 +
docker compose) 둘뿐"이라는 전제 위에 있다. 그런데 `.github/workflows/cd-selfhosted-kubernetes.yml`이
이미 `push: [develop]`로 활성화되어 있고, 기존 `cd-selfhosted-compose.yml`의 develop push
트리거는 주석 처리되어 수동(`workflow_dispatch`)으로만 남아있다 — Kubernetes 전환이
이미 시작된 상태다. 이 전환 자체를 다루는 ADR은 아직 없다(`docs/architecture/kubernetes.md`에
설계는 있음). Compose가 완전히 폐기된 것은 아니라 이 문서의 결정을 무효화하지는 않지만,
"단일 EC2 + compose"를 유일한 배포 대상으로 전제하는 서술은 곧 재검토가 필요하다.
