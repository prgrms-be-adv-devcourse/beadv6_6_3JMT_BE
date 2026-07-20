# Claude Code 설정 파일 개선 사항

**분석일**: 2026-07-19
**분석 대상**: `payment-service/.claude/` 전체(설정 2, 커맨드 6, 규칙 7, 분석 3, 계획 6+아카이브, docs 3) + `CLAUDE.md`
**관련 문서**: [claude-code-usage-pattern-analysis.md](claude-code-usage-pattern-analysis.md), [claude-code-usage-qna.md](claude-code-usage-qna.md)

---

## 총평

전체 구조는 우수하다 — 규칙 분리(`rules/`), git 절차 커맨드화(`commands/`), 계획 문서 체계(`plans/`)가 잘 잡혀 있다. 다만 **실제 동작하지 않는 권한 설정 1건, 팀원 환경(Linux)에서 깨지는 gitignore 1건, 커맨드 간 규칙 충돌 1건**이 발견되었다. 아래 심각도순으로 정리한다.

## 높음 — 실제 오동작

### 1. `settings.json` 권한 규칙이 죽어 있음

- 위치: `.claude/settings.json:4`
- 현재 허용 규칙: `Bash(./gradlew test*)`
- 실제 명령(CLAUDE.md 규정): `../gradlew :payment-service:test` — 모든 gradle 명령을 payment-service 디렉터리에서 `../gradlew`로 실행하므로 `./gradlew` 패턴은 **절대 매칭되지 않는다**.
- 영향: 팀 공유 설정인데 효과 0. 테스트 실행마다 권한 프롬프트 발생.
- 개선: `Bash(../gradlew*)` 계열 패턴으로 수정.

### 2. `settings.local.json` gitignore가 macOS에서만 우연히 작동

- `git check-ignore -v` 결과: **모노레포 루트** `.gitignore`의 `*.LOCAL.*` 패턴이 매칭 — **대문자 패턴이 `core.ignorecase=true`(macOS 기본) 덕분에만** `settings.local.json`과 매칭된다.
- 영향: Linux 환경(CI, 팀원)에서는 매칭되지 않아 개인 권한 설정 파일이 untracked로 노출 — 실수 커밋 위험.
- `scheduled_tasks.lock`도 같은 문제 — 현재 `.git/info/exclude`(작성자 로컬 전용)로만 ignore되어 팀원 clone에는 untracked 노이즈로 등장.
- 개선: 루트 `.gitignore`는 payment-service 수정 범위 밖이므로, **payment-service `.gitignore`**에 `.claude/settings.local.json`, `.claude/scheduled_tasks.lock` 명시 추가.

### 3. `create-pr` ↔ `pre-pr-check` 규칙 충돌 + 복제 드리프트

- 미커밋 변경 발견 시 동작이 서로 다름:
  - `pre-pr-check.md:20` — "경고하고 stash 또는 커밋 후 진행을 **권장**"
  - `create-pr.md:21` — "경고하고 **즉시 중단**"
- 원인: `create-pr.md` 0단계가 pre-pr-check 내용을 **인라인 복사**해 두 사본이 이미 어긋난 상태. 한쪽 수정 시 다른 쪽이 방치되는 구조.
- **개선(확정)**: `pre-pr-check.md`를 **제거**하고 검증 절차를 `create-pr.md`로 단일화한다.
  - 근거: 세션 로그 30일 확인 결과 `/pre-pr-check` **단독 호출 0회** — description부터 "create-pr 실행 시 자동으로 선행"으로, 애초에 create-pr의 서브루틴으로 설계된 파일. 서브루틴을 별도 커맨드로 둘 이유 없음.
  - 참조 방식(create-pr이 pre-pr-check를 읽도록)보다 제거가 나음 — 커맨드 간 간접 참조 한 홉과 드리프트 가능성을 원천 제거.
  - "PR 없이 충돌만 확인" 용도는 실사용 0회, 필요 시 자연어 지시로 충분.
  - 미커밋 변경 정책은 **즉시 중단**으로 통일 — 미커밋 상태로 PR을 만들면 diff와 PR 내용이 어긋나므로 create-pr 문맥에선 중단이 맞다.
  - 단일화 시 create-pr 내부 검증 중복(11번)도 함께 정리하고, 기준 ref 통일(4번)도 같은 수정에 포함한다.

