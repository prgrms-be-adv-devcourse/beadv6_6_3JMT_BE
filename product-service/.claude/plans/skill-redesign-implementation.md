# Product Service Skill Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** product-service의 `.claude/`를 워크플로우 단계별 skill 4개(issue/pr/start/test)에서 액션 단위 skill 7개 + `agents/rule-checker` + `evals/`로 재설계한다.

**Architecture:** `rules/*.md`는 "무엇을 지켜야 하는가"만 담고, `skills/*.md`는 "어떻게 실행하는가"만 담아 skill이 rule 내용을 복제하지 않고 참조만 하게 한다. 각 skill은 하나의 명확한 동작만 담당하며, `agents/rule-checker`가 PR 생성 직전 여러 skill의 검증 결과를 종합하는 최종 게이트 역할을 한다.

**Tech Stack:** Markdown SKILL.md (frontmatter: name/description), gh CLI, git, Gradle.

## Global Constraints

- 설계 문서: `product-service/.claude/plans/skill-redesign.md` (모든 결정의 근거는 이 문서를 따른다)
- 범위: `product-service/CLAUDE.md`, `product-service/.claude/` 전체만 수정한다. 다른 서비스는 건드리지 않는다.
- 파일명에 날짜를 넣지 않는다.
- 커밋 전 항상 현재 브랜치가 `develop`/`main`이 아닌지, 브랜치의 이슈 번호가 실존하는지 확인한다 (설계 문서 5.1의 "커밋 전 사전 확인" 게이트).
- 라벨은 항상 `gh label list`로 실존 여부를 확인한 뒤 사용한다. 실제 저장소 라벨: `chore`, `docs`, `feat`, `fix`, `refactor`, `test`, `release` (`feature`, `bug`는 존재하지 않는다).
- 이슈/PR 생성 시 항상 `--assignee @me`를 지정한다.

---

### Task 1: rules 3개 파일 수정

**Files:**
- Modify: `product-service/.claude/rules/architecture.md`
- Modify: `product-service/.claude/rules/product-api.md`
- Modify: `product-service/.claude/rules/git-workflow.md`

**Interfaces:**
- Produces: 이후 모든 skill이 참조하는 규칙 문서(모듈 경계, 응답 wrapper 규칙, 브랜치 타입 표).

- [ ] **Step 1: architecture.md에 "모듈 경계 및 타 서비스 연동" 절 추가**

`product-service/.claude/rules/architecture.md` 파일 끝(코드 스타일 섹션 뒤)에 아래 내용을 추가한다.

```markdown

## 모듈 경계 및 타 서비스 연동

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
```

- [ ] **Step 2: product-api.md에 응답 wrapper 규칙 추가**

`product-service/.claude/rules/product-api.md`의 "## 응답 형식" 섹션 바로 뒤에 아래 절을
추가한다.

