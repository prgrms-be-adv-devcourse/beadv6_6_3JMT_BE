# PR 보안 검증 게이트 설계

작성일: 2026-06-24

## 배경

`create-github-pr` 스킬은 PR 본문을 채우기 전에 `verify-rules` 스킬을 게이트로 호출한다.
`verify-rules`는 프로젝트 룰 6종(clean-architecture, domain-model, controller-exception,
code-style, swagger, git-convention)을 `rule-checker` 서브에이전트 6개로 **병렬 검증**하고,
위반이 하나라도 있으면 PR 생성을 막는다. 검증 결과는 PR 템플릿 체크리스트 6항목과 1:1로 대응한다.

여기에 **보안 검증**(시크릿 파일 커밋·하드코딩 시크릿·민감정보 노출·.gitignore 누락)을 추가하고,
그 결과를 체크리스트에도 명시하고 싶다.

## 목표

- PR 생성 전에 보안 위반을 자동으로 잡아 게이트로 막는다.
- 검증 결과를 PR 템플릿 체크리스트에 한 줄로 명시한다.
- 기존 게이트·체크리스트 기계(verify-rules + rule-checker + 템플릿 1:1 매핑)를 **그대로 재사용**한다.

## 선택한 접근: 보안을 7번째 "룰"로 통합

보안 검증을 독립 단계나 인라인 bash로 두지 않고, 기존 룰 6종과 동일한 형식의 7번째 룰로 추가한다.
이유:

- 기존 게이트·체크리스트 대칭("verify-rules = 체크리스트 1:1")을 유지한다.
- 룰을 `.claude/rules/*.md` 파일로 분리하는 기존 철학과 일치한다(버전 관리·재사용).
- "yml 로컬 설정값은 위반 아님" 같은 **의미적 예외**를 LLM 기반 `rule-checker`가 룰 파일을 읽고
  판단하므로 결정적 grep보다 오탐이 적다.

대안(별도 보안 게이트 단계, 인라인 bash)은 게이트가 둘로 늘거나 룰 분리 철학에 어긋나 기각.

## 변경 대상 (총 5개 파일)

### 1) 신규: `settlement-service/.claude/rules/security.md`

다른 룰 파일과 동일한 번호 절 구조. 검사 항목과 예외, 검사 방법을 명문화한다.

- **§1 시크릿 파일 커밋 금지** — `git diff <base>...HEAD --name-only`로 **추가된 파일**에
  `.env*`, `*.pem`, `*.key`, `*.p12`, `*.keystore`, `*credential*`, `*secret*.yml/.json`,
  `id_rsa` 등 민감 파일이 있으면 FAIL. (파일명 패턴 매칭)
- **§2 하드코딩 시크릿 금지** — **diff 추가 라인(`+`)만** 스캔. API 키·토큰·비밀번호·AWS 액세스키·
  Bearer 토큰·private key 블록 등 고엔트로피 리터럴이 코드에 박히면 FAIL.
- **§3 민감정보 로깅·노출 금지** — diff 추가 라인에서 비밀번호·토큰·주민번호·카드번호 등을
  `log.*`로 찍거나 예외 메시지·HTTP 응답으로 그대로 노출하면 FAIL.
  (controller-exception.md §2-3 "persistence 상세 노출 금지"와 연결)
- **§4 .gitignore 누락 점검** — `.env` 등 민감 파일 패턴이 `.gitignore`에 없으면 FAIL.
  (실제 유출이 아니더라도 예방 차원에서 하드 FAIL로 통일 — 고치기 쉽고 미래 유출을 막는다.)
- **§예외(중요)** — `application*.yml`/`application*.properties`의 **로컬 설정값**
  (localhost, 로컬 DB URL/계정, 포트 등)은 위반이 아니다. 실제 운영 시크릿
  (외부 API 키·운영 비밀번호)이 평문으로 박힌 경우만 FAIL. 이 의미 판단을 룰 파일에 명문화한다.
- **검사 방법** — 룰 본문에 "검사기는 `git diff <base>...HEAD`로 추가 라인을 떠서 `+` 라인만 보고,
  `--name-only`로 추가 파일을, `.gitignore` 내용을 직접 확인하라"를 적어 `rule-checker`가
  Bash로 수행하게 한다.

### 2) `settlement-service/.claude/skills/verify-rules/SKILL.md`

- description: "프로젝트 룰 **6종**" → "**7종**", 목록에 `security` 추가.
- "룰 → 체크박스 매핑" 테이블에 7번째 행 추가:

  | RULE_NAME | RULE_FILE | 입력 | 체크박스 |
  | --- | --- | --- | --- |
  | security | `settlement-service/.claude/rules/security.md` | 변경 파일 + diff | 보안 |

- 2단계: "rule-checker **6개**" → "**7개**" 병렬 디스패치. security 디스패치 프롬프트에
  `INPUT = CHANGED_FILES`와 함께 "**diff 추가 라인과 `.gitignore`도 직접 확인하라**"를 명시.
- 3·4단계: 게이트 집계·출력 "6/6" → "7/7", 통과 체크리스트 렌더에 보안 줄 추가.

### 3) `settlement-service/.claude/agents/rule-checker.md` (에이전트 정의)

- 본문은 "단일 룰 파일 하나 기준 검사"라 보안 룰에도 그대로 동작. 도구(Read/Grep/Glob/Bash) 충분.
- description의 "룰 **6종**" → "**7종**" 텍스트만 갱신.

### 4) `.github/ISSUE_TEMPLATE/pull_request_template.md`

체크리스트에 7번째 항목 추가:

```
- [ ] 보안: .env·시크릿 파일 미커밋·코드 내 하드코딩 시크릿 없음·민감정보 로깅/노출 없음·.gitignore 누락 없음
```

### 5) `settlement-service/.claude/skills/create-github-pr/SKILL.md`

- 도입부·2.5단계·4단계 본문의 "룰 **6종**", "**6개** 룰 항목" → "**7종**", "**7개**"로 수치 갱신.
- 워크플로우 단계는 그대로(2.5에서 verify-rules 호출 → 보안 게이트 자동 포함).
- "다른 스킬과의 경계"의 verify-rules 설명도 7종으로.

## 게이트 동작

- security 룰 결과가 PASS/N/A면 게이트 통과(7/7), FAIL이면 다른 룰과 동일하게 PR 생성을 막고
  위반 목록(대상:위치·사유)을 보여준 뒤 멈춘다. `git push`·`gh pr create` 하지 않는다.

## 비목표 (YAGNI)

- gitleaks 등 외부 시크릿 스캐너 연동은 하지 않는다(룰 파일 + LLM 검사기로 충분).
- 기존 커밋 히스토리 전체 시크릿 스캔은 하지 않는다(이번 PR diff 범위만).
- WARN 같은 새 게이트 상태는 도입하지 않는다(PASS/FAIL/N/A 바이너리 유지).
