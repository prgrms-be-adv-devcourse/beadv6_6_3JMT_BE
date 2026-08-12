# Product Service — Codex 지침

- 저장소 루트 `AGENTS.md`를 먼저 적용한다.
- product-service의 Swagger 작업에는 `docs/swagger-rules.md`를 추가로 읽고 적용한다.
- 기존 공개 API와 사용자 변경을 보존하며, 규칙 적용만을 위한 얇은 추상화나 대규모 소급 변경을
  만들지 않는다.
## Product-service package structure rules

- Keep the top-level layers `application`, `domain`, `infra`, `presentation`, `exception`, and `config`.
- Group classes by business capability, not by technical type. Use `seller`, `query`, `inspection`, `review`,
  `purchase`, `integration`, and `fileupload` where applicable.
- Application services orchestrate a readable business flow. They depend on ports in `application/usecase` or
  `application/gateway`, never on concrete Kafka, S3, gRPC, or persistence implementations.
- Put read-only flows in `query`; do not create a package for every single class when they belong to one query flow.
- Keep seller version changes in `seller`, inspection requests/results/retries in `inspection`, reviews in `review`,
  and purchased-product reads in `purchase`.
- Keep external technology implementations under `infra` and split batch, messaging, S3, gRPC, and persistence by
  concern. Repository-port adapters belong under `infra/persistence`.
- `presentation` contains only HTTP controllers and request/response DTOs. Domain rules do not live in controllers.
- `domain` must not depend on presentation DTOs or external frameworks such as Kafka or S3. Use `common` only for
  genuinely shared code; do not add generic `util`, `manager`, or `helper` packages.
- Add new classes to the nearest capability package. Keep package moves separate from behavior changes and run
  `:product-service:test` after a move.
- Judge the structure by cohesion, dependency direction, and readability for a new developer, not by LOC alone.
- In `search/application`, keep query, indexing, and embedding flows in their matching capability packages.
- Name search application boundaries with `ProductSearchQueryPort` and `ProductSearchIndexPort`; reserve `Service`,
  `Handler`, and `Indexer` for concrete orchestration or infrastructure implementations whose action is explicit.
- In `search/infra`, group Elasticsearch code by `config`, `query`, and `indexing`; keep messaging, batch, and
  provider adapters in their existing concern packages.
