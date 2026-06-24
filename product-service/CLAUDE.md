# Product Service CLAUDE.md

## 목적

이 문서는 `product-service`에서 작업할 때 따라야 하는 기준을 정리한다.
작업을 시작하기 전에 이 파일을 먼저 읽고, `.claude/rules/` 아래 규칙 문서를 함께 확인한다.
이 문서는 Product Service 전용이다. 다른 서비스나 모듈 작업은 해당 서비스의 기준 문서를 먼저 확인한다.

## 작업 범위

- Product Service 관련 변경은 기본적으로 `product-service/` 하위에서 진행한다.
- `common-module/`, 루트 workflow, 공통 docs 변경이 필요한 경우 PR에 변경 이유를 명시한다.
- 하나의 브랜치에 관련 없는 API 작업을 섞지 않는다.

## 기준 문서

- API 명세: `docs/api-spec/product.md`
- ERD/schema 문서: `docs/erd/schema.md`
- Product 도메인 용어: `docs/domain-glossary/product.md`
- 에러 코드: `docs/error-codes.md`
- Checkstyle: `style/checkstyle/prompthub-checkstyle-rules.xml`
- Formatter: `style/checkstyle/prompthub-formatter.xml`

## 규칙 문서

구현 전에 아래 문서를 읽는다.

- `.claude/rules/architecture.md`
- `.claude/rules/product-api.md`
- `.claude/rules/testing.md`
- `.claude/rules/git-workflow.md`

## Skills

반복되는 workflow 작업은 아래 skill 문서를 따른다.

- `.claude/skills/issue/SKILL.md`: GitHub issue 생성 절차
- `.claude/skills/pr/SKILL.md`: PR 생성 전 확인 및 PR 작성 절차
- `.claude/skills/start/SKILL.md`: 작업 시작부터 issue/branch/rules 확인까지의 시작 절차
- `.claude/skills/test/SKILL.md`: 테스트 범위 판단, 테스트 추가/수정, build 검증 절차

`rules/git-workflow.md`는 브랜치, 커밋, CI/CD처럼 항상 지켜야 하는 Git/GitHub 규칙이다.
`skills/start/SKILL.md`는 작업을 처음 시작할 때 issue 확인, branch 생성, rules 확인 등을 실제 순서대로 진행하기 위한 절차다.

## 우선 작업 범위

로그인 없이 테스트 가능한 Product 공개 조회 API를 먼저 구현한다.

- `GET /api/v1/products`
- `GET /api/v1/products/{productId}`
- `GET /api/v1/products/{productId}/related`
- `GET /api/v1/products/{productId}/reviews`

판매자/관리자 쓰기 API는 Gateway/Auth 흐름이 확정된 뒤 별도 이슈에서 처리한다.
