# Product Service CLAUDE.md

## 목적

이 문서는 `product-service`에서 작업할 때 따라야 하는 기준을 정리한다.
작업을 시작하기 전에 이 파일을 먼저 읽고, `.claude/rules/` 아래 규칙 문서를 함께 확인한다.
이 문서는 Product Service 전용이다. 다른 서비스나 모듈 작업은 해당 서비스의 기준 문서를 먼저 확인한다.

## 작업 범위

- Product Service 관련 변경은 기본적으로 `product-service/` 하위에서 진행한다.
- 다른 서비스 모듈은 참고용으로만 읽는다. 쓰기 작업(생성·수정·삭제)은 하지 않는다.
  자세한 모듈 경계 규칙은 `.claude/rules/architecture.md`를 따른다.
- `common-module/`, 루트 workflow, 공통 docs 변경이 필요한 경우 PR에 변경 이유를 명시하고,
  진행 전 사용자에게 먼저 알린다.
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

- `.claude/rules/architecture.md` — 계층 책임, 의존 방향, 예외 처리, 모듈 경계
- `.claude/rules/product-api.md` — API 계약, category/ID 규칙, 응답 wrapper 규칙
- `.claude/rules/testing.md` — 테스트 기준
- `.claude/rules/git-workflow.md` — 브랜치 타입, Issue 우선 원칙

## Skills

작업 단계마다 아래 skill을 순서대로 쓴다.

1. `.claude/skills/create-github-issue/SKILL.md` — 이슈 생성
2. `.claude/skills/create-branch/SKILL.md` — 이슈 기반 브랜치 생성
3. (구현)
4. `.claude/skills/write-tests/SKILL.md` — 테스트 작성
5. `.claude/skills/sync-product-docs/SKILL.md` — product 관련 docs 동기화
6. `.claude/skills/verify-rules/SKILL.md` — 규칙 준수 확인
7. `.claude/skills/commit/SKILL.md` — 커밋 (사전 게이트 포함)
8. `.claude/skills/create-github-pr/SKILL.md` — PR 생성 (agents/rule-checker 게이트 포함)

## 작업 시작 체크리스트

1. 이슈가 있는지 확인, 없으면 생성 (`create-github-issue`)
2. 이슈 번호 기준 브랜치 생성 (`create-branch`)
3. 위 규칙 문서 확인
4. 구현
5. 테스트 작성 (`write-tests`)
6. docs 동기화 (`sync-product-docs`)
7. 규칙 검증 (`verify-rules`)
8. 커밋 (`commit`)
9. PR 생성 (`create-github-pr`)

## 우선 작업 범위

로그인 없이 테스트 가능한 Product 공개 조회 API를 먼저 구현한다.

- `GET /api/v1/products`
- `GET /api/v1/products/{productId}`
- `GET /api/v1/products/{productId}/related`
- `GET /api/v1/products/{productId}/reviews`

판매자/관리자 쓰기 API는 Gateway/Auth 흐름이 확정된 뒤 별도 이슈에서 처리한다.
