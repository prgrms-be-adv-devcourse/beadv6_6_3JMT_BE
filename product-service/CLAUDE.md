# Product Service CLAUDE.md

## 목적

이 문서는 `product-service`에서 작업할 때 따라야 하는 기준을 정리한다.
작업을 시작하기 전에 이 파일을 먼저 읽고, `.claude/rules/` 아래 규칙 문서를 함께 확인한다.
이 문서는 Product Service 전용이다. 다른 서비스나 모듈 작업은 해당 서비스의 기준 문서를 먼저 확인한다.

## 작업 범위

- Product Service 관련 변경은 기본적으로 `product-service/` 하위에서 진행한다.
- 다른 서비스 모듈은 읽지 않는다(코드·룰·CLAUDE.md 포함, 루트 `CLAUDE.md` 전역 규칙). 쓰기
  작업(생성·수정·삭제)도 당연히 하지 않는다.
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

- `docs/swagger-rules.md` — Product Service Swagger 문서화·테스트 규칙
- `.claude/rules/product-api.md` — API 계약, category/ID 규칙, 응답 wrapper 규칙
- `.claude/rules/testing.md` — 테스트 기준
- `.claude/rules/git-workflow.md` — 브랜치 타입, Issue 우선 원칙

## Skills

작업 단계마다 아래 skill을 순서대로 쓴다.

> 공용 스킬(create-github-issue, create-branch, verify-rules, commit, create-github-pr)은 저장소 루트
> `.claude/skills/`로 통합됐다. product 전용 스킬(write-tests, sync-product-docs, save-plan-docs)만
> `product-service/.claude/skills/`에 남는다. 스킬은 이름으로 호출되므로 경로와 무관하게 동작한다.

1. 루트 `.claude/skills/create-github-issue/` — 이슈 생성 (공용)
2. 루트 `.claude/skills/create-branch/` — 이슈 기반 브랜치 생성 (공용)
3. (구현)
4. `.claude/skills/write-tests/` — 테스트 작성 (product 전용)
5. `.claude/skills/sync-product-docs/` — product 관련 docs 동기화 (product 전용)
6. 루트 `.claude/skills/verify-rules/` — 규칙 준수 확인 (공용, 대상 서비스 룰 자동 탐색)
7. 루트 `.claude/skills/commit/` — 커밋 (사전 게이트 포함, 공용)
8. 루트 `.claude/skills/create-github-pr/` — PR 생성 (공용)

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
## Package structure rules (required for every implementation)

- Keep the top-level layers `application`, `domain`, `infra`, `presentation`, `exception`, and `config`.
- Organize classes by business capability, not by technical type. Use the existing capability packages such as
  `seller`, `query`, `inspection`, `review`, `purchase`, `integration`, and `fileupload`.
- Keep read-only flows in `application/service/query`. Do not create a new package for a single class.
- Keep seller version changes in `seller`, inspection requests/results/retries in `inspection`, reviews in `review`,
  and purchased-product reads in `purchase`.
- Keep external technology implementations under `infra`, split by concern (`batch`, `messaging`, `grpc`, and
  `persistence`). Repository adapters belong under `infra/persistence`.
- Keep HTTP controllers and request/response DTOs under `presentation`; domain rules must not live in controllers.
- Keep application services as readable sequential orchestration. Depend on application ports, not concrete Kafka,
  S3, gRPC, or persistence implementations.
- Do not add generic `util`, `manager`, or `helper` packages. Use `common` only for genuinely shared code.
- Add each new class to the nearest existing capability package. Package moves must be separate from behavior changes
  when possible, and `:product-service:test` must run after a move.
- Before finishing any product-service implementation, check package placement, dependency direction, and whether a
  new package is actually necessary.
- For the search module, keep `application/query`, `application/indexing`, and `application/embedding` separate.
  Keep Elasticsearch adapters under `infra/es/config`, `infra/es/query`, or `infra/es/indexing`; do not return to a
  flat `search/application` or `search/infra/es` package.
- Name the search application ports `ProductSearchQueryPort` and `ProductSearchIndexPort`. Prefer concrete action
  names such as `ProductSearchEventProcessor` over vague `Handler` names.
