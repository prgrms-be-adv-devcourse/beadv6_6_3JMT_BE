---
name: create-project-issue
description: Use when any service, module, or shared area in the current repository needs a new GitHub bug report, feature request, improvement, or maintenance issue.
---

# 프로젝트 GitHub 이슈 생성

현재 checkout의 이슈 템플릿을 단일 진실 공급원으로 사용한다. 실행 시점의 GitHub 라벨, assignee, Type, Project `#45 (프로젝트)`, Status를 조회하고 사용자 승인 뒤에만 이슈를 만든다.

## 실행 계약

| 단계 | 필수 결과 |
| --- | --- |
| 사전 점검 | 인증, 저장소, 요청 유형, 사용할 템플릿 확정 |
| 템플릿 변환 | frontmatter를 제목·라벨·assignee CLI 메타데이터와 본문으로 분리 |
| 메타데이터 | 실제 라벨·assignee·Type·Project·Status의 이름과 ID 확인 |
| 승인 | 제목, 본문 전체, 모든 메타데이터와 적용 동작 공개 |
| 생성 | 명시적 승인 뒤 이슈 생성, Type·Project·Status 적용 |
| 검증 | 실제 이슈와 프로젝트 값을 재조회하고 부분 실패 보고 |

요청 경로가 어느 서비스에 속하는지를 실행 조건으로 삼지 않는다. 현재 저장소의 product, payment, user, settlement 등 모든 모듈과 공통 영역을 같은 절차로 처리한다.

## 1. 사전 점검과 유형 결정

저장소 루트에서 다음을 확인한다.

1. `gh auth status`로 CLI 설치, 로그인 계정과 scope를 확인한다. `gh repo view --json nameWithOwner,owner,name,url`로 owner/repository를 고정한다.
2. `.github/ISSUE_TEMPLATE/`의 파일 목록을 확인하고 후보를 완전히 읽는다. frontmatter의 `name`, `about`과 본문 구조를 보고 요청에 맞는 템플릿을 고른다. 파일명만 가정하지 않는다.
3. 오류, 장애, 기대와 다른 동작은 Bug로, 새 기능이나 동작 개선은 Feature로, 문서·정리·리팩터링·테스트 같은 유지보수는 Task로 분류한다. 요청과 템플릿이 모호하면 가능한 선택과 영향을 한 번에 설명하고 묻는다.
4. 맞는 템플릿이 없으면 본문 구조를 만들지 말고 사용자에게 사용 가능한 템플릿 선택을 요청한다.

조회와 `/tmp` 임시 파일 작성은 가능하지만 이 단계에서 이슈, 라벨, 프로젝트나 필드를 생성·수정하지 않는다.

## 2. 템플릿을 CLI 입력으로 변환

선택한 파일에서 첫 `---` 쌍 사이의 YAML frontmatter와 이후 Markdown 본문을 분리한다. frontmatter는 `gh issue create`가 자동 해석하지 않으므로 다음 계약으로 변환한다.

- `title`: `[BUG]`, `[FEATURE]` 같은 접두어만 유지하고 안내용 플레이스홀더는 요청을 요약한 제목으로 교체한다.
- `labels`: YAML 문자열, 쉼표 구분 문자열, 목록을 모두 개별 후보로 정규화한다. 이후 실제 라벨 검증을 통과한 값만 각각 `--label`로 전달한다.
- `assignees`: 빈 값을 버리고 각 login을 후보로 보존한다. 생성자 본인 `@me`는 템플릿 값과 무관하게 항상 추가하며, 중복 login은 제거한다.
- `name`, `about` 등 웹 UI 전용 값과 frontmatter 구분자는 이슈 본문에 넣지 않는다.

본문의 섹션 제목, 순서, 체크리스트와 기존 이모지는 유지하고 안내 문구를 사용자 요청의 실제 내용으로 교체한다. 필수 정보가 부족하면 여러 번 나누지 말고 한 번에 묻는다.

- Bug: 버그 설명, 재현 단계, 예상 결과, 실제 결과
- Feature: 문제 제기, 제안하는 기능
- 선택 항목: 환경, 스크린샷, 대안, 추가 정보. 제공되지 않은 선택 항목 때문에 질문하지 않는다.

과장 수식어, 강조 남발, 새 이모지와 불필요한 설명을 넣지 않는다. 사실과 근거를 짧고 담백하게 쓴다.

## 3. 실제 메타데이터 조회

초안 전에 아래 값을 모두 실행 시점에 조회한다. 기억한 이름이나 ID를 사용하지 않는다.

1. `gh label list --limit 200 --json name,description,color`로 실제 라벨을 읽는다. frontmatter 후보가 존재하면 그대로 사용한다. 존재하지 않으면 이름과 설명상 대응이 하나뿐인 실제 라벨로 교체할 수 있다(예: `feature` → `feat`, `bug` → `fix`). 교체 근거를 승인 화면에 표시한다. 대응이 없거나 둘 이상이면 새 라벨을 만들지 말고 사용자에게 선택을 요청한다.
2. `gh api user`로 `@me`의 실제 login을 확인한다. frontmatter의 다른 assignee는 `repos/<owner>/<repo>/assignees/<login>` 조회로 할당 가능성을 확인한다. 확인되지 않은 login은 전달하지 않고 영향을 설명한다.
3. `gh api repos/<owner>/<repo>/issue-types`로 이 저장소의 Type 이름과 ID를 읽고 요청에 맞는 정확한 `Feature`, `Bug`, `Task`를 선택한다. 없으면 비슷한 값을 추측하거나 새 Type을 만들지 않는다.
4. 저장소 owner의 `gh project view 45 --owner <owner> --format json`으로 `#45 (프로젝트)`의 제목과 ID를 확인한다. 이어서 `gh project field-list 45 --owner <owner> --format json`으로 정확히 `Status` 필드와 `Todo` 옵션의 ID를 찾는다. Priority와 Effort는 설정하지 않는다.

