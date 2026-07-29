# Git Workflow 규칙

이 문서는 Product Service 작업에서 항상 지켜야 하는 핵심 원칙만 담는다. 실행 절차(이슈를
어떻게 만들고 PR을 어떻게 올리는지)는 루트 `.claude/skills/`의 각 액션 skill을 따른다.

## Issue 우선

구현을 시작하기 전에 GitHub issue를 먼저 만들거나 확인한다. 절차는
루트 `.claude/skills/create-github-issue/SKILL.md`를 따른다. 이슈/브랜치 없이 `develop`·`main`에
바로 커밋하지 않는다 — 이 확인은 루트 `.claude/skills/commit/SKILL.md`의 필수 게이트다.

## Branch 타입

브랜치 명명 형식과 타입 목록은 루트 `.claude/rules/git-convention.md`를 따른다.
브랜치 생성 절차는 루트 `.claude/skills/create-branch/SKILL.md`를 따른다.

## PR

PR 템플릿, 체크리스트, 리뷰어 지정은 이 문서에 하드코딩하지 않는다. 실행 시점에 실제 루트
파일(`.github/PULL_REQUEST_TEMPLATE.md`, `.github/CODEOWNERS`)을 읽어서 따른다. 절차는
루트 `.claude/skills/create-github-pr/SKILL.md`를 따른다.

## GitHub Actions

CI는 `develop` 또는 `main` 대상 PR에서 실행된다. `product-service/**` 변경 시
`product-service-ci`가 실행된다. reusable build는 PostgreSQL 16을 띄우고
`./gradlew clean build --no-daemon`을 실행한다. CI가 실패하면 merge할 수 없다.

CD는 `main` push/merge 이후에만 실행된다.
