# Git Workflow 규칙

이 문서는 Product Service 작업에서 항상 지켜야 하는 workflow 규칙이다.
작업 시작을 위한 실제 실행 순서가 필요하면 `.claude/skills/start/SKILL.md`를 따른다.

## Issue 우선

구현을 시작하기 전에 GitHub issue를 먼저 만들거나 확인한다.

Product 공개 조회 API 이슈 제목 예시:

```text
[FEATURE] Product 공개 조회 API 구현
```

repository에 실제 존재하는 라벨만 사용한다. 기능 작업 라벨은 아래를 사용한다.

```text
feat
```

## Branch 이름

최신 `develop`에서 작업 브랜치를 생성한다.

```text
<type>/#<issue-number>-<description>
```

예시:

```text
feat/#44-product-public-query-api
docs/#45-product-claude-rules
fix/#46-product-ci-build
```

branch type은 issue type과 commit type에 맞춘다.

- 기능 추가: `feat`
- 버그 수정: `fix`
- 문서 작업: `docs`
- 테스트 추가/수정: `test`
- 설정/빌드/작업환경: `chore`
- 구조 개선: `refactor`
- 코드 포맷: `style`

## Commit 메시지

아래 형식을 사용한다.

```text
<type>: <summary>
```

허용 type:

- `feat`
- `fix`
- `refactor`
- `docs`
- `chore`
- `style`
- `test`

예시:

```text
feat: implement product public query APIs
docs: add product service Claude workflow rules
fix: configure product service CI datasource
```

summary는 리뷰어가 바로 이해할 수 있게 작성한다.
필요하면 영어 동사 뒤에 한국어 설명을 섞어도 된다.

예시:

```text
feat: implement product public query APIs 상품 공개 조회 API 구현
docs: add product service Claude workflow rules 작업 규칙 문서 추가
fix: configure product service CI datasource CI datasource 설정 수정
```

## PR 전 local check

push 또는 PR 생성 전에 변경한 모듈 기준으로 build를 확인한다.
Product 작업은 항상 `product-service` build를 확인한다.
`build`는 `product-service/src/main` 컴파일, checkstyle, `product-service/src/test` 테스트를 포함하므로 `test`만 따로 실행하는 것으로 대체하지 않는다.

```bash
cd product-service
./gradlew clean build --no-daemon
```

Windows:

```powershell
cd product-service
.\gradlew.bat clean build --no-daemon
```

CI와 유사하게 확인해야 하면 `.github/workflows/reusable-build.yml`의 DB 환경변수를 맞춘 뒤 실행한다.

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
그 경우 실패 원인을 PR 본문이나 작업 보고에 남긴다.

추가 확인:

```bash
git status --short
git diff --stat
```

여러 모듈을 함께 변경한 경우 변경한 각 모듈의 build/test도 확인한다.
예를 들어 `common-module`과 `product-service`를 함께 수정했다면 Product Service build에서 common-module 변경이 같이 검증되는지 확인하고, 필요하면 root 또는 관련 모듈 build를 추가로 실행한다.

## GitHub Actions

CI는 `develop` 또는 `main` 대상 PR에서 실행된다.

- `product-service/**` 변경 시 `product-service-ci`가 실행된다.
- reusable build는 PostgreSQL 16을 띄우고 `./gradlew clean build --no-daemon`을 실행한다.
- CI가 실패하면 merge할 수 없다.

CD는 `main` push/merge 이후에만 실행된다.

## PR

팀에서 다르게 정하지 않았다면 PR base는 `develop`으로 한다.

이슈를 닫아야 하는 PR에는 close keyword를 포함한다.

```text
Closed #<issue-number>
```

PR 본문에는 아래 내용을 포함한다.

- 변경 내용
- 테스트 계획과 결과
- 관련 이슈
- schema, 응답 필드, 프론트 호환성에 영향이 있으면 reviewer 참고사항
