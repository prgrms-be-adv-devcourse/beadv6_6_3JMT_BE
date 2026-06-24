# 룰 검증 서브에이전트 설계

PR 직전, 프로젝트 룰 파일(`settlement-service/.claude/rules/`) 6종을 기준으로 변경 코드를
병렬 서브에이전트로 검증하고, 결과를 PR 체크리스트에 체크박스로 자동 반영한다.
위반이 있으면 PR 생성을 게이트로 막는다.

## 배경 / 목적

- `settlement-service/.claude/rules/`에 컨벤션 룰 6종이 정리돼 있다:
  `clean-architecture`, `domain-model`, `controller-exception`, `code-style`,
  `swagger`, `git-convention`.
- 현재 PR 템플릿(`.github/ISSUE_TEMPLATE/pull_request_template.md`)의 체크리스트 항목은
  이 룰과 맞지 않고(LLM·`source_hash` 등 타 서비스 흔적), 맨 아래
  `TODO: 추후 자가점검 항목은 아키텍처에 맞게 변동해야 합니다`로 교체 예정임이 명시돼 있다.
- `create-github-pr` 스킬은 체크리스트를 스킬에 박아두지 않고 **실행 시점에 템플릿에서 읽어**
  채운다(템플릿이 단일 진실 공급원).
- 목표: PR을 만들기 직전에 룰 위반을 자동 검증하고, 통과 항목을 체크박스로 PR 본문에 반영한다.

## 결정 사항 (브레인스토밍 합의)

| 항목 | 결정 |
| --- | --- |
| 검증 대상 | **변경 파일 전체** (diff가 닿은 파일은 통째로, 손 안 댄 파일은 제외) |
| 병렬 분할 | **룰 파일당 서브에이전트 1개** (총 6개) |
| 결과 표시 | **PR 템플릿 체크리스트를 룰 기반으로 교체 + 통과 항목 자동 체크** |
| 위반 처리 | **위반 ≥1건이면 PR 생성 중단, 수정 유도** (엄격 게이트) |
| 트리거/연동 | **독립 `verify-rules` 스킬 + `create-github-pr`에서 호출** (단독 실행도 가능) |

## 전체 구조

```
verify-rules 스킬 (오케스트레이터, 메인 컨텍스트)
   │ 1. base 대비 변경 파일 목록 수집 (git diff)
   │ 2. rule-checker 서브에이전트 6개 병렬 디스패치 (룰당 1개)
   ▼
rule-checker × 6  (읽기 전용, 격리 컨텍스트)
   │  각자: 자기 룰 파일 + 변경 파일 통째로 읽고 위반 탐지
   │  구조화된 리포트 반환 (룰별 PASS/FAIL/N-A + 위반 file:line + 근거)
   ▼
verify-rules 가 6개 결과 집계
   ├─ 위반 0건 → 체크리스트 전부 [x], "PR 진행 가능"
   └─ 위반 ≥1건 → 게이트 STOP, 위반 목록 출력, 수정 유도 (PR 안 만듦)
        ▲
create-github-pr 스킬이 본문 채우기 직전에 verify-rules 를 먼저 호출
   → 통과해야 다음 단계. 통과 시 집계된 체크리스트를 PR 본문에 [x]로 주입
```

## 구성요소

### 1. `.claude/agents/rule-checker.md` — 읽기 전용 검증 서브에이전트 (1종)

- 프롬프트로 **검증할 룰 파일 경로 + 변경 파일 목록**을 받는다.
- 해당 룰 **하나만** 대조해 위반을 탐지하고, 고정 포맷 리포트를 반환한다.
- 6번 병렬 호출되며 각자 다른 룰을 맡는다. (한 에이전트 = 한 룰 = 한 체크박스)
- 도구는 읽기·검색 전용 (Edit/Write 없음). 검증만 하고 코드를 고치지 않는다.
- 자기 룰과 무관한 위반은 보고하지 않는다 (룰 간 책임 분리).

### 2. `.claude/skills/verify-rules/SKILL.md` — 오케스트레이터 스킬

- 책임: 변경 파일 수집 → 6개 디스패치 → 집계 → 게이트 판정 → 체크리스트 렌더.
- 단독 호출 가능("룰 검증만 돌려줘")하고, `create-github-pr`에서도 호출된다.
- 룰 파일 6종의 경로를 알고 있고, 각 룰을 어느 체크박스 라벨에 매핑하는지 안다.

