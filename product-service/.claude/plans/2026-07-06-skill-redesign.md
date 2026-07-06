# Product Service Claude Skill/Rule 재설계

- 작성일: 2026-07-06
- 범위: `product-service/CLAUDE.md`, `product-service/.claude/` 전체
- 다른 서비스(order/payment/settlement/user 등)와 루트 파일은 **참고만** 하고 수정하지 않는다.

## 1. 배경

`product-service`는 이미 `CLAUDE.md` + `rules/` 4개(architecture, product-api, testing,
git-workflow) + `skills/` 4개(issue, pr, start, test) 구성을 갖추고 있었지만, 워크플로우
단계별로 skill이 뭉쳐 있다 보니 `rules/git-workflow.md` 내용이 `start`·`pr` skill에 그대로
중복되는 등 product-service 스스로 개선할 부분이 있었다. 개선 방향을 잡기 위해 같은
저장소 안의 다른 서비스들이 각자 채택한 skill 구성 방식도 함께 참고했다.

| 서비스 | 구성 |
|---|---|
| product-service (현재) | 워크플로우 단계별 skill (issue → start → pr → test) |
| payment-service | `commands/` + `docs/` + `plans/`(구현 계획 아카이브) |
| settlement-service | 액션 단위 skill(create-github-issue, create-github-pr, test-first-unit-test, verify-rules) + `agents/rule-checker` + `evals/` |
| user-service | 액션 단위 skill(commit, create-branch, create-github-issue, create-github-pr) + `evals/` |

이번 재설계는 다른 서비스와 우열을 가리려는 것이 아니라 product-service 자신의 구조를
개선하는 데 목적이 있다. 그중 액션 단위 skill 구성과 `evals/`, `agents/rule-checker`
패턴이 product-service가 겪고 있는 rules-skill 중복 문제를 푸는 데 도움이 되어 이 부분만
가져온다. 프로젝트 전체 표준화는 이번 범위가 아니다.

## 2. 설계 원칙

1. **단일 진실 공급원(SSOT).** `rules/*.md`는 "무엇을 지켜야 하는가"만 담고, `skills/*.md`는
   "어떻게 실행하는가"만 담는다. skill이 rule 내용을 복제하지 않고 참조만 한다. (기존
   `start`/`pr` skill에 `git-workflow.md` 내용이 그대로 중복되던 문제를 해소)
2. **루트 파일 우선.** 이슈/PR 템플릿, 라벨, 리뷰어 후보, 커밋 관례는 프로젝트 루트에 이미
   존재하는 실제 파일(`.github/ISSUE_TEMPLATE/*.md`, `.github/PULL_REQUEST_TEMPLATE.md`,
   `.github/CODEOWNERS`, `git log`)을 그때그때 읽어서 쓴다. skill 문서 안에 하드코딩하지 않는다.
   (인터넷에서 가져온 범용 컨벤션 문서를 그대로 이식하지 않는다 — 실제 검증 결과 이 프로젝트와
   무관한 다른 프로젝트의 PR 체크리스트가 섞여 있었음을 확인했다.)
3. **액션 단위 skill.** 하나의 skill은 하나의 명확한 동작만 담당한다. 기존 `start`/`pr`처럼
   여러 단계를 한 skill이 뭉쳐서 처리하지 않는다.
4. **product-service 고유 리스크를 별도 skill로.** FE 호환성·category/ID 계약·공유 docs 동기화처럼
   product-service만 갖는 리스크는 범용 skill로 커버되지 않으므로 전용 skill(`sync-product-docs`)을 둔다.
5. **공유 문서는 섹션 단위로만 건드린다.** `docs/error-codes.md`, `docs/erd/schema.md`처럼 여러
   서비스가 섹션을 나눠 쓰는 문서는 product-service 소관 섹션만 수정하고 다른 서비스 섹션은
   읽기 전용으로 취급한다.
6. **위험한 동작은 항상 사용자 승인 후.** 문서 수정, PR 생성, push, 리뷰어 지정처럼 사람에게
   영향이 가거나(리뷰어는 Slack 알림 발생) 여러 서비스가 보는 자원을 바꾸는 동작은 실행 전
   사용자에게 보여주고 승인받는다.