```markdown

## 응답 wrapper 규칙

- 외부(공개/판매자/관리자) API는 `ApiResult<T>` 또는 `PageResponse<T>`로 감싼다.
- 내부(`/internal/**`, 타 서비스 간 호출) API는 wrapper 없이 raw DTO를 반환한다.

예시:

```java
// 외부 — wrapper 있음
@GetMapping("/products/{productId}")
public ApiResult<ProductDetailResponse> getProduct(@PathVariable UUID productId) { ... }

// 내부 — wrapper 없음
@GetMapping("/internal/products/{productId}/content")
public ProductContentResponse getProductContent(@PathVariable UUID productId) { ... }
```
```

- [ ] **Step 3: git-workflow.md를 브랜치 타입·Issue 우선 원칙 중심으로 축소**

`product-service/.claude/rules/git-workflow.md` 전체를 아래 내용으로 교체한다.

```markdown
# Git Workflow 규칙

이 문서는 Product Service 작업에서 항상 지켜야 하는 핵심 원칙만 담는다. 실행 절차(이슈를
어떻게 만들고 PR을 어떻게 올리는지)는 `.claude/skills/`의 각 액션 skill을 따른다.

## Issue 우선

구현을 시작하기 전에 GitHub issue를 먼저 만들거나 확인한다. 절차는
`.claude/skills/create-github-issue/SKILL.md`를 따른다. 이슈/브랜치 없이 `develop`·`main`에
바로 커밋하지 않는다 — 이 확인은 `.claude/skills/commit/SKILL.md`의 필수 게이트다.

## Branch 타입

최신 `develop`에서 작업 브랜치를 생성한다. 형식: `<type>/#<issue-number>-<description>`

| 목적 | type |
|---|---|
| 기능 추가 | `feat` |
| 버그 수정 | `fix` |
| 문서 작업 | `docs` |
| 테스트 추가/수정 | `test` |
| 설정/빌드/작업환경 | `chore` |
| 구조 개선 | `refactor` |
| 코드 포맷 | `style` |

브랜치 생성 절차는 `.claude/skills/create-branch/SKILL.md`를 따른다.

## PR

PR 템플릿, 체크리스트, 리뷰어 지정은 이 문서에 하드코딩하지 않는다. 실행 시점에 실제 루트
파일(`.github/PULL_REQUEST_TEMPLATE.md`, `.github/CODEOWNERS`)을 읽어서 따른다. 절차는
`.claude/skills/create-github-pr/SKILL.md`를 따른다.

## GitHub Actions

CI는 `develop` 또는 `main` 대상 PR에서 실행된다. `product-service/**` 변경 시
`product-service-ci`가 실행된다. reusable build는 PostgreSQL 16을 띄우고
`./gradlew clean build --no-daemon`을 실행한다. CI가 실패하면 merge할 수 없다.

CD는 `main` push/merge 이후에만 실행된다.
```

- [ ] **Step 4: 세 파일이 올바르게 저장됐는지 확인**

```bash
cat product-service/.claude/rules/architecture.md | tail -20
cat product-service/.claude/rules/product-api.md | grep -A 10 "응답 wrapper 규칙"
cat product-service/.claude/rules/git-workflow.md
```

Expected: 세 파일 모두 위에서 작성한 내용이 그대로 반영되어 있음.

- [ ] **Step 5: 커밋**

```bash
git add product-service/.claude/rules/architecture.md product-service/.claude/rules/product-api.md product-service/.claude/rules/git-workflow.md
git commit -m "docs: product-service rules에 모듈 경계·응답 wrapper 규칙 추가, git-workflow 축소"
```

---

### Task 2: 기존 skill 4개 제거

**Files:**
- Delete: `product-service/.claude/skills/issue/`
- Delete: `product-service/.claude/skills/pr/`
- Delete: `product-service/.claude/skills/start/`
- Delete: `product-service/.claude/skills/test/`

**Interfaces:**
- Consumes: Task 1에서 수정된 rules 파일 (이후 새 skill들이 이 rules를 참조하므로 먼저 정리).
- Produces: 빈 `product-service/.claude/skills/` 디렉터리 (Task 3~9가 여기 새 skill을 채운다).

- [ ] **Step 1: 기존 skill 디렉터리 삭제**

```bash
rm -rf product-service/.claude/skills/issue
rm -rf product-service/.claude/skills/pr
rm -rf product-service/.claude/skills/start
rm -rf product-service/.claude/skills/test
```

- [ ] **Step 2: 삭제 확인**

```bash
ls product-service/.claude/skills/ 2>&1 || echo "EMPTY_OR_NOT_EXISTS"
```

Expected: 4개 디렉터리가 더 이상 없음(디렉터리 자체가 없다는 오류 메시지가 나와도 정상).

- [ ] **Step 3: 커밋**

```bash
git add -A product-service/.claude/skills/
git commit -m "chore: product-service 기존 워크플로우 단계별 skill 4개 제거"
```

---

### Task 3: create-github-issue skill 작성

**Files:**
- Create: `product-service/.claude/skills/create-github-issue/SKILL.md`
- Create: `product-service/.claude/skills/create-github-issue/evals/evals.json`

**Interfaces:**
- Produces: `create-github-issue` skill — 이후 Task 4(create-branch)가 이슈 번호를 입력으로 받는다.

- [ ] **Step 1: SKILL.md 작성**

```markdown
---
name: create-github-issue
description: 레포의 GitHub 이슈 템플릿(.github/ISSUE_TEMPLATE)에 맞춰 버그 리포트 또는 기능 요청 이슈를 작성하고 gh issue create로 실제 등록한다. 사용자가 "이슈 만들어줘", "버그 이슈 올려줘", "기능 요청 등록해줘"라고 하거나, 새 기능/개선/버그를 이슈로 정리하려 할 때 사용한다.
---

# Product Issue 생성 Skill

product-service 작업에서 GitHub 이슈 생성을 요청받으면 이 절차를 따른다.

## 1. 이슈 유형 판단

내용을 보고 버그 리포트인지 기능 요청인지 판단한다. 애매하면 사용자에게 묻는다.

## 2. 필수 정보 확인

아래 정보가 부족하면 바로 만들지 않고 한 번에 모아서 되묻는다.

- 버그: 재현 단계, 예상 결과, 실제 결과
- 기능 요청: 문제 제기, 제안하는 기능

## 3. 템플릿 읽기

- 버그: `.github/ISSUE_TEMPLATE/bug_report.md`
- 기능 요청: `.github/ISSUE_TEMPLATE/feature_request.md`

템플릿의 섹션 구조를 그대로 따른다. **frontmatter의 `labels` 값은 참고만 하고 맹신하지
않는다** — 실제 저장소에 그 라벨이 있는지 `gh label list`로 항상 재확인한다.
(`feature_request.md`의 frontmatter는 `labels: feature`지만, 실제 저장소 라벨은 `feature`가
아니라 `feat`이다.)

## 4. 라벨 확인

```bash
gh label list
```

내용 성격에 맞는 실제 존재하는 라벨을 고른다. 매칭되는 라벨이 없으면 라벨 없이 진행한다
(새 라벨을 만들지 않는다).

## 5. 초안 작성 및 승인

title/body/label 초안을 사용자에게 보여주고 승인받는다. 승인 전에는 `gh issue create`를
실행하지 않는다.

## 6. 생성

승인되면 아래 형식으로 생성한다. **assignee는 항상 작성자 본인(`@me`)으로 지정한다.**

```bash
gh issue create \
  --title "<제목>" \
  --label "<라벨>" \
  --assignee "@me" \
  --body-file <body-file>
```

`--label`은 4단계에서 실존이 확인된 값만 넣는다. 매칭되는 라벨이 없으면 `--label` 플래그를
생략한다.

Windows에서 `gh`가 PATH에 없으면 전체 경로를 사용한다.

```powershell
& "C:\Program Files\GitHub CLI\gh.exe" issue create --title "<제목>" --label "<라벨>" --assignee "@me" --body-file <body-file>
```

## 톤

담백하게 작성한다. 과장된 수식어, 완료됐다는 식의 서술을 쓰지 않는다. 인증 필요 여부,
기준 API spec/ERD 문서, 통과해야 하는 테스트를 명시한다.

## 금지 사항

- 범위 없이 "이슈 만들어줘"라고만 하면 바로 생성하지 않는다.
- template placeholder를 그대로 남기지 않는다.
- 실제로 존재하지 않는 라벨을 임의로 지정하지 않는다.
- assignee 없이 이슈를 생성하지 않는다.
```

- [ ] **Step 2: evals.json 작성**

```json
{
  "skill_name": "create-github-issue",
  "evals": [
    {
      "id": 0,
      "prompt": "상품 상세 조회 API에서 판매자 프로필 이미지가 안 나와. 원래는 sellerProfileImageUrl 필드에 값이 있어야 하는데 항상 null이야. 재현은 아무 상품 상세 조회하면 바로 보임. 이거 버그 이슈로 올려줘. 단, 실제로 gh issue create를 실행하지 말고 만들어질 이슈 본문과 gh 명령을 보여줘.",
      "expected_output": "bug_report 템플릿 섹션 구조로 본문이 채워지고, 되묻는 질문 없이 바로 완성. title은 [BUG] 접두사. label은 gh label list로 확인된 실제 라벨(fix) 사용, bug라는 라벨은 쓰지 않음. --assignee @me 포함.",
      "files": []
    },
    {
      "id": 1,
      "prompt": "상품 목록 조회에 판매자별 필터 추가하면 좋겠어. 이슈로 정리해줘. 단, 실제로 gh issue create를 실행하지 말고 만들어질 이슈 본문과 gh 명령을 보여줘.",
      "expected_output": "feature_request 템플릿 섹션 구조(문제 제기/제안하는 기능/대안/추가 정보)로 채워짐. label은 gh label list로 확인된 실제 라벨(feat) 사용, feature라는 라벨은 쓰지 않음. --assignee @me 포함.",
      "files": []
    },
    {
      "id": 2,
      "prompt": "이슈 만들어줘.",
      "expected_output": "범위(버그/기능, 제목, 재현 방법 또는 제안 내용)가 없으므로 바로 생성하지 않고 필요한 정보를 한 번에 모아 되묻는다. gh issue create를 실행하지 않는다.",
      "files": []
    }
  ]
}
```

- [ ] **Step 3: 파일 검증**

```bash
cat product-service/.claude/skills/create-github-issue/SKILL.md | head -5
node -e "JSON.parse(require('fs').readFileSync('product-service/.claude/skills/create-github-issue/evals/evals.json','utf8')); console.log('valid json')"
```

Expected: frontmatter가 올바르게 출력되고, `valid json`이 출력됨(JSON 문법 오류 없음).

- [ ] **Step 4: 커밋**

```bash
git add product-service/.claude/skills/create-github-issue/
git commit -m "feat: create-github-issue skill 추가"
```

---

### Task 4: create-branch skill 작성

**Files:**
- Create: `product-service/.claude/skills/create-branch/SKILL.md`
- Create: `product-service/.claude/skills/create-branch/evals/evals.json`

**Interfaces:**
- Consumes: Task 3의 이슈 번호(사용자가 이슈 생성 후 이어서 요청).
- Produces: 이슈 번호 기반 브랜치 — Task 5(commit), Task 7(create-github-pr)이 이 브랜치명 규칙(`<type>/#<번호>-...`)에 의존한다.

- [ ] **Step 1: SKILL.md 작성**

```markdown
---
name: create-branch
description: 이슈 번호를 기준으로 최신 develop에서 작업 브랜치를 생성한다. "브랜치 만들어줘", "이슈 #12로 브랜치 따줘" 같은 요청이나, create-github-issue 직후 작업을 시작할 때 사용한다.
---

# Branch 생성 Skill

## 절차

1. 대상 이슈 번호와 타입을 확인한다. 모르면 사용자에게 묻거나 `gh issue list`로 후보를 보여준다.
2. 최신 develop을 받는다.

```bash
git checkout develop
git pull origin develop
```

3. 브랜치명을 정한다: `<type>/#<issue-number>-<description>` (description은 영문
   kebab-case 권장).

```bash
git checkout -b "<type>/#<issue-number>-<description>"
```

타입은 `.claude/rules/git-workflow.md`의 브랜치 타입 표를 따른다.

4. 생성 결과를 보고한다: 브랜치명, 시작 커밋.

## 엣지 케이스

- 이미 작업 브랜치에 있고 새 브랜치가 필요 없어 보이면, 계속 진행할지 사용자에게 확인한다.
- 같은 이름의 브랜치가 이미 있으면 덮어쓰지 않고 사용자에게 알린다.
- uncommitted 변경사항이 있으면 브랜치 전환 전에 `git status --short`로 보여주고 stash/커밋
  여부를 사용자에게 확인한다.
```

- [ ] **Step 2: evals.json 작성**

```json
{
  "skill_name": "create-branch",
  "evals": [
    {
      "id": 0,
      "prompt": "이슈 #45 상품 공개 조회 API 구현 브랜치 만들어줘. 실제 git 명령은 실행하지 말고 실행할 명령만 보여줘.",
      "expected_output": "git checkout develop, git pull origin develop, git checkout -b feat/#45-... 순서로 명령 제시. 브랜치 타입은 이슈 성격(기능 추가)에 맞춰 feat 사용.",
      "files": []
    },
    {
      "id": 1,
      "prompt": "브랜치 만들어줘.",
      "expected_output": "이슈 번호와 타입이 없으므로 바로 생성하지 않고 대상 이슈 번호를 먼저 확인한다.",
      "files": []
    },
    {
      "id": 2,
      "prompt": "지금 uncommitted 변경사항이 있는 상태에서 이슈 #50 브랜치 만들어줘.",
      "expected_output": "브랜치 전환 전에 git status --short로 변경사항을 보여주고 stash 또는 커밋 여부를 먼저 사용자에게 확인한다.",
      "files": []
    }
  ]
}
```

- [ ] **Step 3: 파일 검증**

```bash
node -e "JSON.parse(require('fs').readFileSync('product-service/.claude/skills/create-branch/evals/evals.json','utf8')); console.log('valid json')"
```

Expected: `valid json` 출력.

- [ ] **Step 4: 커밋**

```bash
git add product-service/.claude/skills/create-branch/
git commit -m "feat: create-branch skill 추가"
```

---

### Task 5: commit skill 작성

**Files:**
- Create: `product-service/.claude/skills/commit/SKILL.md`
- Create: `product-service/.claude/skills/commit/evals/evals.json`

**Interfaces:**
- Consumes: Task 4에서 만든 브랜치명 규칙(`<type>/#<번호>-...`)을 사전 게이트에서 검증한다.
- Produces: 커밋 — Task 7(create-github-pr)이 이 커밋들을 diff로 읽는다.

- [ ] **Step 1: SKILL.md 작성**

```markdown
---
name: commit
description: 변경 단위별로 커밋 메시지를 작성하고 커밋한다. 커밋 실행 전 반드시 현재 브랜치와 이슈 연결 여부를 확인하는 사전 게이트를 거친다. "커밋해줘"라는 요청이나 논리적으로 완결된 변경 단위가 끝났을 때 사용한다.
---

# Commit Skill

## 0. 사전 확인 (필수 게이트 — 생략 불가)

커밋 메시지를 짓기 전에 먼저 확인한다.

```bash
git branch --show-current
```

- **현재 브랜치가 `develop` 또는 `main`이면 커밋하지 않는다.** 관련 이슈가 있는지 사용자에게
  확인하고, 없으면 `create-github-issue`로 이슈를 만든 뒤 `create-branch`로 이슈 번호 기반
  브랜치를 만들고 나서 진행한다.
- 이미 작업 브랜치에 있다면 브랜치명의 이슈 번호(`<type>/#<번호>-...`)가 실제 존재하는
  이슈인지 확인한다.

```bash
gh issue view <번호>
```

  이슈가 없거나 브랜치명에 이슈 번호가 없으면 사용자에게 알리고 계속 진행할지 확인한다.

이 확인을 통과하기 전에는 어떤 경우에도 `git commit`을 실행하지 않는다.

## 1. 변경 확인

```bash
git status --short
git diff --stat
```

## 2. 커밋 타입 판단

고정된 컨벤션 문서가 없으므로 최근 커밋 로그의 관례를 따른다.

```bash
git log --oneline -10
```

타입: `feat`(새 기능) · `fix`(버그 수정) · `refactor`(구조 개선, 기능 변경 없음) ·
`docs`(문서) · `chore`(설정/빌드) · `style`(포맷) · `test`(테스트).

## 3. 커밋 메시지 작성

형식: `<type>: <설명>`. 설명은 리뷰어가 바로 이해할 수 있게, 한국어/영어 혼용 가능.

```bash
git add <파일...>
git commit -m "<type>: <설명>"
```

## 금지 사항

- 0단계 게이트를 통과하기 전에 커밋하지 않는다.
- 관련 없는 변경을 한 커밋에 섞지 않는다.
- 실패하는 테스트를 숨기려고 파일을 제외하지 않는다.
```

- [ ] **Step 2: evals.json 작성**

```json
{
  "skill_name": "commit",
  "evals": [
    {
      "id": 0,
      "prompt": "지금 develop 브랜치에 있는데 이 변경사항 커밋해줘.",
      "expected_output": "0단계 게이트에서 현재 브랜치가 develop임을 확인하고 즉시 커밋을 거부한다. 관련 이슈가 있는지 먼저 묻고, 없으면 create-github-issue → create-branch 순서로 안내한다.",
      "files": []
    },
    {
      "id": 1,
      "prompt": "feat/#45-product-public-query-api 브랜치에서 ProductController.java를 수정했어. 커밋해줘.",
      "expected_output": "먼저 gh issue view 45로 이슈 존재를 확인한 뒤, git log 관례를 참고해 feat: 타입으로 커밋 메시지를 짓고 커밋한다.",
      "files": []
    },
    {
      "id": 2,
      "prompt": "random-branch-name이라는, 이슈 번호가 없는 브랜치에서 커밋해줘.",
      "expected_output": "브랜치명에 이슈 번호가 없음을 감지하고, 이대로 진행할지 사용자에게 먼저 확인한다. 확인 없이 바로 커밋하지 않는다.",
      "files": []
    }
  ]
}
```

- [ ] **Step 3: 파일 검증**

```bash
node -e "JSON.parse(require('fs').readFileSync('product-service/.claude/skills/commit/evals/evals.json','utf8')); console.log('valid json')"
```

Expected: `valid json` 출력.

- [ ] **Step 4: 커밋**

```bash
git add product-service/.claude/skills/commit/
git commit -m "feat: commit skill 추가 (커밋 전 이슈/브랜치 확인 게이트 포함)"
```

---

### Task 6: create-github-pr skill 작성

**Files:**
- Create: `product-service/.claude/skills/create-github-pr/SKILL.md`
- Create: `product-service/.claude/skills/create-github-pr/evals/evals.json`

**Interfaces:**
- Consumes: Task 7(sync-product-docs), Task 8(verify-rules)의 검증 결과. `.github/CODEOWNERS` 전체 인원 목록.
- Produces: 생성된 PR — Task 10(agents/rule-checker)이 이 skill 실행 직전 게이트로 호출된다.

- [ ] **Step 1: SKILL.md 작성**

```markdown
---
name: create-github-pr
description: 현재 브랜치의 커밋·diff·build를 확인해 레포의 PR 템플릿을 채우고, 사용자 승인 후 gh pr create로 Pull Request를 생성한다. 리뷰어는 CODEOWNERS 전체(작성자 제외)를 제안한다. "PR 만들어줘", "풀리퀘 올려줘" 같은 요청에 사용한다.
---

# Product PR 생성 Skill

## 사전 조건

- `develop`/`main` 브랜치에서 PR을 만들지 않는다.
- base 대비 commit이 없으면 만들지 않는다.
- push/생성 전 사용자 승인을 받는다.

## 절차

1. `.github/PULL_REQUEST_TEMPLATE.md`를 읽는다.
2. `CLAUDE.md`, `.claude/rules/architecture.md`, `.claude/rules/product-api.md`,
   `.claude/rules/testing.md`, `.claude/rules/git-workflow.md`를 읽는다.
3. 현재 브랜치, base(기본 `develop`), commit, diff를 확인한다.

```bash
git branch --show-current
git log develop..HEAD --oneline
git diff develop...HEAD --stat
```

4. 모듈 build를 실행한다(`test`만 실행하는 것으로 대체하지 않는다).

```bash
cd product-service
./gradlew clean build --no-daemon
```

Windows:

```powershell
cd product-service
.\gradlew.bat clean build --no-daemon
```

5. `sync-product-docs`와 `verify-rules`를 먼저 실행해 통과 여부를 확인한다
   (`agents/rule-checker`가 최종 게이트로 다시 종합한다).
6. 라벨·assignee·리뷰어를 정한다.
   - **라벨**: 브랜치/커밋 타입에서 도출하되 `gh label list`로 실존 확인 후 사용. 없으면 생략.
   - **assignee**: 작성자 본인(`--assignee @me`).
   - **리뷰어**: `.github/CODEOWNERS` 전체 인원 − 작성자 본인. 리뷰어는 Slack 전체 알림을
     유발하므로 다음 단계(승인 화면)에서 반드시 다시 보여주고 확인받는다. 확정 못 하면
     `--reviewer`를 생략한다(추측으로 아무나 달지 않는다).
7. PR 본문을 템플릿 구조 그대로 채운다. 체크리스트는 실제 diff/테스트 결과로 판단 가능한
   항목만 체크한다.
8. **base·본문·리뷰어·라벨을 한 화면에 보여주고 승인받는다.** 승인 전에는 `git push`도
   `gh pr create`도 실행하지 않는다.
9. 승인되면 생성한다.

```bash
git push -u origin <head-branch>
gh pr create \
  --base develop \
  --head <head-branch> \
  --title "<type>: <설명>" \
  --body-file <body-file> \
  --assignee "@me" \
  --reviewer <리뷰어1>,<리뷰어2> \
  --label "<라벨>"
```

`--reviewer`·`--label`이 정해지지 않았으면 플래그 자체를 뺀다(빈 값이면 `gh`가 에러낸다).

## 엣지 케이스

- 이미 같은 head의 PR이 있으면 중복 생성하지 않고 기존 PR URL을 알린다
  (`gh pr list --head <branch>`).
- 리뷰어 태깅만 실패해도 PR 자체는 살아남는다. `gh pr edit <N> --add-reviewer ...`로 재시도를
  안내한다.
- build 실패 시 원인을 분류해 사용자에게 보고하고, 로컬 DB 환경 차이로 인한 실패인지 CI에서도
  재현될지 판단해 PR 본문에 남긴다.

## 톤

담백하게. 과장된 수식어, 정형화된 강조 문구를 피한다.
```

- [ ] **Step 2: evals.json 작성**

```json
{
  "skill_name": "create-github-pr",
  "evals": [
    {
      "id": 0,
      "prompt": "feat/#45-product-public-query-api 브랜치에서 작업 끝났어. PR 만들어줘. gh pr create는 실제로 실행하지 말고 실행할 명령과 본문만 보여줘.",
      "expected_output": ".github/CODEOWNERS 전체 인원에서 작성자 본인을 제외한 목록을 --reviewer로 제안하고, 승인 화면에서 반드시 재확인을 요청한다. --assignee @me 포함. base는 develop 확인.",
      "files": []
    },
    {
      "id": 1,
      "prompt": "지금 develop 브랜치인데 PR 만들어줘.",
      "expected_output": "develop 브랜치에서는 PR을 만들 수 없다고 안내하고 작업 브랜치로 옮기라고 알린 뒤 멈춘다.",
      "files": []
    },
    {
      "id": 2,
      "prompt": "CODEOWNERS 파일이 없다고 가정하고 PR 만들어줘. gh pr create는 실행하지 말고 명령만 보여줘.",
      "expected_output": "리뷰어 후보를 확정할 수 없으므로 --reviewer 플래그를 생략하고 진행한다. 추측으로 아무나 리뷰어로 달지 않는다.",
      "files": []
    }
  ]
}
```

- [ ] **Step 3: 파일 검증**

```bash
node -e "JSON.parse(require('fs').readFileSync('product-service/.claude/skills/create-github-pr/evals/evals.json','utf8')); console.log('valid json')"
```

Expected: `valid json` 출력.

- [ ] **Step 4: 커밋**

```bash
git add product-service/.claude/skills/create-github-pr/
git commit -m "feat: create-github-pr skill 추가 (CODEOWNERS 기반 리뷰어 로직 포함)"
```

---

### Task 7: write-tests skill 작성

**Files:**
- Create: `product-service/.claude/skills/write-tests/SKILL.md`
- Create: `product-service/.claude/skills/write-tests/evals/evals.json`

**Interfaces:**
- Produces: 테스트 파일 — Task 6(create-github-pr)의 build 단계가 이 테스트를 포함해 검증한다.

- [ ] **Step 1: SKILL.md 작성**

```markdown
---
name: write-tests
description: Product Service 변경사항에 맞는 테스트 범위를 판단하고 테스트를 추가·수정한 뒤 build로 검증한다. "테스트 만들어줘", "PR 전에 테스트 확인해줘" 같은 요청에 사용한다.
---

# Write Tests Skill

## 1. 사전 확인

`.claude/rules/testing.md`, `.claude/rules/architecture.md`, `.claude/rules/product-api.md`를
읽는다.

## 2. 변경 계층 분류

```bash
git branch --show-current
git status --short
git diff --stat
```

- `presentation/controller` 변경 → Controller 테스트
- `application` 변경 → Service 테스트
- `domain` 변경 → Domain 테스트
- `infra/persistence` 변경 → Repository 테스트
- 문서만 변경 → 테스트 추가 없이 build 확인만

## 3. 기존 스타일 확인

```bash
rg --files product-service/src/test
rg "@SpringBootTest|MockMvc|Mockito|DataJpaTest|DisplayName" product-service/src/test
```

## 4. 작성 기준

`.claude/rules/testing.md`의 변경 유형별 기준을 따른다. public API 테스트에는 인증 헤더를
넣지 않는다. 인증 필요 API는 `X-User-Id`, `X-User-Role`을 직접 넣는다.

## 5. Build 검증

```bash
cd product-service
./gradlew clean build --no-daemon
```

## 6. 보고

추가/수정한 테스트 파일, 검증한 케이스, build 결과, 남은 테스트 공백을 보고한다.

## 금지 사항

- `test` task만 실행하고 PR 전 검증이 끝났다고 말하지 않는다.
- Controller 테스트에서 service 로직을 직접 검증하지 않는다.
- 실패한 테스트를 삭제해서 build를 통과시키지 않는다.
```

- [ ] **Step 2: evals.json 작성**

```json
{
  "skill_name": "write-tests",
  "evals": [
    {
      "id": 0,
      "prompt": "ProductController에 GET /products/{productId}/related 엔드포인트를 추가했어. 테스트 만들어줘.",
      "expected_output": "presentation/controller 변경으로 분류하고 ProductControllerTest에 요청 경로/파라미터/공통 응답 포맷/성공 status를 검증하는 테스트를 제안한다. 공개 API이므로 인증 헤더는 넣지 않는다.",
      "files": []
    },
    {
      "id": 1,
      "prompt": "docs/api-spec/product.md만 수정했어. 테스트 만들어줘.",
      "expected_output": "문서만 변경된 경우로 분류해 테스트 파일 추가 없이 build 확인만 안내한다.",
      "files": []
    },
    {
      "id": 2,
      "prompt": "테스트 다 작성했고 build도 통과했어. PR 전 검증 끝난 거지?",
      "expected_output": "write-tests가 커버하는 범위(테스트 작성·build)와 PR 전 전체 검증(sync-product-docs, verify-rules 포함)은 다르다는 점을 명확히 하고, 테스트만으로 PR 전 검증이 끝났다고 단정하지 않는다.",
      "files": []
    }
  ]
}
```

- [ ] **Step 3: 파일 검증**

```bash
node -e "JSON.parse(require('fs').readFileSync('product-service/.claude/skills/write-tests/evals/evals.json','utf8')); console.log('valid json')"
```

Expected: `valid json` 출력.

- [ ] **Step 4: 커밋**

```bash
git add product-service/.claude/skills/write-tests/
git commit -m "feat: write-tests skill 추가"
```

---

### Task 8: verify-rules skill 작성

**Files:**
- Create: `product-service/.claude/skills/verify-rules/SKILL.md`
- Create: `product-service/.claude/skills/verify-rules/evals/evals.json`

**Interfaces:**
- Consumes: Task 1에서 수정된 rules 4개 파일.
- Produces: 규칙 위반 목록 — Task 10(agents/rule-checker)이 이 결과를 종합 게이트의 1번 조건으로 사용한다.

- [ ] **Step 1: SKILL.md 작성**

```markdown
---
name: verify-rules
description: 현재 브랜치의 변경 코드를 product-service 규칙 4종(architecture, product-api, testing, git-workflow) 기준으로 검증한다. 위반이 있으면 위반 목록을 보여준다. "룰 검증해줘", "컨벤션 어긴 데 없는지 봐줘"라는 요청이나 create-github-pr 직전에 사용한다. 코드를 수정하지 않는다.
---

# Verify Rules Skill

## 절차

1. 이번 브랜치의 diff를 가져온다.

```bash
git diff develop...HEAD
```

2. `.claude/rules/architecture.md`, `.claude/rules/product-api.md`, `.claude/rules/testing.md`,
   `.claude/rules/git-workflow.md`를 각각 기준으로 diff를 검토한다.

검사 항목 예:
- Controller에 비즈니스 로직/DB 접근이 있는가 (architecture.md 위반)
- 외부 API가 `ApiResult`/`PageResponse`로 안 감싸져 있는가 (product-api.md 위반)
- public setter로 상태를 바꾸는가 (architecture.md 위반)
- 변경 계층에 맞는 테스트가 없는가 (testing.md 위반)
- 브랜치명에 이슈 번호가 없는가 (git-workflow.md 위반)

3. 위반을 목록으로 보고한다. 위반이 없으면 "위반 없음"을 명시적으로 보고한다.

## 금지 사항

- 코드를 직접 수정하지 않는다. 위반 보고만 한다.
- 확신 없는 항목을 위반으로 단정하지 않고 "확인 필요"로 표시한다.
```

- [ ] **Step 2: evals.json 작성**

```json
{
  "skill_name": "verify-rules",
  "evals": [
    {
      "id": 0,
      "prompt": "ProductController.java에서 컨트롤러 메서드 안에 ProductRepository를 직접 주입해서 findById를 호출하는 코드가 diff에 있어. 룰 검증해줘.",
      "expected_output": "architecture.md 위반(Controller가 Repository에 직접 접근)으로 보고한다. 코드를 직접 수정하지 않는다.",
      "files": []
    },
    {
      "id": 1,
      "prompt": "diff에 새 public API 컨트롤러 메서드가 ApiResult나 PageResponse 없이 ProductDetailResponse를 그대로 반환하는 코드가 있어. 룰 검증해줘.",
      "expected_output": "product-api.md의 응답 wrapper 규칙 위반(외부 API인데 wrapper 없음)으로 보고한다.",
      "files": []
    },
    {
      "id": 2,
      "prompt": "이 diff는 규칙을 다 지킨 것 같은데 룰 검증해줘.",
      "expected_output": "실제로 4개 규칙 문서 기준으로 diff를 확인한 뒤, 위반이 없으면 위반이 없다는 것을 명시적으로 보고한다(확인 없이 그냥 통과라고 말하지 않는다).",
      "files": []
    }
  ]
}
```

- [ ] **Step 3: 파일 검증**

```bash
node -e "JSON.parse(require('fs').readFileSync('product-service/.claude/skills/verify-rules/evals/evals.json','utf8')); console.log('valid json')"
```

Expected: `valid json` 출력.

- [ ] **Step 4: 커밋**

```bash
git add product-service/.claude/skills/verify-rules/
git commit -m "feat: verify-rules skill 추가"
```

---

### Task 9: sync-product-docs skill 작성

**Files:**
- Create: `product-service/.claude/skills/sync-product-docs/SKILL.md`
- Create: `product-service/.claude/skills/sync-product-docs/evals/evals.json`

**Interfaces:**
- Produces: docs 동기화 결과 — Task 10(agents/rule-checker)이 이 결과를 종합 게이트의 2번 조건으로 사용한다.

- [ ] **Step 1: SKILL.md 작성**

```markdown
---
name: sync-product-docs
description: product-service의 controller/DTO, ProductErrorCode, @Entity, 도메인 모델 변경을 루트 docs(api-spec/product.md, error-codes.md의 PRODUCT 섹션, erd/schema.md의 Product Service 섹션, domain-glossary/product.md)와 대조하고 사용자 승인 하에 동기화한다. create-github-pr 실행 전 항상 먼저 호출된다.
---

# Sync Product Docs Skill

## 대상 문서 매핑

| 코드 변경 유형 | 대조 대상 | 비고 |
|---|---|---|
| controller/DTO 변경 | `docs/api-spec/product.md` | 전체 파일이 product 전용 |
| `ProductErrorCode` 신규/변경 | `docs/error-codes.md`의 "## 상품 (PRODUCT)" 섹션만 | 다른 서비스 섹션 절대 수정 금지 |
| `@Entity` 필드 변경 | `docs/erd/schema.md`의 "## Product Service" 섹션만 | 다른 서비스 섹션 절대 수정 금지 |
| 도메인 모델/상태(enum 등) 변경 | `docs/domain-glossary/product.md` | 전체 파일이 product 전용 |

## 절차

1. 이번 브랜치의 변경 파일 중 위 4가지 유형에 해당하는 변경을 찾는다.

```bash
git diff develop...HEAD --name-only -- product-service/src/main/java
```

2. 각 변경을 대응하는 문서(해당 섹션)와 대조한다.
3. 불일치를 발견하면 목록으로 사용자에게 보고한다. 코드가 맞고 문서가 틀렸는지, 문서가
   맞고 코드가 틀렸는지 판단 근거를 함께 제시한다.
4. **사용자 승인을 받은 항목만** 문서를 수정한다. 승인 없이 자동으로 고치지 않는다.
5. 코드가 틀린 경우(문서가 맞음)로 판단되면 문서는 건드리지 않고 코드 수정이 필요하다고만
   보고한다(자동 코드 수정 안 함).

## 금지 사항

- `docs/error-codes.md`, `docs/erd/schema.md`에서 product-service 소관 섹션 외의 다른 서비스
  섹션에는 어떤 diff도 만들지 않는다.
- 사용자 승인 없이 `docs/` 파일을 커밋하지 않는다.
```

- [ ] **Step 2: evals.json 작성**

```json
{
  "skill_name": "sync-product-docs",
  "evals": [
    {
      "id": 0,
      "prompt": "ProductDetailResponse에 sellerProfileImageUrl, sellerProductCount 필드가 있는데 docs/api-spec/product.md 상품 상세 응답 예시에는 없어. docs 동기화해줘.",
      "expected_output": "불일치를 보고하고(코드가 맞고 문서가 누락됨), 사용자 승인을 받은 뒤에만 docs/api-spec/product.md를 수정한다. 승인 전에는 파일을 고치지 않는다.",
      "files": []
    },
    {
      "id": 1,
      "prompt": "ProductErrorCode에 PRODUCT_ALREADY_DELETED를 새로 추가했어. docs 동기화해줘.",
      "expected_output": "docs/error-codes.md의 '## 상품 (PRODUCT)' 섹션에만 반영을 제안한다. 다른 서비스(ORDER/PAYMENT 등) 섹션은 절대 건드리지 않는다.",
      "files": []
    },
    {
      "id": 2,
      "prompt": "docs/error-codes.md의 ORDER 섹션에 있는 에러코드 설명이 오래된 것 같은데 이것도 같이 고쳐줘.",
      "expected_output": "이 skill은 product-service 소관 섹션(PRODUCT)만 다루도록 설계되어 있으므로 ORDER 섹션 수정은 범위 밖이라고 안내하고 수정하지 않는다.",
      "files": []
    }
  ]
}
```

- [ ] **Step 3: 파일 검증**

```bash
node -e "JSON.parse(require('fs').readFileSync('product-service/.claude/skills/sync-product-docs/evals/evals.json','utf8')); console.log('valid json')"
```

Expected: `valid json` 출력.

- [ ] **Step 4: 커밋**

```bash
git add product-service/.claude/skills/sync-product-docs/
git commit -m "feat: sync-product-docs skill 추가"
```

---

### Task 10: agents/rule-checker 작성

**Files:**
- Create: `product-service/.claude/agents/rule-checker.md`

**Interfaces:**
- Consumes: Task 8(verify-rules), Task 9(sync-product-docs)의 실행 결과, Task 5(commit)의 이슈/브랜치 확인 로직.
- Produces: PR 생성 가능 여부 최종 판정 — Task 6(create-github-pr)이 생성 직전 이 게이트를 호출한다.

- [ ] **Step 1: rule-checker.md 작성**

```markdown
---
name: rule-checker
description: PR 생성 직전 verify-rules, sync-product-docs, 모듈 경계, 이슈/브랜치 확인 4가지를 종합해 최종 게이트 역할을 하는 서브에이전트. create-github-pr이 실제 생성 단계로 가기 전에 호출한다.
---

# Rule Checker Agent

product-service PR 생성 직전 아래 4가지를 순서대로 확인하고, 하나라도 실패하면 PR 생성을
막고 실패 목록을 보고한다.

## 1. verify-rules 결과

`verify-rules` skill을 실행한 결과에 위반이 하나라도 있으면 실패로 판정한다.

## 2. sync-product-docs 결과

`sync-product-docs` skill 실행 결과, product-service 소관 섹션 외 다른 서비스 섹션에 diff가
있으면 실패로 판정한다.

## 3. 모듈 경계 위반 검사

```bash
git diff develop...HEAD --name-only
```

`product-service/`, 승인된 범위 내 `docs/` 이외의 경로에 변경이 있으면 실패로 판정하고
목록을 보여준다. 사용자가 그 자리에서 "진행해도 된다"고 명시적으로 확인해야만 예외로
통과시킨다. 자동으로 과거 승인 여부를 추적하지 않는다.

## 4. 이슈/브랜치 확인

```bash
git branch --show-current
```

`develop`/`main`이거나 브랜치명의 이슈 번호가 실제 존재하지 않으면 실패로 판정한다.

## 종합 판정

4가지가 모두 통과해야 "PR 생성 가능"으로 보고한다. 하나라도 실패하면 실패 항목과 이유를
목록으로 보여주고 PR 생성을 진행하지 않는다.
```

- [ ] **Step 2: 파일 검증**

```bash
cat product-service/.claude/agents/rule-checker.md | head -5
```

Expected: frontmatter(name/description)가 올바르게 출력됨.

- [ ] **Step 3: 커밋**

```bash
git add product-service/.claude/agents/rule-checker.md
git commit -m "feat: agents/rule-checker 추가 (PR 전 4가지 게이트 종합)"
```

---

### Task 11: CLAUDE.md 라우터 갱신

**Files:**
- Modify: `product-service/CLAUDE.md`

**Interfaces:**
- Consumes: Task 1~10에서 만든 모든 rules/skills/agents 경로.

- [ ] **Step 1: CLAUDE.md 전체 교체**

`product-service/CLAUDE.md` 전체를 아래 내용으로 교체한다.

```markdown
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

트러블슈팅을 포트폴리오에 기록하고 싶으면 `.claude/skills/publish-troubleshooting/SKILL.md`를
명시적으로 요청한다(자동으로 트리거되지 않음).

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
```

- [ ] **Step 2: 파일 검증**

```bash
cat product-service/CLAUDE.md
```

Expected: 위 내용이 그대로 저장되어 있고, 참조하는 모든 경로(`.claude/rules/*.md`,
`.claude/skills/*/SKILL.md`)가 실제로 존재함.

```bash
for f in architecture.md product-api.md testing.md git-workflow.md; do test -f "product-service/.claude/rules/$f" && echo "OK: $f" || echo "MISSING: $f"; done
for d in create-github-issue create-branch commit create-github-pr write-tests verify-rules sync-product-docs; do test -f "product-service/.claude/skills/$d/SKILL.md" && echo "OK: $d" || echo "MISSING: $d"; done
```

Expected: 모두 `OK`.

- [ ] **Step 3: 커밋**

```bash
git add product-service/CLAUDE.md
git commit -m "docs: product-service CLAUDE.md를 새 skill/rules 구조에 맞춰 갱신"
```

---

### Task 12: docs/api-spec/product.md 드리프트 수정

**Files:**
- Modify: `docs/api-spec/product.md`

**Interfaces:**
- Consumes: `product-service/src/main/java/com/prompthub/product/presentation/dto/response/ProductDetailResponse.java`의 실제 필드 (이미 구현되어 있음).

- [ ] **Step 1: 상품 상세 조회 응답 예시에 누락 필드 추가**

`docs/api-spec/product.md`의 "### GET /products/{productId} — 상품 상세 조회" 섹션의 JSON
예시에서 `"sellerId": "uuid",` 다음 줄에 아래 두 필드를 추가한다.

```json
    "sellerId": "uuid",
    "sellerProfileImageUrl": "https://...",
    "sellerProductCount": 12,
    "badge": "신규",