### 4. `create-pr` 기준 ref 불일치

- 0단계는 `origin/develop` 기준, 1~2단계는 로컬 `develop` 기준(`create-pr.md:31-32`).
- 영향: 로컬 develop이 낡았으면 "develop 대비 커밋 수"와 diff를 오판 — PR에 포함될 커밋 목록이 실제와 달라질 수 있음.
- 개선: 전 단계 `origin/develop`으로 통일.

## 중간 — 워크플로 마모

### 5. 완료된 계획 문서 미아카이브

- #344(PR #345 머지), #396(PR #404 머지), #398(PR #410 머지) 계획·태스크 문서 6개가 전부 `plans/` 직하에 잔류 — `archive/` 이동 안 됨.
- 영향: `/resume-plan` 기본 동작이 "최신 `*-tasks.md` 선택"이라 완료된 문서를 집어온다.
- 원인: 아카이브 시점 규칙이 어디에도 없음.
- 개선: `plan-doc-format.md`에 "PR 머지 후 `archive/`로 이동" 규칙 한 줄 추가, 또는 `/resume-plan`의 "모든 Task 완료" 분기에서 아카이브를 제안하도록 보강.

### 6. 분석 문서 간 수치 불일치

- 같은 30일 데이터셋인데 수치가 다름:
  - `usage-pattern-analysis.md` — 커밋 위임 "약 90회", `@plans` 참조 "61회"
  - `claude-code-custom-skills.md` / `usage-qna.md` — "118회", "106회"
- 영향: 포트폴리오 발췌 용도 문서라 수치 신뢰성이 중요 — 근거 데이터가 서로를 인용하는데 숫자가 어긋남.
- 개선: 산출 기준(집계 범위·패턴 매칭 기준)을 통일하거나 차이 사유를 각주로 명시.

### 7. `resume-plan` fetch 누락

- `resume-plan.md:23`에서 `git log --oneline origin/develop -15`를 **fetch 없이** 조회 — 낡은 origin ref로 Task 완료 여부를 오판할 수 있음.
- 개선: 2단계 앞에 `git fetch origin` 추가.

### 8. 트러블슈팅 규칙 vs 산출물 0개

- `troubleshooting-doc-format.md`에 자동 작성 트리거까지 정의돼 있으나 `trouble-shooting/` 디렉터리 자체가 없음(문서 0개). 30일간 디버깅 관련 프롬프트 305개 — 규칙만으로는 실행이 안 됨이 데이터로 입증됨.
- 해결책은 Stop 훅 또는 디버깅 종료 시점 트리거 스킬로의 승격(usage-qna Q4-4). 단, `claude-code-custom-skills.md`에서 사용자 결정으로 이번 범위 제외가 명시된 항목 — 재결정이 필요한 시점만 기록해 둔다.

## 낮음

### 9. 공용 읽기 권한 미공유

- `settings.local.json`의 `gh issue/pr list·view`, `git log/diff/status/branch/fetch` 등 읽기 전용 권한은 create-* 커맨드가 팀 전체에서 사용하는 것.
- 개선: 읽기 전용 권한을 `settings.json`으로 승격하면 팀원 권한 프롬프트 감소. 쓰기 권한(`gh issue create`, `git push` 등)은 개인 판단 영역이므로 local 유지.

### 10. CLAUDE.md 디렉터리 안내 누락

- `.claude/commands/`(커스텀 커맨드 6개), `.claude/analysis/`(분석 문서) 디렉터리의 존재를 CLAUDE.md가 언급하지 않음 — 신규 팀원 발견성 저하.

### 11. `create-pr` 내부 검증 중복

- 0단계와 2단계에 보호 브랜치·커밋 수 체크가 분산 — **3번 항목의 단일화 수정에 포함** (pre-pr-check 제거 시 함께 정리).

## 권장 처리 순서

1. **1~4번** — 한 커밋으로 묶기 적합. 3번은 pre-pr-check 제거 + create-pr 단일화로 확정, 4번(ref 통일)·11번(검증 중복 정리) 포함.
2. **5, 7번** — 커맨드·규칙 보강 한 커밋
3. **6번** — 분석 문서 수치 재검증 후 별도 커밋
4. **8~10번** — 필요 시점에 개별 판단