7. **모듈 경계 — 타 서비스 연동은 임의로 진행하지 않는다.** product-service 작업 중 다른
   서비스(gRPC 호출 대상, `.proto` 계약 등)와 상관관계가 생기면 직접 판단해서 다른 모듈을
   고치거나 계약을 추정하지 않고, 반드시 먼저 사용자에게 확인한다. (settlement-service의
   CLAUDE.md에 이미 있는 "모듈 경계" 원칙을 product-service에도 동일하게 적용)

## 3. 디렉터리 구조

```text
product-service/
├── CLAUDE.md                          # 라우터 전용: 무엇을 어디서 찾는지 안내
└── .claude/
    ├── rules/                          # "무엇을 지켜야 하는가"
    │   ├── architecture.md            # 유지
    │   ├── product-api.md             # 유지 + 외부/내부 응답 wrapper 구분 규칙 추가
    │   ├── testing.md                 # 유지
    │   └── git-workflow.md            # 축소: 브랜치 타입만 남기고 템플릿/체크리스트는 루트 참조로 전환
    ├── skills/                         # "어떻게 실행하는가" — 액션 단위
    │   ├── create-github-issue/SKILL.md
    │   ├── create-branch/SKILL.md
    │   ├── commit/SKILL.md
    │   ├── create-github-pr/SKILL.md
    │   ├── write-tests/SKILL.md
    │   ├── verify-rules/SKILL.md
    │   └── sync-product-docs/SKILL.md         # product 전용
    ├── agents/
    │   └── rule-checker.md             # PR 전 최종 게이트
    └── plans/                          # 이 설계 문서 및 향후 구현 계획 저장 위치
        └── 2026-07-06-skill-redesign.md
```

기존 `issue/pr/start/test` 4개 skill은 폐기한다. `start`가 하던 "issue 확인 → branch 생성 →
rules 확인" 오케스트레이션은 별도 skill 없이 `CLAUDE.md`의 "작업 시작 체크리스트" 섹션에서
각 액션 skill을 순서대로 안내하는 방식으로 대체한다.

## 4. Rules 변경사항

- `architecture.md`: **"모듈 경계 및 타 서비스 연동" 절 신규 추가**.
  - `product-service/` 안은 자유롭게 읽고 쓴다.
  - 다른 서비스 모듈(order/payment/settlement/user-service, apigateway, config, discovery)은
    참고용으로만 읽는다. 파일 생성·수정·삭제 등 쓰기 작업을 하지 않는다.
  - `common-module`은 공유 라이브러리이므로 변경이 필요하면 다른 서비스에 미치는 영향을
    사용자에게 먼저 알리고 승인받은 후 진행한다.
  - **gRPC/타 서비스 API 연동이 필요한 작업** — 다른 서비스의 gRPC 응답에서 새 필드가
    필요하거나, `.proto` 계약을 바꿔야 하는 경우 — 다른 서비스 코드나 `.proto`를 임의로
    수정하거나 계약을 추정하지 않는다. 대신:
    1. 어떤 서비스의 어떤 API/gRPC 메서드가 어떻게 바뀌어야 하는지 정리해 사용자에게 보고한다.
    2. 사용자 승인 후에만 진행한다. 사용자가 직접 다른 서비스 담당자와 조율하거나 "다른
       서비스도 같이 고쳐줘"라고 명시적으로 지시할 때만 다른 모듈에 손을 댄다.
  - product-service 쪽 `.proto`(`product_query.proto`, `order_product.proto` 등)를 변경할 때도
    이를 가져다 쓰는 다른 서비스(order-service, user-service 등)에 영향이 가므로, 변경 전
    반드시 사용자에게 알리고 승인받는다.
- `product-api.md`: 아래 규칙을 추가한다.
  - 외부(공개/판매자/관리자) API는 `ApiResult<T>` 또는 `PageResponse<T>`로 감싼다.
  - 내부(`/internal/**`, 타 서비스 간 호출) API는 wrapper 없이 raw DTO를 반환한다.
  - (2026-07-06 기준 코드 조사 결과 이미 이 규칙대로 구현되어 있음 — 문서에만 없던 gap을 메움)
- `testing.md`: 내용 변경 없음.
- `git-workflow.md`: 브랜치 타입·이슈 우선 원칙만 남기고, PR 체크리스트/커밋 세부 규칙은
  "실행 시점에 루트 파일을 읽어라"로 대체한다(중복 제거). **"Issue 우선" 원칙은 이제 문서상
  선언에 그치지 않고 `commit` skill의 사전 확인 게이트로 실제 강제된다** (5.1 참고).

