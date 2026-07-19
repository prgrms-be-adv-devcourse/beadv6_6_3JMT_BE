---
description: 현재 브랜치의 커밋을 분석해 팀 정식 PR 템플릿으로 GitHub PR을 생성합니다. base는 항상 develop이며, 초안 확인 후 gh pr create를 실행합니다.
---

아래 절차를 단계별로 수행하라.

## 1단계: 사전 검증

```bash
git fetch origin
git branch --show-current
git status --short
```

- 현재 브랜치가 `develop` 또는 `main`이면 **즉시 중단**하고 "작업 브랜치에서 실행해 주세요. 현재 브랜치: <브랜치명>"라고 경고한다.
- `git status --short` 출력이 있으면 "⚠️ 미커밋 변경이 있습니다. 커밋 또는 stash 후 다시 실행해 주세요."라고 경고하고 **즉시 중단**한다 — 미커밋 상태로 PR을 만들면 diff와 PR 내용이 어긋난다.

## 2단계: 커밋·충돌 현황 파악

모든 비교 기준은 **`origin/develop`**(1단계에서 fetch한 최신)이다.

```bash
git log origin/develop..HEAD --oneline
git diff origin/develop...HEAD --stat
git log HEAD..origin/develop --oneline
git diff --name-only origin/develop...HEAD
git diff --name-only HEAD...origin/develop
```

- `origin/develop` 대비 커밋이 0개이면 "PR에 포함할 커밋이 없습니다. 작업 후 다시 시도해 주세요."라고 안내하고 중단한다.
- 마지막 두 명령 결과에서 `payment-service/` 하위의 공통 변경 파일이 있으면 "⚠️ 충돌 가능성 있는 파일: <목록>" 경고 후 계속 진행할지 사용자에게 확인한다. rebase 또는 merge 후 재시도를 권장한다.
- 이상 없으면 "✅ 사전 검증 통과" 출력 후 다음 단계로 진행한다.

## 3단계: 브랜치명에서 정보 추출

브랜치명 형식: `type/#이슈번호-설명` (예: `feat/#15-payment-confirm-api`)

- `type`: 브랜치명 앞 부분에서 추출. 없으면 커밋 메시지에서 추론.
- `이슈번호`: `#` 뒤 숫자 추출. 없으면 "이슈 번호를 알려주세요."라고 묻는다.

## 3-1단계: 라벨 결정

추출한 `type` 기반으로 GitHub 라벨을 자동 결정한다.

| type | 라벨 |
|---|---|
| `feat` | `feat` |
| `fix` | `fix` |
| `docs` | `docs` |
| `chore` | `chore` |
| `test` | `test` |
| `style` | `style` |
| `refactor` | `refactor` |

## 4단계: PR 제목 생성

팀 형식: `type: 한국어 설명 (#이슈번호)`

예시:
- `feat: 결제 승인 API 구현 (#15)`
- `chore: payment-service 모듈 초기 설정 (#6)`

커밋 로그와 변경 파일 목록을 종합해 한국어로 간결한 설명을 작성한다.

## 5단계: PR 본문 생성

아래 팀 정식 PR 템플릿 구조를 채운다. 커밋 로그와 변경 파일 통계를 근거로 작성하고, 알 수 없는 항목은 안내 문구를 유지한다.

```markdown
## 🛠️ 설명 (Description)

어떤 작업을 했는지 설명해주세요.

## 📄 설계 문서 (Design Document)

관련 설계 문서나 위키 페이지가 있다면 링크를 추가해주세요.(하위 항목 노션링크 첨부)

## ✅ 테스트 계획 (Test Plan)

- 어떤 테스트를 수행했는지, 또는 수행할 예정인지 작성해주세요.
- 유닛 테스트, 통합 테스트, E2E 테스트 등
- 테스트 커버리지

## 📝 변경 사항 요약 (Summary)

- (커밋 로그에서 추출한 주요 변경 사항)

## 🔗 관련 이슈 (Related Issues)

- Closed #이슈번호

## ☑️ 체크리스트 (Checklist)

- [ ] 코드가 프로젝트 코딩 컨벤션을 따릅니다.
- [ ] 테스트 코드가 작성되었고, 통과했습니다.
- [ ] 변경 사항에 대한 문서화가 완료되었습니다.
- [ ] 필요한 경우, 다른 팀원에게 리뷰를 요청했습니다.
- [ ] 레이어 격리: `domain` 패키지에 JPA/Toss SDK 등 외부 의존성 침투 없음
- [ ] 보안: `.env` 값·PG API Key가 코드에 하드코딩되지 않음

## 👀 리뷰어를 위한 참고 사항 (Notes for Reviewers)

## ➕ 추가 정보 (Additional Information)
```

## 6단계: 초안 제시 및 동의 획득

아래 형식으로 초안을 보여준다.

```
=== PR 초안 ===
브랜치: <현재 브랜치> → develop
제목: feat: 결제 승인 API 구현 (#15)
라벨: feat
담당자: @me (본인)

커밋 (develop 이후):
(git log 결과)

변경 파일:
(git diff --stat 결과)

본문:
(작성된 본문 전체)
================

이 내용으로 PR을 생성할까요? (수정이 필요하면 말씀해 주세요)
```

수정 요청이 있으면 반영 후 다시 보여준다. **명시적 동의를 받기 전까지는 push 및 gh 명령을 실행하지 않는다.**

## 7단계: PR 생성

동의를 받으면 순서대로 실행한다.

**원격 브랜치 확인 후 푸시 (없으면):**
```bash
git push -u origin <현재 브랜치>
```

**PR 생성:**
```bash
gh pr create \
  --base develop \
  --head <현재 브랜치> \
  --title "<PR 제목>" \
  --body "<본문>" \
  --label "<라벨>" \
  --assignee @me
```

실행 후 생성된 PR URL을 보고한다.