Project 조회와 쓰기에는 필요한 token scope가 있어야 한다. scope 부족, `#45 (프로젝트)` 부재, Status/Todo 또는 Type 부재, 권한 부족이 발생하면 생성 전에 확인된 값과 누락된 영향 및 필요한 사용자 조치를 보고한다. 누락된 ID를 추측하지 않으며, 사용자가 누락 필드 없이 진행할지 명시적으로 선택하기 전에는 생성하지 않는다.

## 4. 승인용 전체 초안

다음을 한 번에 보여주고 **명시적 승인**을 받는다.

- 대상 repository와 선택한 템플릿 경로
- Type과 분류 근거
- 제목
- frontmatter를 제외한 Markdown 본문 전체
- 라벨과 frontmatter 원본·실제 라벨 사이의 교체 근거
- assignee login 목록(`@me` 포함)
- Project `#45 (프로젝트)`의 실제 제목·ID
- Status `Todo`의 field ID·option ID
- 승인 후 수행할 이슈 생성, Type 적용, 프로젝트 추가, Status 적용 동작

수정 요청이 있으면 초안을 고쳐 전체를 다시 보여준다. 승인 전에는 `gh issue create`, GraphQL mutation, `gh project item-add`, `gh project item-edit`를 실행하지 않는다.

## 5. 승인 후 생성과 필드 적용

승인 직전에 템플릿 파일과 조회한 라벨·assignee·Type·Project·Status를 다시 확인한다. 승인 화면과 달라졌으면 초안과 승인을 다시 수행한다.

1. 승인된 본문만 `/tmp` 파일에 저장하고 `gh issue create --title --body-file --label --assignee`로 생성한다. 현재 `gh issue create --help`가 `--type`을 지원하면 조회한 Type 이름도 함께 전달한다. frontmatter 자체와 존재하지 않는 라벨은 전달하지 않는다.
2. 생성 결과의 URL과 번호, node ID를 즉시 기록한다. 생성 명령에서 Type을 지원하지 않았다면 조회한 Type ID와 이슈 node ID로 `updateIssueIssueType` GraphQL mutation을 실행한다.
3. `gh project item-add 45 --owner <owner> --url <issue-url> --format json`으로 프로젝트에 추가하고 반환된 item ID를 기록한다.
4. `gh project item-edit --id <item-id> --project-id <project-id> --field-id <status-field-id> --single-select-option-id <todo-option-id>`로 Status를 `Todo`로 설정한다.

이슈 생성 뒤 Type, Project, Status 중 일부가 실패해도 생성된 이슈를 삭제하거나 같은 이슈를 다시 만들지 않는다. 성공한 단계와 실패한 단계, 오류, 사용자가 보정할 수 있는 명령을 구분해 보고한다.

## 6. 사후 검증과 보고

`gh issue view`와 GitHub API로 제목, 본문, Type, 라벨, assignee를 다시 읽는다. 프로젝트 item도 재조회해 `#45 (프로젝트)`와 Status `Todo`를 확인한다. 승인값과 다른 항목은 승인 범위 안에서 한 번 보정하고 다시 조회한다.

모두 일치하면 `#<번호> (이슈)`와 URL을 보고한다. 보정하지 못한 차이는 성공으로 숨기지 말고 다음 형식으로 보고한다.

| 항목 | 결과 |
| --- | --- |
| 이슈 | 생성됨: `#<번호> (이슈)`, URL |
| Type / Project / Status | 적용 또는 실패 |
| 라벨 / assignee | 실제 재조회 값 |
| 남은 조치 | 권한, scope, 누락 필드와 재시도 방법 |

## 중단 조건

| 조건 | 처리 |
| --- | --- |
| gh 미설치·미인증 | 설치 또는 사용자 인증 필요 보고 |
| 템플릿 없음·후보 모호 | 구조를 만들지 말고 선택 요청 |
| 필수 본문 정보 부족 | 필요한 항목을 한 번에 질문 |
| 실제 라벨·assignee·Type 미확정 | 생성·추측 금지, 선택 또는 권한 요청 |
| Project #45·Status Todo ID 미확정 | 영향 공개 후 명시적 진행 선택 전 생성 금지 |
| 승인 뒤 필드 적용 실패 | 이슈 유지, 부분 성공과 누락 필드 보고 |

## 흔한 실수

- `settlement-service/` 밖의 요청을 거절: 저장소 전체 스킬이므로 잘못이다.
- frontmatter를 본문에 복사하거나 label을 검증 없이 전달: CLI 입력 변환 계약을 어긴다.
- 템플릿의 안내 문구를 실제 내용처럼 남김: 모든 필수 섹션을 요청 내용으로 교체한다.
- Project 제목만 기억하고 `#45 (프로젝트)`나 Status ID를 조회하지 않음: 현재 메타데이터를 다시 읽는다.
- 제목과 요약만 보여주고 승인받음: 본문 전체와 모든 필드 적용 동작이 승인 대상이다.
- 필드 적용 실패 뒤 이슈 삭제 또는 재생성: 생성된 이슈는 유지하고 부분 성공을 보고한다.