## 5. Skills 상세

### 5.1 공통 액션 skill (6개)

| Skill | 역할 | 참조하는 루트/rules 파일 |
|---|---|---|
| `create-github-issue` | 이슈 타입·범위·완료조건 확인 후 GitHub 이슈 생성 | `.github/ISSUE_TEMPLATE/*.md` 본문 구조는 그대로 쓰되, **라벨은 frontmatter를 맹신하지 않고 `gh label list`로 실존 여부를 항상 재확인**한다. (2026-07-06 확인 결과 `feature_request.md`의 frontmatter는 `labels: feature`지만 실제 저장소 라벨은 `feature`가 아니라 `feat`다 — 템플릿 frontmatter도 틀릴 수 있다는 근거) **이슈 생성 시 항상 `--assignee @me`를 지정한다** (2026-07-06 실제로 이슈 #210을 assignee 없이 생성했다가 뒤늦게 추가한 사고를 계기로 추가된 규칙 — `create-github-pr`의 기존 assignee 규칙과 동일하게 이슈에도 적용) |
| `create-branch` | 이슈 번호 기준 `develop`에서 브랜치 생성 | `rules/git-workflow.md`의 브랜치 타입 |
| `commit` | 변경 단위별 커밋 메시지 작성 | 고정 문서 없음 — 최근 `git log` 관례(`feat/fix/refactor/docs/chore/style/test`)를 그대로 따름. **커밋 실행 전 사전 확인 필수**(아래 "커밋 전 사전 확인" 참고) |
| `create-github-pr` | branch/diff/build 확인 후 PR 생성, 리뷰어 지정 | `.github/PULL_REQUEST_TEMPLATE.md`, `.github/CODEOWNERS` |
| `write-tests` | 변경 계층에 맞는 테스트 작성/보강 | `rules/testing.md` |
| `verify-rules` | 구현이 4개 rules 문서를 지켰는지 점검 | `rules/*.md` 전체 |

**`commit` 커밋 전 사전 확인 (필수 게이트)**:
- `git branch --show-current`로 현재 브랜치를 확인한다. **`develop`·`main`이면 커밋을 만들지 않는다.**
  관련 이슈가 있는지 먼저 사용자에게 확인하고, 없으면 `create-github-issue`로 이슈를 만든 뒤
  `create-branch`로 이슈 번호 기반 브랜치를 만들고 나서 진행한다.
  (2026-07-06 실제로 이 설계 문서 자체를 `develop`에 바로 커밋했다가 걸러지지 못한 사고가 있었음 —
  이 사고를 계기로 추가된 규칙)
- 이미 작업 브랜치에 있다면 브랜치명의 이슈 번호(`<type>/#<번호>-...`)가 실제 존재하는 이슈인지
  확인한다(`gh issue view <번호>`). 이슈 없이 임의로 만든 브랜치면 사용자에게 알리고 진행 여부를 묻는다.
- 이 확인은 `commit` skill 자체의 선행 조건이며, `verify-rules`/`agents/rule-checker`에도 동일 조건을
  게이트로 추가한다(아래 5.3 참고).

**`create-github-pr` 리뷰어 로직** (settlement-service 패턴 채택):
- 리뷰어 후보 = `.github/CODEOWNERS` 전체 인원 − PR 작성자 본인.
- 리뷰어는 Slack 전체 알림을 유발하므로, PR 생성 직전 승인 화면에서 반드시 다시 보여주고
  확인받는다. 사용자가 빼거나 추가하면 반영한다.
- 리뷰어를 확정할 수 없으면(팀 불명) `--reviewer` 플래그 자체를 생략한다. 추측으로 아무나
  태그하지 않는다.
- 리뷰어 태깅만 실패해도 PR 생성 자체는 계속 진행하고, 이후 `gh pr edit --add-reviewer`로
  재시도하도록 안내한다.
- 라벨은 브랜치/커밋 타입에서 도출하되, `gh label list`로 실제 존재하는 라벨인지 먼저 확인한다.

### 5.2 product 전용 skill — `sync-product-docs`

기존 계획했던 `verify-product-api-contract`에서 범위를 넓혀 이름을 변경했다. API 계약뿐 아니라
product-service와 관련된 루트 `docs/` 전체를 다룬다.

**대상 문서 매핑**

| 코드 변경 유형 | 대조/동기화 대상 문서 | 비고 |
|---|---|---|
| controller/DTO 변경 | `docs/api-spec/product.md` | 전체 파일이 product 전용 |
| `ProductErrorCode` 신규/변경 | `docs/error-codes.md`의 "## 상품 (PRODUCT)" 섹션만 | 다른 서비스 섹션(SYS/ORDER/PAYMENT/USER/SETTLEMENT) 절대 수정 금지 |
| `@Entity` 필드 변경 | `docs/erd/schema.md`의 "## Product Service" 섹션만 | 다른 서비스 섹션 절대 수정 금지 |
| 도메인 모델/상태(enum 등) 변경 | `docs/domain-glossary/product.md` | 전체 파일이 product 전용 |

**절차**
1. 이번 브랜치의 변경 파일 중 위 4가지 유형에 해당하는 변경을 찾는다.
2. 각 변경을 대응하는 문서(해당 섹션)와 대조한다.
3. 불일치를 발견하면 목록으로 사용자에게 보고한다. 코드가 맞고 문서가 틀렸는지, 문서가 맞고
   코드가 틀렸는지 판단 근거를 함께 제시한다.
4. **사용자 승인을 받은 항목만** 문서를 수정한다. 승인 없이 자동으로 고치지 않는다 —
   공유 문서라서 다른 서비스 팀원도 참조하기 때문이다.
5. 코드가 틀린 경우(문서가 맞음)로 판단되면 문서는 건드리지 않고 코드 수정이 필요하다고만
   보고한다(자동 코드 수정 안 함).

**금지 사항**
- `docs/error-codes.md`, `docs/erd/schema.md`에서 product-service 소관 섹션 외의 다른 서비스
  섹션에는 어떤 diff도 만들지 않는다. (섹션 헤더로 범위를 명확히 구분해서 판단)
- 사용자 승인 없이 `docs/` 파일을 커밋하지 않는다.

**트리거**: `create-github-pr` 실행 전 항상 먼저 호출된다(자동 편입). 사용자가 명시적으로
"product docs 동기화해줘"라고 요청해도 단독 실행 가능하다.

### 5.3 `agents/rule-checker`

PR 생성 직전 `verify-rules`와 `sync-product-docs`의 결과를 종합해 최종 게이트 역할을 한다
(settlement-service 패턴 차용).

- `verify-rules` 위반이 하나라도 있으면 PR 생성을 막는다.
- `sync-product-docs`가 "다른 서비스 섹션에 diff 발생" 같은 금지 사항 위반을 감지하면 PR
  생성을 막는다.
- **모듈 경계 위반 검사**: 이번 브랜치의 diff에 `product-service/`, `docs/`(승인된 범위 내) 이외의
  다른 서비스 모듈 경로에 대한 변경이 있으면 일단 PR 생성을 멈추고 그 목록을 사용자에게 보여준다.
  사용자가 그 자리에서 "맞다, 진행해도 된다"고 확인해야만 계속 진행한다 — 자동으로 과거 승인
  여부를 추적하거나 판단하지 않는다.
- **이슈/브랜치 확인**: 현재 브랜치가 `develop`·`main`이거나, 브랜치명의 이슈 번호가 실제 존재하지
  않으면 PR 생성을 막는다. (`commit` skill의 사전 확인과 동일 조건 — 커밋 시점에 놓쳤어도 PR
  직전에 한 번 더 걸러낸다)
- 위 네 가지가 모두 통과해야 `create-github-pr`이 실제 생성 단계로 진행한다.

## 6. evals 계획

7개 skill(공통 6개 + `sync-product-docs`) 각각에 `evals/evals.json`을 추가한다. 이 설계
문서에는 "무엇을 검증할지" 시나리오만 정의하고, 실제 테스트 케이스 작성은 구현 단계
(writing-plans 이후)에서 진행한다.

예시 시나리오:
- `create-github-issue`: 범위 없이 "이슈 만들어줘"라고만 했을 때 바로 생성하지 않고 정보를
  먼저 확인하는가. 생성된 이슈에 `--assignee @me`가 항상 붙어있는가.
- `create-github-pr`: PR 작성자 본인이 리뷰어 후보에서 제외되는가. CODEOWNERS를 못 찾으면
  `--reviewer`를 생략하는가.
- `sync-product-docs`: 스펙에 없는 필드가 코드에 있을 때 감지하는가. `docs/error-codes.md`의
  다른 서비스 섹션은 절대 수정하지 않는가.
- `verify-rules`: 계층 위반(Controller에 비즈니스 로직 등)을 실제로 잡아내는가.

## 7. 작업 흐름 (Data Flow)

```text
CLAUDE.md 체크리스트 확인
  → create-github-issue (이슈 없으면)
  → create-branch (develop 기준, <type>/#이슈번호-내용)
  → (구현)
  → write-tests (변경 계층별 테스트)
  → sync-product-docs (product 관련 docs 대조·동기화)
  → verify-rules (architecture/product-api/testing/git-workflow 준수 확인)
  → commit (사전 확인: 현재 브랜치가 develop/main이 아닌지, 이슈 번호가 실존하는지 먼저 확인
            → 통과해야 최근 git log 관례 기반 타입으로 커밋 진행)
  → create-github-pr
       ├─ .github/PULL_REQUEST_TEMPLATE.md 읽어서 본문 채움
       ├─ .github/CODEOWNERS 전체(작성자 제외)를 --reviewer로 제안 → 승인 화면에서 재확인
       └─ agents/rule-checker: verify-rules + sync-product-docs + 모듈 경계 검사 3가지 종합 게이트
            (위반 있으면 PR 생성 중단, 없으면 진행)
```

## 8. 조사 과정에서 발견한 기존 이슈 (참고용)

이번 설계로 자연스럽게 해소되거나, 별도로 고쳐야 할 기존 이슈:

- `ProductDetailResponse`에 `docs/api-spec/product.md`에 없는 필드 2개(`sellerProfileImageUrl`,
  `sellerProductCount`)가 이미 구현되어 있음 — `sync-product-docs` 첫 실행 시 잡아서 문서에
  반영해야 한다.
- 기존 `issue` skill이 기능 이슈 라벨을 `feat`으로 안내하지만, 실제
  `.github/ISSUE_TEMPLATE/feature_request.md`의 frontmatter는 `labels: feature`다 →
  `create-github-issue`는 실제 템플릿 값을 그대로 읽어 쓰도록 설계했으므로 이 문제가 재발하지
  않는다.
- 외부에서 붙여넣은 범용 Git 컨벤션 문서(YouthFit 프로젝트의 PR 체크리스트 포함)는 이
  프로젝트 것이 아님을 확인했다. 참고 자료로 저장하지 않는다.
- (2026-07-06) 이 설계 문서 자체를 작성하는 과정에서 이슈/브랜치 없이 `develop`에 바로
  커밋하는 사고가 실제로 발생했다 → `commit` skill과 `agents/rule-checker`에 기계적 사전
  확인 게이트를 추가하는 계기가 됨 (5.1, 5.3 참고). 사고 수습: 커밋을 `docs/#210-...` 브랜치로
  옮기고 `develop`은 원상 복구함(둘 다 미푸시 상태였어서 안전하게 처리 가능했음).
- (2026-07-06) 같은 이슈 #210을 assignee 없이 생성했다가 뒤늦게 `--add-assignee @me`로
  추가하는 사고가 있었다 → `create-github-issue`가 이슈 생성 시 항상 `--assignee @me`를
  붙이도록 규칙에 명시함 (5.1 참고).

## 9. Out of Scope

- 다른 서비스(order/payment/settlement/user/apigateway/config/discovery)의 `.claude/` 구조 변경.
- 프로젝트 전체 skill 표준화/통합.
- checkstyle/formatter 설정 변경 — 이미 올바르게 적용되어 있음을 확인했다(변경 불필요).

## 10. 다음 단계

이 설계 문서 승인 후 `writing-plans` 스킬로 전환하여 아래 순서의 구현 계획을 작성한다.

1. 기존 `issue/pr/start/test` skill 4개 제거
2. 공통 액션 skill 6개 신규 작성
3. `sync-product-docs` skill 신규 작성
4. `agents/rule-checker.md` 신규 작성
5. `rules/product-api.md`, `rules/git-workflow.md` 수정
6. `CLAUDE.md` 라우터 갱신 (체크리스트 섹션 포함)
7. 7개 skill의 `evals/evals.json` 작성
8. `docs/api-spec/product.md`에 누락된 필드 2개 반영 (8장에서 발견한 기존 드리프트)
