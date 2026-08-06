# 나만의 스킬 생성 구현 계획

30일 사용 패턴 분석에서 확인된 고빈도 반복 자연어 지시를 재사용 가능한 스킬 2개로 전환한다.

---

## 배경 및 목표

`.claude/analysis/claude-code-usage-pattern-analysis.md`, `.claude/analysis/claude-code-usage-qna.md` 분석 결과, 이슈/브랜치/PR 절차는 이미 스킬화되어 잘 작동하는 반면 아래 2가지는 매번 자연어로 반복되고 있었다.

| 반복 패턴 | 빈도 (30일) | 현재 방식 |
|---|---|---|
| "커밋해줘" 계열 커밋 위임 | 118회 | 자연어 |
| `@.claude/plans/...` 참조 후 작업 재개 | 106회 | 자연어 + @참조 |

목표: 위 2개를 스킬로 만들어 (1) 반복 타이핑을 제거하고, (2) 팀 컨벤션 준수를 모델 재량이 아닌 절차로 고정한다.

## 설계 결정

### D1. 대상 스킬 2개와 우선순위

`create-commit` → `resume-plan` 순으로 구현한다. 우선순위는 순전히 반복 빈도 데이터(118 > 106)를 따른다. 새 도구·의존성 없이 커맨드 마크다운 파일만 추가하므로 둘 다 저비용이다.

분석 문서(Q4)에서 후보였던 `debug-log`(로그 파일화·추출 스킬)와 `log-trouble`(트러블슈팅 문서화 스킬)은 사용자 결정으로 이번 범위에서 제외했다.

### D2. 배치 — 2개 모두 프로젝트 커맨드

두 스킬 모두 `payment-service/.claude/commands/`에 둔다. 둘 다 이 프로젝트의 팀 컨벤션(`git-conventions.md`의 커밋 형식, `plan-doc-format.md`의 태스크 문서 구조)에 결합되어 있고, 기존 `create-branch`/`create-issue`/`create-pr`이 같은 위치에 있어 일관된다. 팀원도 같은 스킬을 쓸 수 있다는 부수 이익도 있다.

### D3. `create-commit` — 커밋 절차의 스킬화

118회 반복된 최다 패턴. 이름은 기존 `create-*` 계열과 통일한다. 절차: ① `git status`/`git diff`로 변경 파악 → ② `git-conventions.md` 형식(`type: 한국어 설명`, 필요 시 불릿 본문, AI 기여 시 실제 사용 모델 버전의 `Co-Authored-By` 트레일러, `#이슈번호` 본문 금지)으로 메시지 생성 → ③ 커밋 메시지 초안을 사용자에게 보여주고 확인 후 커밋. 인자로 `--push`를 받으면 커밋 후 푸시까지 수행한다("커밋하고 푸시해" 변형 8회+ 흡수). 확인 단계를 넣는 이유: 커밋 메시지는 팀에 남는 산출물이고, 기존 `create-issue`/`create-pr`도 초안 확인 후 실행하는 패턴이라 UX가 일관된다.

### D4. `resume-plan` — 계획 문서 기반 작업 재개의 정형화

`@.claude/plans/` 참조가 106회로, `/clear`로 세션을 잘게 끊는 사용 습관(304회)과 결합된 핵심 워크플로다. 지금은 매번 "이어서 진행해줘" + 상황 설명을 자연어로 반복한다. 스킬 절차: ① 인자로 계획 문서명(또는 생략 시 `.claude/plans/`에서 최신 `*-tasks.md`)을 받아 읽기 → ② git log·체크박스로 완료/미완료 Task 판별 → ③ "다음 Task: N번 {제목}"을 사용자에게 확인 → ④ 해당 Task부터 실행(Task당 1커밋 규칙 준수). 완료 판별을 체크박스에만 의존하지 않고 git log와 교차 확인하는 이유: 태스크 문서의 체크박스 갱신이 누락되는 경우가 실제로 있었기 때문이다.

## 구현 순서 (Task = 1 커밋)

### Task 1: `create-commit` 커맨드 작성
- Step 1: `.claude/commands/create-commit.md` 작성 (frontmatter `description`/`argument-hint` + D3 절차. 기존 `create-branch.md` 형식 준수)
- Step 2: 실제 변경사항으로 `/create-commit` 호출해 메시지 형식·트레일러 검증
- Step: Commit

### Task 2: `resume-plan` 커맨드 작성
- Step 1: `.claude/commands/resume-plan.md` 작성 (D4 절차)
- Step 2: 기존 `398-refund-flow-redesign-tasks.md`(완료된 계획)로 "모든 Task 완료" 판별이 정확한지 검증
- Step: Commit

## 검증 기준

- 각 스킬을 실제 시나리오로 1회 이상 호출해 산출물(커밋 메시지, Task 판별)이 해당 규칙 문서의 형식과 일치하는지 확인한다.
- 성공 지표(운용 후 측정): "커밋해줘" 자연어 빈도 감소.

## 범위 제외

- `debug-log` 스킬 (로그 파일화·추출) — 사용자 결정으로 제외
- `log-trouble` 스킬 (트러블슈팅 문서화) — 사용자 결정으로 제외. 규칙(`troubleshooting-doc-format.md`)과 실행의 괴리 문제는 분석 문서에 기록만 남김
- AskUserQuestion 반복 선택 기본값의 CLAUDE.md 반영 (Q&A 문서 Q5-4 — 별도 작업)
- 기존 스킬(`create-issue`/`create-branch`/`create-pr`/`grill-me`) 수정 없음
