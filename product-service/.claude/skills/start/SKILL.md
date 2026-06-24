---
name: product-start
description: Product Service 작업을 시작할 때 issue 생성/확인, branch 생성, rules 확인까지 진행한다.
---

# Product Start Skill

Product Service 작업을 처음 시작하거나, 현재 작업 시작 흐름이 올바른지 점검할 때 이 절차를 따른다.

## 목적

아래 흐름을 일관되게 진행한다.

```text
issue 생성/확인
-> issue 번호 기준 branch 생성
-> product-service rules 확인
-> 작업 시작 준비 완료
```

## 시작 전 확인

사용자가 작업 범위를 명확히 주지 않으면 바로 작업하지 않고 먼저 확인한다.

- 작업 대상 서비스 또는 모듈
- 이슈를 새로 만들지, 기존 이슈를 사용할지
- 이슈 타입: `Feature`, `Bug`, `Task`
- 작업 범위
- 관련 API 또는 파일
- 완료 조건
- 테스트 기준

작업 대상이 `product-service`가 아니면 이 skill을 기준으로 바로 진행하지 않는다.
해당 서비스 또는 모듈의 기준 문서가 있는지 먼저 확인한다.

## 1. Issue 생성 또는 확인

새 이슈가 필요하면 `product-service/.claude/skills/issue/SKILL.md` 절차를 따른다.

기존 이슈가 있다면 번호와 제목을 확인한다.

```bash
gh issue view <issue-number>
```

Windows에서 `gh`가 PATH에 없으면 전체 경로를 사용한다.

```powershell
& "C:\Program Files\GitHub CLI\gh.exe" issue view <issue-number>
```

## 2. Branch 생성

브랜치는 최신 `develop`에서 생성한다.

```bash
git checkout develop
git pull origin develop
git checkout -b "<type>/#<issue-number>-<description>"
```

예시:

```bash
git checkout -b "docs/#45-product-claude-rules"
git checkout -b "feat/#46-product-public-query-api"
```

이미 작업 브랜치에 있다면 현재 branch가 issue 번호와 맞는지 확인한다.

```bash
git branch --show-current
```

## 3. Rules 확인

구현 또는 문서 변경 전에 아래 파일을 읽는다.

- `product-service/CLAUDE.md`
- `product-service/.claude/rules/architecture.md`
- `product-service/.claude/rules/product-api.md`
- `product-service/.claude/rules/testing.md`
- `product-service/.claude/rules/git-workflow.md`

Product API 구현 작업이면 추가로 확인한다.

- `docs/api-spec/product.md`
- `docs/erd/schema.md`
- `docs/domain-glossary/product.md`
- 프론트 프로젝트 `C:\programmers_prj\beadv6_6_3JMT_FE`의 관련 화면/요청 흐름

프론트 URL path, query param, response field, ID 타입, DDL 기준이 서로 다르면 임의로 결정하지 않고 사용자에게 확인한다.

## 4. 작업 중 원칙

- Controller에는 최소 호출만 둔다.
- 비즈니스 로직은 Controller에 넣지 않는다.
- 예외 처리는 `common-module`의 `BusinessException`, `ErrorCode`, `ErrorResponse` 구조를 따른다.
- Product 전용 예외/에러 코드는 필요한 경우에만 추가한다.
- Product 공개 조회 API는 인증 없이 동작해야 한다.
- 인증 필요 API는 JWT를 직접 파싱하지 않고 Gateway가 주입한 `X-User-Id`, `X-User-Role`을 사용한다.

## 5. 작업 시작 준비 완료

issue, branch, rules 확인이 끝나면 작업 시작 준비가 완료된 상태다.
이후 구현은 `architecture.md`, `product-api.md`, `git-workflow.md` 기준으로 진행한다.

구현 완료 후 PR 전 검증과 PR 생성은 `product-service/.claude/skills/pr/SKILL.md` 절차를 따른다.

## 참고: PR 전 local 검증

PR 전에는 root `.github/workflows/ci.yml`, `.github/workflows/reusable-build.yml`, `.github/workflows/cd.yml` 기준으로 CI/CD 흐름을 확인한다.

Product Service 작업은 항상 `product-service` build를 확인한다.
이 build는 `product-service/src/main` 컴파일, checkstyle, `product-service/src/test` 테스트를 포함한다.
따라서 `test`만 실행하는 것으로 대체하지 않는다.

```bash
cd product-service
./gradlew clean build --no-daemon
```

Windows:

```powershell
cd product-service
.\gradlew.bat clean build --no-daemon
```

CI와 유사하게 확인해야 하면 root `.github/workflows/reusable-build.yml`의 DB 환경변수를 맞춘다.

PowerShell 예시:

```powershell
cd C:\programmers_prj\beadv6_6_3JMT_BE\product-service

$env:DB_HOST="localhost"
$env:DB_PORT="5432"
$env:DB_NAME="prompthub_test"
$env:DB_USERNAME="test"
$env:DB_PASSWORD="test"

.\gradlew.bat clean build --no-daemon
```

로컬에 CI와 동일한 PostgreSQL DB/계정이 없으면 실패할 수 있다.
이 경우 실패 원인을 사용자에게 보고하고, CI에서 같은 문제가 날 가능성이 있는지 판단한다.

이 build에는 `product-service/src/test/**` 테스트도 포함되어야 한다.
테스트를 생략하거나 실패한 경우 PR 본문에 이유를 적는다.

여러 모듈을 변경했다면 변경한 모듈도 함께 확인한다.

예시:

```text
product-service 변경 -> product-service build 필수
common-module + product-service 변경 -> product-service build로 common-module 연동 확인, 필요 시 관련 모듈 추가 build
root workflow 변경 -> GitHub Actions 동작 영향 PR에 명시
```

추가 확인:

```bash
git status --short
git diff --stat
```

## 6. 완료 기준

start skill 완료 시 아래를 사용자에게 보고한다.

- issue 번호
- branch 이름
- 읽은 rules
- 다음 작업 단계
- 남은 확인 사항