### 3. PR 템플릿 체크리스트 교체 (일회성)

`.github/ISSUE_TEMPLATE/pull_request_template.md`의 기존 체크리스트 4항목을
6룰 1:1 매핑으로 교체한다(룰 = 체크박스 1개):

```markdown
## ☑️ 체크리스트 (Checklist)

- [ ] 클린아키텍처: 의존성 방향(presentation→application→domain←infrastructure)·포트&어댑터·계층 책임 준수
- [ ] 도메인 모델: 비즈니스 setter 금지·상태변경은 도메인 메서드·Lombok 허용 범위 준수
- [ ] Controller/예외: 얇은 컨트롤러(Repository 직접 접근 금지)·커스텀 예외·전역 예외 핸들러
- [ ] 코드 스타일: 네이밍 케이스·와일드카드 import 금지·빈 catch 금지
- [ ] Swagger: 표현계층 한정·@Operation/@ApiResponses/@Schema 명시
- [ ] Git 컨벤션: 커밋 메시지(`<타입>: <내용>`)·브랜치 명명 규칙
```

- 맨 아래 `TODO:` 안내 문구는 제거한다.

### 4. `create-github-pr` 연동

- "본문 채우기" 직전 단계에 **verify-rules 호출**을 끼워 넣는다.
- 게이트 통과해야 다음 단계(본문 작성·PR 생성)로 넘어간다.
- 통과 시 verify-rules가 집계한 결과를 템플릿 체크리스트에 `[x]`로 반영한다.
- 체크리스트 항목 자체는 여전히 **템플릿에서 읽는다**(스킬에 박지 않는다).

## 데이터 흐름 / 리포트 포맷

`Request ──▶ Command ──▶ ...` 같은 계층 변환과 별개로, 서브에이전트↔스킬 간에는
아래 고정 텍스트 포맷으로 결과를 주고받는다(메인 에이전트가 파싱).

```
RULE: clean-architecture
STATUS: FAIL
VIOLATIONS:
- src/main/java/.../SettlementController.java:42 — Repository 직접 주입 (포트 미경유)
- src/main/java/.../Settlement.java:13 — domain이 infrastructure import
```

- 위반이 없으면 `STATUS: PASS`, `VIOLATIONS:`는 비운다.
- 변경 파일에 해당 룰 적용 대상이 전혀 없으면 `STATUS: N/A` (체크박스 [x] 처리).

집계 규칙:
- 모든 룰이 `PASS` 또는 `N/A` → 게이트 통과, 6개 체크박스 전부 `[x]`.
- 하나라도 `FAIL` → 게이트 STOP. 해당 룰 체크박스는 `[ ]` 유지, 위반 목록 출력.

## 실패 / 엣지 처리

- **적용 대상 없음**: 변경에 컨트롤러가 없는데 swagger 룰 → 그 룰은 `N/A`로 통과, 체크박스 `[x]`.
- **위반 발견**: 1건이라도 있으면 PR 생성 중단. 위반을 `file:line + 사유`로 출력하고 수정 유도.
- **변경 없음**: `git diff`가 비면 검증할 게 없으니 알리고 멈춘다.
- **base 브랜치 판정**: `create-github-pr`의 기존 base 판정 로직(develop/main)을 재사용한다.

## 테스트 / 검증 방법

- **위반 케이스**: 일부러 룰 위반 코드(Controller에 Repository 직접 주입, 도메인에 `@Setter`,
  와일드카드 import 등)를 만든 브랜치에서 verify-rules 실행 → 해당 룰 `FAIL`,
  게이트가 PR을 막는지 확인.
- **통과 케이스**: 깨끗한 변경에서 전 룰 `PASS`/`N/A`, 체크박스 전부 `[x]` 확인.
- **N/A 케이스**: 컨트롤러 변경이 없는 PR에서 swagger 룰이 `N/A`로 통과하는지 확인.
- **연동**: `create-github-pr` 흐름에서 검증 통과 후에만 본문 작성·PR 생성으로 진행되는지 확인.

## 범위 밖 (YAGNI)

- 위반 자동 수정(auto-fix)은 하지 않는다. 검증·게이트·리포트까지만.
- 룰별 세부 항목 단위 체크박스 분할은 하지 않는다(룰 = 체크박스 1:1 유지).
- 변경과 무관한 모듈 전체 스캔은 하지 않는다(변경 파일 한정).
