# Codex Skills and Module AGENTS Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** 저장소 공용 Codex 스킬과 모듈 전용 지침을 공식 탐색 경로에 배치하고, 개인 담당 범위 없이 경로 기준으로 적용되게 한다.

**Architecture:** 저장소 전체에서 재사용하는 Git·GitHub·검증 스킬 5개는 루트 .agents/skills/에 두고, 정산 도메인에만 필요한 테스트 우선 스킬은 settlement-service/.agents/skills/에 둔다. 루트 AGENTS.md는 공용 규칙과 경로 라우팅만 담당하고, 정산·유저·Admin 정산 패키지는 가장 가까운 AGENTS.md에서 기술·테스트 규칙을 추가한다.

**Tech Stack:** Markdown, Codex AGENTS.md, Codex SKILL.md, YAML, Git, Gradle

## Global Constraints

- Codex 관련 파일만 생성·수정·이동한다.
- 루트 및 서비스별 CLAUDE.md와 .claude/**는 수정하지 않는다.
- 공유 AGENTS.md와 공용 스킬에 개인 담당 범위나 특정 작업자의 수정 권한을 기록하지 않는다.
- order-service/AGENTS.md는 수정하지 않는다.
- 애플리케이션 코드, 테스트 코드, 빌드 설정은 수정하지 않는다.
- 같은 name을 가진 스킬 사본을 기존 settlement-service/.codex/skills/에 남기지 않는다.
- 기존 사용자 변경을 stage하거나 되돌리지 않으며, 이 격리 작업트리의 계획 대상 파일만 경로를 명시해 stage한다.

---

## File Structure

### Repository-wide skills

- .agents/skills/commit-project-changes/: 저장소 전체 변경 집합을 안전하게 커밋하는 절차와 UI 메타데이터
- .agents/skills/create-project-branch/: 저장소의 임의 경로 작업을 최신 base에서 분기하는 절차와 UI 메타데이터
- .agents/skills/create-project-issue/: 현재 GitHub 템플릿과 메타데이터를 사용한 이슈 생성 절차와 UI 메타데이터
- .agents/skills/create-project-pr/: 전체 diff 검증 뒤 PR을 생성하거나 갱신하는 절차와 UI 메타데이터
- .agents/skills/verify-project-changes/: 전체 변경 파일을 경로별 AGENTS.md와 일반 검토에 매핑하는 절차와 UI 메타데이터

각 디렉터리는 SKILL.md와 agents/openai.yaml 두 파일만 갖는다.

### Settlement-only skill

- settlement-service/.agents/skills/test-settlement-first/SKILL.md: 정산 핵심 규칙의 RED→GREEN 구현 절차
- settlement-service/.agents/skills/test-settlement-first/agents/openai.yaml: 정산 테스트 우선 스킬 UI 메타데이터

### Instruction hierarchy

- AGENTS.md: 저장소 전체 안전·검증·공용 스킬·하위 지침 라우팅
- settlement-service/AGENTS.md: 정산 모듈 경계, 아키텍처, 계약, 테스트 우선 규칙
- user-service/AGENTS.md: 유저 모듈 아키텍처, 계약, API·문서 동기화, 검증 규칙
- admin-service/src/main/java/com/prompthub/admin/settlement/AGENTS.md: Admin 정산 운영 코드의 3-tier·인증·상태·합계 규칙
- admin-service/src/test/java/com/prompthub/admin/settlement/AGENTS.md: Admin 정산 테스트의 계층별 범위와 H2 Repository 검증 규칙

### Removed legacy locations

settlement-service/.codex/skills/ 아래의 기존 6개 스킬 디렉터리를 모두 제거한다. 다른 .codex 또는 .claude 경로는 건드리지 않는다.

---

### Task 1: Move and generalize the five repository-wide skills

**Files:**
- Create: .agents/skills/commit-project-changes/SKILL.md
- Create: .agents/skills/commit-project-changes/agents/openai.yaml
- Create: .agents/skills/create-project-branch/SKILL.md
- Create: .agents/skills/create-project-branch/agents/openai.yaml
- Create: .agents/skills/create-project-issue/SKILL.md
- Create: .agents/skills/create-project-issue/agents/openai.yaml
- Create: .agents/skills/create-project-pr/SKILL.md
- Create: .agents/skills/create-project-pr/agents/openai.yaml
- Create: .agents/skills/verify-project-changes/SKILL.md
- Create: .agents/skills/verify-project-changes/agents/openai.yaml
- Delete: settlement-service/.codex/skills/commit-project-changes/**
- Delete: settlement-service/.codex/skills/create-project-branch/**
- Delete: settlement-service/.codex/skills/create-project-issue/**
- Delete: settlement-service/.codex/skills/create-project-pr/**
- Delete: settlement-service/.codex/skills/verify-project-changes/**

**Interfaces:**
- Consumes: 저장소 루트와 대상 경로에 적용되는 AGENTS.md, 현재 Git 상태, 현재 checkout의 GitHub 템플릿
- Produces: 저장소 어느 경로에서도 이름으로 발견되는 공용 스킬 5개

- [ ] **Step 1: Recreate the five skill directories under the official root path**

apply_patch로 기존 5개 스킬의 SKILL.md와 agents/openai.yaml을 각각의 루트 공용 스킬 디렉터리 아래에 추가하고, 동일한 기존 파일을 삭제한다. 이슈·PR 스킬의 승인 경계와 실제 GitHub 메타데이터 조회 절차는 그대로 보존한다.

Expected paths:

~~~text
.agents/skills/commit-project-changes/{SKILL.md,agents/openai.yaml}
.agents/skills/create-project-branch/{SKILL.md,agents/openai.yaml}
.agents/skills/create-project-issue/{SKILL.md,agents/openai.yaml}
.agents/skills/create-project-pr/{SKILL.md,agents/openai.yaml}
.agents/skills/verify-project-changes/{SKILL.md,agents/openai.yaml}
~~~

- [ ] **Step 2: Generalize commit-project-changes**

SKILL.md의 frontmatter와 본문을 다음 계약으로 바꾼다.

~~~text
name: commit-project-changes
description: 현재 저장소의 요청된 변경을 분석하고 적용되는 AGENTS.md와 Git 규칙에 맞춰 안전하게 stage·commit할 때 사용한다.
scope: 저장소 전체. 특정 서비스 또는 개인 담당 경로를 하드코딩하지 않는다.
rules: 전체 status/diff 확인 → 대상 경로 AGENTS.md 확인 → 정확한 파일만 stage → staged diff 재검증 → 커밋.
prohibited: 관련 없는 변경 stage, 임의 stash/reset, hook 우회, Co-Authored-By 추가, 별도 요청 없는 push/PR.
~~~

agents/openai.yaml을 다음과 같이 맞춘다.

~~~yaml
interface:
  display_name: "Commit Project Changes"
  short_description: "요청된 변경을 경로별 규칙에 맞춰 안전하게 커밋"
  default_prompt: "Use $commit-project-changes to inspect and commit the requested repository changes safely."
policy:
  allow_implicit_invocation: true
~~~

- [ ] **Step 3: Generalize create-project-branch**

SKILL.md에서 settlement/user/admin 정산 경로 제한을 제거하고 다음 계약을 명시한다.

~~~text
scope: 저장소 전체의 이슈 또는 작업 설명.
context: 대상 경로에 적용되는 AGENTS.md와 현재 Git 규칙만 읽는다.
dirty tree: 미커밋 변경을 새 브랜치에 가져갈지가 불명확하면 생성 전에 질문한다.
base: 사용자가 지정한 base를 우선하고, 지정하지 않으면 저장소 규칙과 remote 상태로 결정한다.
mutation: 같은 이름 덮어쓰기·stash·reset·commit·push는 별도 승인 없이 하지 않는다.
naming: /codex 또는 codex/를 강제하지 않고 저장소 Git 규칙과 사용자 지정 이름을 우선한다.
~~~

agents/openai.yaml을 다음과 같이 맞춘다.

~~~yaml
interface:
  display_name: "Create Project Branch"
  short_description: "저장소 작업 브랜치를 규칙에 맞춰 안전하게 생성"
  default_prompt: "Use $create-project-branch to create a repository branch for this issue or task."
policy:
  allow_implicit_invocation: true
~~~

- [ ] **Step 4: Make verification Codex-instruction based**

verify-project-changes/SKILL.md의 규칙 탐색 계약을 아래처럼 통일한다. CLAUDE.md나 .claude/**를 필수 입력으로 요구하지 않는다.

~~~text
각 manifest 경로에 대해 저장소 루트부터 가장 가까운 상위 디렉터리까지 적용되는 AGENTS.md를 완전히 읽는다.
AGENTS.md가 참조하는 Codex 지침과 현재 checkout의 GitHub 템플릿만 추가로 읽는다.
규칙이 없는 경로도 general-code-review에 배정한다.
~~~

create-project-pr/SKILL.md의 REQUIRED SUB-SKILL: verify-project-changes 이름과 전체 diff 검증 게이트는 유지한다. create-project-issue, create-project-pr, verify-project-changes의 agents/openai.yaml은 기존 이름과 기본 프롬프트를 유지하되 이동된 SKILL.md와 모순이 없는지 대조한다.

- [ ] **Step 5: Validate Task 1 structure and remove duplicate copies**

Run:

~~~bash
find .agents/skills -type f | sort
find settlement-service/.codex/skills -type f | sort
rg -n '담당 범위|settlement-service/\.codex/skills|CLAUDE\.md|\.claude/' .agents/skills
~~~

Expected:

~~~text
루트 공용 스킬의 파일은 10개다.
기존 경로에는 test-settlement-first의 두 파일만 남는다.
공용 스킬에 개인 담당 범위, 기존 스킬 경로, Claude 필수 참조가 없다.
~~~

- [ ] **Step 6: Commit the repository-wide skills**

~~~bash
git add -A -- .agents/skills settlement-service/.codex/skills/commit-project-changes settlement-service/.codex/skills/create-project-branch settlement-service/.codex/skills/create-project-issue settlement-service/.codex/skills/create-project-pr settlement-service/.codex/skills/verify-project-changes
git diff --cached --check
git commit -m "chore: Codex 공용 스킬을 루트로 이동"
~~~

### Task 2: Move and update the settlement-only test-first skill

**Files:**
- Create: settlement-service/.agents/skills/test-settlement-first/SKILL.md
- Create: settlement-service/.agents/skills/test-settlement-first/agents/openai.yaml
- Delete: settlement-service/.codex/skills/test-settlement-first/SKILL.md
- Delete: settlement-service/.codex/skills/test-settlement-first/agents/openai.yaml

**Interfaces:**
- Consumes: 루트 AGENTS.md, settlement-service/AGENTS.md, 정산 규칙 변경 요청
- Produces: 정산 모듈 경로에서만 발견되는 test-settlement-first 스킬

- [ ] **Step 1: Move the settlement skill to the module official path**

apply_patch로 두 파일을 settlement-service/.agents/skills/test-settlement-first/에 추가하고 기존 .codex/skills/test-settlement-first/ 파일을 삭제한다.

- [ ] **Step 2: Replace the prerequisite and preserve RED→GREEN behavior**

SKILL.md의 첫 단계와 적용 경계를 다음 내용으로 바꾼다.

~~~text
1. 저장소 루트 AGENTS.md와 settlement-service/AGENTS.md를 완전히 읽는다.
2. 정산 계산, 상태 전이, 중복, 권한, 예외, 금액 규칙의 성공·실패·경계를 정리한다.
3. Domain → Application Service 순으로 실패 테스트를 작성한다.
4. 변경 규칙에 대응하는 테스트 클래스의 FQCN을 정하고, 예를 들어 ./gradlew :settlement-service:test --tests "com.prompthub.settlement.application.service.SettlementCalculationApplicationServiceTest"로 RED를 확인한다.
5. 최소 구현으로 GREEN을 만들고 규칙별로 반복한다.
6. 리팩터링 후 ./gradlew :settlement-service:test를 실행한다.
~~~

다음 세부 규칙은 유지한다.

~~~text
Domain 객체는 mock하지 않는다.
Application Service는 Repository·외부 Client·Publisher만 mock한다.
JUnit 5, AssertJ, Given/When/Then, 한국어 @DisplayName을 사용한다.
BigDecimal은 값 비교가 필요하면 isEqualByComparingTo를 사용한다.
버그 수정은 재현 테스트의 RED를 먼저 확인한다.
DB·HTTP·Security·Batch·Transaction 검증은 통합 테스트로 분리한다.
~~~

- [ ] **Step 3: Align the skill metadata**

~~~yaml
interface:
  display_name: "Test Settlement First"
  short_description: "정산 핵심 비즈니스 로직을 실패 단위 테스트부터 구현"
  default_prompt: "Use $test-settlement-first to define failing tests before implementing this settlement behavior."
policy:
  allow_implicit_invocation: true
~~~

- [ ] **Step 4: Verify there is no legacy skill tree**

Run:

~~~bash
find settlement-service/.agents/skills -type f | sort
test ! -e settlement-service/.codex/skills
~~~

Expected: 정산 전용 스킬 파일 2개가 새 경로에 있고 settlement-service/.codex/skills는 존재하지 않는다.

- [ ] **Step 5: Commit the settlement-only skill**

~~~bash
git add -A -- settlement-service/.agents/skills settlement-service/.codex/skills/test-settlement-first
git diff --cached --check
git commit -m "chore: 정산 테스트 우선 스킬 경로 정리"
~~~

### Task 3: Rewrite root Codex routing

**Files:**
- Modify: AGENTS.md

**Interfaces:**
- Consumes: 루트 공용 스킬 5개와 현재 존재하거나 이번 작업에서 추가하는 하위 AGENTS.md
- Produces: 저장소 루트에서 시작한 Codex가 대상 경로별 지침과 스킬을 명시적으로 찾는 라우팅 규칙

- [ ] **Step 1: Replace Claude-document routing with Codex path routing**

루트 AGENTS.md를 다음 섹션 책임으로 재작성한다.

~~~text
# PromptHub BE — Codex 루트 지침
적용 우선순위
전역 안전 규칙
작업 범위와 경로별 AGENTS.md 로드
공용 Codex 스킬 라우팅
빌드와 검증
공유 문서 위치
~~~

CLAUDE.md를 보조 문서로 읽으라는 문구와 서비스별 CLAUDE.md 표는 제거한다. 대신 다음 경로 라우팅 표를 포함한다.

~~~text
settlement-service/** → settlement-service/AGENTS.md
user-service/** → user-service/AGENTS.md
order-service/** → order-service/AGENTS.md
admin-service/src/main/java/com/prompthub/admin/settlement/** → 해당 경로 AGENTS.md
admin-service/src/test/java/com/prompthub/admin/settlement/** → 해당 test AGENTS.md와 대응 main AGENTS.md
그 밖의 경로 → 더 가까운 AGENTS.md가 있으면 적용하고, 없으면 루트 AGENTS.md만 적용
~~~

- [ ] **Step 2: Add the repository-wide skill routing table**

~~~text
커밋 요청 → commit-project-changes
작업 브랜치 생성 → create-project-branch
GitHub 이슈 생성 → create-project-issue
PR 생성 또는 갱신 → create-project-pr
커밋·PR 전 전체 diff 검증 또는 코드 리뷰 → verify-project-changes
~~~

스킬 본문은 복사하지 않고 .agents/skills/commit-project-changes/SKILL.md를 비롯한 각 공용 스킬의 SKILL.md가 단일 진실 공급원임을 명시한다. 요청 경로나 규칙 적용이 애매하면 변경 전에 질문하고 개인 담당 범위를 추론하지 않는다고 적는다.

- [ ] **Step 3: Preserve neutral build and safety rules**

~~~text
비밀정보를 출력하거나 저장소에 복사하지 않는다.
관련 없는 사용자 변경을 수정·되돌림·stage하지 않는다.
서비스 변경은 실제 대상 서비스의 Gradle test task로 검증한다. 예를 들어 payment-service 변경은 ./gradlew :payment-service:test를 사용하고, 다중 모듈 변경은 각 대상 모듈과 필요 시 ./gradlew test로 검증한다.
문서·지침·스킬만 바꾸면 구조 검사, skill validation, git diff --check를 수행한다.
실행하지 못한 검증을 성공으로 보고하지 않는다.
~~~

- [ ] **Step 4: Verify and commit root routing**

Run:

~~~bash
rg -n 'CLAUDE\.md|\.claude/|담당 범위|\.codex/skills' AGENTS.md
git diff --check -- AGENTS.md
~~~

Expected: 검색 결과가 없고 whitespace 오류가 없다.

~~~bash
git add AGENTS.md
git commit -m "docs: Codex 경로별 라우팅 정리"
~~~

### Task 4: Add neutral module and package instructions

**Files:**
- Modify: settlement-service/AGENTS.md
- Create: user-service/AGENTS.md
- Create: admin-service/src/main/java/com/prompthub/admin/settlement/AGENTS.md
- Create: admin-service/src/test/java/com/prompthub/admin/settlement/AGENTS.md

**Interfaces:**
- Consumes: 루트 AGENTS.md, 실제 정산·유저·Admin 정산 패키지 구조
- Produces: 경로에 가까운 기술·테스트 지침 4개

- [ ] **Step 1: Rewrite settlement instructions as module rules**

settlement-service/AGENTS.md에서 소통 방식, 개인 담당 범위, 다른 모듈 쓰기 권한, 기존 .codex 산출물 위치, Claude 필수 로드 섹션을 제거한다. 다음 기술 규칙을 포함한다.

~~~text
적용 범위: settlement-service/**.
의존 방향: domain은 프레임워크와 infrastructure를 모르고, application은 use case와 port를 정의하며, infrastructure와 presentation이 바깥쪽에서 연결한다.
계약: 다른 서비스 DB나 내부 구현에 직접 의존하지 않고 grpc/, common-module/, docs/api-spec/, 이벤트 계약을 사용한다.
웹: Gateway가 JWT를 검증하고 X-User-Id와 X-User-Role을 주입하므로 서비스는 전달된 헤더의 형식·권한만 검증한다.
변경 동기화: API, 예외, 상태, 금액, 이벤트 계약 변경 시 대응 테스트와 관련 문서를 함께 확인한다.
테스트 우선: 계산·상태 전이·중복·권한·예외·금액 규칙은 test-settlement-first를 사용한다.
검증: 대상 테스트 클래스 → ./gradlew :settlement-service:test 순서로 실행한다.
스킬 위치: settlement-service/.agents/skills/test-settlement-first/SKILL.md.
~~~

- [ ] **Step 2: Create user module instructions**

user-service/AGENTS.md에 다음 내용을 작성한다.

~~~text
적용 범위: user-service/**.
도메인: auth, user, seller, sellersettlement, wishlist 경계를 유지한다.
아키텍처: domain → application → infrastructure/presentation 의존 방향을 지키고 domain이 Spring/JPA/Web 구현을 알지 않게 한다.
서비스 경계: 다른 서비스 DB나 내부 클래스를 직접 참조하지 않고 gRPC·이벤트·공용 계약을 사용한다.
인증: Gateway가 검증해 전달한 사용자 헤더와 user-service가 소유한 인증·토큰 책임을 구분한다.
동기화: API DTO·에러·인증·판매자 정산 계약 변경 시 테스트, OpenAPI/API 명세, gRPC 계약을 실제 영향에 맞춰 확인한다.
검증: 대상 테스트 클래스와 ./gradlew :user-service:test.
공용 작업: 커밋·브랜치·이슈·PR·전체 검증은 루트 공용 스킬 이름만 참조한다.
~~~

- [ ] **Step 3: Create Admin settlement main package instructions**

admin-service/src/main/java/com/prompthub/admin/settlement/AGENTS.md에 다음 내용을 작성한다.

~~~text
적용 범위: 현재 admin.settlement main 패키지와 하위 패키지뿐이다.
3-tier: Controller는 요청·헤더 검증과 응답 변환 후 Service에 위임하고, Service는 트랜잭션·상태 전이·합계·유스케이스를 조정하며, Repository는 영속화와 조회만 담당한다.
인증: JWT 검증은 Gateway 책임이며 X-User-Id와 X-User-Role의 형식·관리자 권한만 경계에서 확인한다.
의존: 다른 서비스 DB와 내부 코드에 직접 의존하지 않고 공개 API·이벤트·공용 계약을 사용한다.
동기화: API 응답, 표시 상태, 지급·취소 전이, 합계·집계, 재전송 Job 규칙 변경은 대응 테스트와 함께 수정한다.
검증: 대상 테스트 클래스 후 ./gradlew :admin-service:test.
범위 제한: admin-service의 다른 패키지에 대한 수정 권한을 이 문서가 부여하지 않는다.
~~~

- [ ] **Step 4: Create Admin settlement test package instructions**

admin-service/src/test/java/com/prompthub/admin/settlement/AGENTS.md에 다음 내용을 작성한다.

~~~text
적용 범위: 현재 admin.settlement test 패키지와 하위 패키지뿐이다.
main 연계: 대응 main 패키지 AGENTS.md도 함께 적용한다.
계층별 검증: Controller는 요청·헤더·상태·응답 매핑, Service는 상태 전이·합계·예외·협력 호출, Repository는 쿼리·스키마·집계를 검증한다.
Repository: 외부 PostgreSQL에 의존하지 않고 현재 테스트 설정의 H2 기반 Repository 테스트를 유지한다.
회귀: 버그 수정은 재현 테스트의 실패를 먼저 확인하고 최소 구현 뒤 통과시킨다.
검증: 영향받은 테스트를 먼저 실행한다. 예를 들어 Service 규칙은 ./gradlew :admin-service:test --tests "com.prompthub.admin.settlement.service.SettlementServiceTest"로 확인한 뒤 ./gradlew :admin-service:test를 실행한다.
~~~

- [ ] **Step 5: Check module instructions for personal or Claude-specific scope**

Run:

~~~bash
rg -n '담당 범위|특정 작업자|CLAUDE\.md|\.claude/|\.codex/skills' settlement-service/AGENTS.md user-service/AGENTS.md admin-service/src/main/java/com/prompthub/admin/settlement/AGENTS.md admin-service/src/test/java/com/prompthub/admin/settlement/AGENTS.md
git diff --check -- settlement-service/AGENTS.md user-service/AGENTS.md admin-service/src/main/java/com/prompthub/admin/settlement/AGENTS.md admin-service/src/test/java/com/prompthub/admin/settlement/AGENTS.md
~~~

Expected: 금지된 기존 경로나 개인 지침이 없고 whitespace 오류가 없다.

- [ ] **Step 6: Commit the module instructions**

~~~bash
git add settlement-service/AGENTS.md user-service/AGENTS.md admin-service/src/main/java/com/prompthub/admin/settlement/AGENTS.md admin-service/src/test/java/com/prompthub/admin/settlement/AGENTS.md
git commit -m "docs: 서비스별 Codex 지침 추가"
~~~

### Task 5: Validate the complete Codex instruction layout

**Files:**
- Verify: AGENTS.md
- Verify: .agents/skills/**
- Verify: settlement-service/AGENTS.md
- Verify: settlement-service/.agents/skills/**
- Verify: user-service/AGENTS.md
- Verify: admin-service/src/main/java/com/prompthub/admin/settlement/AGENTS.md
- Verify: admin-service/src/test/java/com/prompthub/admin/settlement/AGENTS.md

**Interfaces:**
- Consumes: Tasks 1–4의 모든 Codex 지침과 스킬
- Produces: 중복·낡은 참조·메타데이터 오류가 없는 최종 검증 증거

- [ ] **Step 1: Validate all six skills with Skill Creator**

Run each command from the repository root:

~~~bash
python3 /Users/taetaetae/.codex/skills/.system/skill-creator/scripts/quick_validate.py .agents/skills/commit-project-changes
python3 /Users/taetaetae/.codex/skills/.system/skill-creator/scripts/quick_validate.py .agents/skills/create-project-branch
python3 /Users/taetaetae/.codex/skills/.system/skill-creator/scripts/quick_validate.py .agents/skills/create-project-issue
python3 /Users/taetaetae/.codex/skills/.system/skill-creator/scripts/quick_validate.py .agents/skills/create-project-pr
python3 /Users/taetaetae/.codex/skills/.system/skill-creator/scripts/quick_validate.py .agents/skills/verify-project-changes
python3 /Users/taetaetae/.codex/skills/.system/skill-creator/scripts/quick_validate.py settlement-service/.agents/skills/test-settlement-first
~~~

Expected for every command:

~~~text
Skill is valid!
~~~

- [ ] **Step 2: Verify discovery layout and uniqueness**

Run:

~~~bash
find .agents/skills settlement-service/.agents/skills -type f | sort
test ! -e settlement-service/.codex/skills
rg -n '^name:' .agents/skills/*/SKILL.md settlement-service/.agents/skills/*/SKILL.md
~~~

Expected: 공용 스킬 5개와 정산 전용 스킬 1개의 name이 각각 한 번만 나타나고, 각 스킬에는 SKILL.md와 agents/openai.yaml만 존재한다.

- [ ] **Step 3: Scan all changed Codex files for stale references**

Run:

~~~bash
rg -n 'settlement-service/\.codex/skills|\.codex/skills|CLAUDE\.md|\.claude/|담당 범위' AGENTS.md .agents/skills settlement-service/AGENTS.md settlement-service/.agents/skills user-service/AGENTS.md admin-service/src/main/java/com/prompthub/admin/settlement/AGENTS.md admin-service/src/test/java/com/prompthub/admin/settlement/AGENTS.md
~~~

Expected: 결과가 없다.

- [ ] **Step 4: Verify the exact changed-file boundary**

Run:

~~~bash
git status --short
git diff --check origin/develop...HEAD
git diff --name-status origin/develop...HEAD
~~~

Expected: 설계·계획 문서, 루트 및 대상 모듈 AGENTS.md, 새 .agents/skills/**, 삭제된 settlement-service/.codex/skills/**만 나타난다. order-service/AGENTS.md, 모든 CLAUDE.md, .claude/**, 애플리케이션 코드, 테스트 코드, 빌드 파일은 나타나지 않는다.

- [ ] **Step 5: Review final content and record verification scope**

Run:

~~~bash
git diff --stat origin/develop...HEAD
git log --oneline origin/develop..HEAD
~~~

문서·Codex 설정만 변경했으므로 Gradle 재실행은 필수 검증에서 제외한다. PR에는 이 브랜치에서 앞서 통과한 ./gradlew test 결과와 이번 구조·skill validation 결과를 구분해 기록하며, 이번 변경 뒤 Gradle을 새로 실행한 것처럼 표현하지 않는다.

- [ ] **Step 6: Stop on validation failure or record the clean result**

검증 하나라도 실패하면 완료로 보고하지 않고 실패한 Task로 돌아가 해당 Task의 파일과 검증 단계를 다시 수행한다. 모두 통과하면 작업트리가 깨끗한지 확인하고, 실행한 명령과 실제 결과를 완료 보고 및 PR 검증 내역에 기록한다. Task 5 자체는 파일을 변경하거나 별도 커밋을 만들지 않는다.

## Follow-up Amendment: Shared Convention Routing

사용자 확인에 따라 루트 `.claude/rules/**`를 Codex가 변경 유형별로 읽는 팀 공통 컨벤션으로 연결한다. 이 결정은 위 단계의 `.claude/` 참조 금지 검사보다 우선한다.

- 루트 `AGENTS.md`에 security, code-style, clean-architecture, domain-model, controller-exception, swagger, kafka-event, git-convention 라우팅 표를 추가한다.
- commit, branch, PR 스킬은 `git-convention.md`를 읽는다.
- verify 스킬은 전체 manifest를 대상 경로의 `AGENTS.md`와 적용 가능한 공통 규칙에 매핑한다.
- `git-convention.md`의 Claude 전용 trailer 예시는 Codex에 적용하지 않으며 다른 AI trailer로 자동 대체하지 않는다.
- `.claude/rules/**`, 모든 `CLAUDE.md`, 애플리케이션 코드와 `order-service/AGENTS.md`는 수정하지 않는다.
- 검증에서는 `.claude/rules/` 참조가 승인된 루트 지침과 공용 스킬에만 있는지 확인하고, 서비스별 `CLAUDE.md`, 기존 `.codex/skills`, 개인 작업 범위 참조는 계속 금지한다.