```

- [ ] **Step 2: 변경 확인**

```bash
grep -A 3 '"sellerId": "uuid"' docs/api-spec/product.md
```

Expected: `sellerProfileImageUrl`, `sellerProductCount`가 상품 상세 조회 응답 예시에 포함됨.

- [ ] **Step 3: 커밋**

```bash
git add docs/api-spec/product.md
git commit -m "docs: 상품 상세 조회 API 응답에 누락된 sellerProfileImageUrl, sellerProductCount 필드 반영"
```

---

## Self-Review Notes

- **Spec coverage**: `skill-redesign.md`의 다음 단계 8개 항목(4개 skill 제거, 6개 신규 작성,
  sync-product-docs, agents/rule-checker, rules 수정, CLAUDE.md 갱신, evals 7개, docs 드리프트
  수정)이 Task 1~12에 모두 매핑됨.
- **Placeholder scan**: 모든 Step에 실제 파일 전체 내용을 포함함. "TBD"/"나중에" 없음.
- **Type consistency**: 7개 skill이 모두 동일한 frontmatter 형식(name/description)을 쓰고,
  CLAUDE.md의 skill 경로 목록이 Task 3~9에서 실제로 만든 디렉터리명과 정확히 일치함
  (`create-github-issue`, `create-branch`, `commit`, `create-github-pr`, `write-tests`,
  `verify-rules`, `sync-product-docs`).
