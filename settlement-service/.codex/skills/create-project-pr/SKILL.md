---
name: create-project-pr
description: Use when any service, module, or shared area in the current repository needs a GitHub pull request created or an existing pull request updated from the current branch.
---

# 프로젝트 GitHub PR 생성

현재 checkout의 PR 템플릿을 단일 진실 공급원으로 사용하고 base 대비 전체 변경을 검증한다. 사용자 승인 전에는 원격 브랜치와 PR을 변경하지 않는다.

**REQUIRED SUB-SKILL:** 먼저 `verify-project-changes`를 완전히 읽고 전체 변경 집합에 적용한다.

## 실행 계약

| 단계 | 필수 결과 |
| --- | --- |
| 사전 점검 | 인증, base/head, 작업 트리, commit, 기존 PR 확인 |
| 검증 | 전체 diff coverage와 경로별 규칙, 일반 검토, 영향 범위 테스트 |
| 승인 | 생성 또는 갱신할 제목, 본문 전체, 상태와 메타데이터 공개 |
| 원격 변경 | 명시적 승인 뒤 push와 PR 생성 또는 갱신 |
| 사후 확인 | 실제 PR 본문, 상태, base/head와 메타데이터 재조회 |

변경 경로가 특정 서비스에 포함되는지를 실행 조건으로 삼지 않는다. 규칙이 없는 모듈도 `general-code-review`와 테스트 영향 분석에서 제외하지 않는다.

## 1. 사전 점검과 입력 고정

저장소 루트에서 다음을 확인한다.

1. `gh auth status`로 CLI 설치와 인증을 확인한다.
2. 현재 head, 작업 트리, remote, 저장소 기본 브랜치와 추적 브랜치를 확인한다. 사용자가 base를 지정했다면 우선하고, 아니면 기본 브랜치와 merge-base 근거로 후보를 정한다. 확정한 base SHA, head SHA, merge-base SHA를 이후 재확인할 입력으로 기록한다.
3. base나 저장소 기본 브랜치 자체가 head이면 중단한다. base 대비 commit이 없거나 미커밋 변경 때문에 PR에서 작업이 누락될 수 있어도 중단하고 사용자에게 알린다.
4. `git fetch origin` 후 commit 전체 메시지, `base...HEAD` 파일 목록·stat·diff, staged·unstaged·관련 untracked 파일을 수집한다.
5. `gh pr list --head <head> --state all`로 같은 head의 PR을 찾는다. 열린 PR이 있으면 새로 만들지 않고 **갱신 대상**으로 사용한다. 둘 이상이거나 closed/merged PR만 있으면 임의로 고르지 말고 사용자에게 선택을 요청한다.

조회와 로컬 임시 파일 작성은 가능하지만 이 단계에서 push, `gh pr create`, `gh pr edit`, `gh pr ready`, 리뷰 요청처럼 원격 상태를 바꾸는 명령은 실행하지 않는다.

## 2. 템플릿과 메타데이터 조회

실행 시점에 `.github/PULL_REQUEST_TEMPLATE.md`를 가장 먼저 찾고 완전히 읽는다. 없으면 저장소의 `.github/`, 루트, `docs/`에서 GitHub가 인식하는 PR 템플릿 파일과 `PULL_REQUEST_TEMPLATE/` 디렉터리를 탐색한다. 후보가 하나면 사용하고, 여러 후보의 선택 근거가 없으면 사용자에게 묻는다. 템플릿이 없으면 구조를 지어내지 말고 중단한다.

다음 GitHub 메타데이터도 실제 값을 조회한다.

- owner/repository와 현재 로그인 사용자
- `gh label list`의 실제 라벨
- 기본 reviewer 후보별 GitHub 계정 존재 여부와 해당 repository에서 review 요청이 가능한지 read-only GitHub API로 조회한 결과
- 기존 PR이면 현재 base/head, 본문, Draft 여부, assignee, label, review request

템플릿의 섹션, 순서, 체크리스트 문구가 기준이다. 스킬에 기억된 항목으로 대체하지 않는다.

## 3. 전체 변경 검증 게이트

`verify-project-changes`에 확정한 base/head, `base...HEAD` 전체 diff와 관련 작업 트리 변경을 넘긴다. 변경 모듈과 공통 영역의 빌드 파일, 적용 규칙, 의존 관계에서 테스트·check·build 명령을 도출하고 실제로 실행한다. 루트나 공통 계약 변경은 영향을 받는 하위 모듈까지 확장한다.

결과의 coverage manifest와 모든 `RULE / SCOPE / STATUS / FINDINGS`를 확인한다.

- manifest의 모든 파일이 규칙 검사 또는 `general-code-review`에 배정되어야 한다.
- `FAIL`, `UNVERIFIED`, coverage 누락이 하나라도 있으면 전체 검증은 미통과다.
- 실행하지 못한 테스트, 읽지 못한 규칙, 확인하지 못한 영향은 통과로 바꾸지 않는다.
- 미통과이면 findings와 남은 위험을 보고하고 중단한다. push와 PR 생성·갱신은 하지 않는다.

## 4. 승인용 PR 초안 작성

검증이 통과하면 commit과 실제 diff를 근거로 템플릿을 채우고 `/tmp`의 임시 body 파일에 저장한다.

- 안내 문구를 실제 설명으로 교체하되 섹션 제목과 구조는 유지한다.
- 설명과 요약에는 무엇을 왜 바꿨는지 짧게 적는다.
- 테스트 계획에는 실제 명령과 결과를 적는다. 수행하지 않은 검증을 수행했다고 쓰지 않는다.
- 체크리스트는 검증 결과로 확인된 항목만 `[x]`로 표시한다. 적용 대상이 없으면 근거와 함께 N/A로 표현한다.
- 브랜치와 commit에서 확인한 이슈만 `#<번호> (이슈)` 형식으로 연결한다. 모르는 링크나 내용을 만들지 않는다.
- 저장소 파일 링크는 `https://github.com/<owner>/<repo>/blob/<head-sha>/<path>` 전체 URL을 사용한다.
- 과장 수식어, 강조 남발, 새 이모지, 불필요한 설명을 넣지 않고 사실 위주로 담백하게 쓴다.

제목은 대표 변경을 `<type>: <내용>` 한 줄로 요약한다. 기본 메타데이터는 다음과 같이 제안하되 실제 저장소 값과 대조한다.

- 상태: Ready for review. 사용자가 명시적으로 요청할 때만 Draft
- assignee: `@me`
- label: 브랜치 type을 우선하고 없으면 대표 commit type을 사용하되, 실제 존재하는 라벨만 선택
- reviewer: `git-mesome`, `Jinpyo-An`, `oxix97`, `gfkmkl`에서 현재 로그인 사용자를 제외하고, 계정이 존재하며 해당 repository에서 review 요청 가능한 것으로 확인된 후보만 사용

reviewer 후보 검증은 승인 화면을 만들기 전에 실행한다. 계정 조회가 실패했거나 repository review 요청이 불가능한 후보는 `--reviewer`에서 제외하고 후보별 제외 이유를 승인 화면에 표시한다. 적격 reviewer가 한 명도 없으면 `--reviewer` 플래그를 생략한다.

기존 PR 갱신에서는 현재 값과 제안 값을 함께 보여준다. 기존 메타데이터 삭제나 base 변경도 승인 대상이며 조용히 덮어쓰지 않는다.

## 5. 승인 경계

다음을 한 번에 보여주고 **명시적 승인**을 받는다.

- 작업: 새 PR 생성 또는 기존 `#<번호> (PR)` 갱신
- base ← head와 base/head/merge-base SHA, 제목, Ready/Draft 상태
- assignee, label, reviewer와 제외된 reviewer 후보별 이유
- 템플릿을 채운 실제 본문 전체
- 실행할 push와 PR 생성·갱신 동작

수정 요청이 있으면 초안을 고쳐 전체를 다시 보여준다. 승인 전에는 push나 어떤 PR 변경도 하지 않는다.

## 6. 승인 후 적용

push 직전에 base SHA, head SHA, merge-base SHA와 작업 트리를 다시 조회한다. 승인 화면의 입력과 하나라도 달라졌으면 push하지 말고 전체 diff 검증, 초안 작성, 승인을 다시 수행한다.

입력이 그대로면 승인된 head를 `git push -u origin <head>`로 올리고 `git rev-parse HEAD`로 최종 SHA를 확정한다. 본문의 저장소 파일 링크를 최종 SHA로 맞춘 뒤 각 링크 경로가 존재하는지 `gh api "repos/<owner>/<repo>/contents/<path>?ref=<head-sha>"`로 확인한다.

- 새 PR: `gh pr create --base --head --title --body-file --assignee`를 실행한다. 승인된 reviewer와 label만 추가하고, 기본 생성에는 `--draft`를 넣지 않는다.
- 기존 PR: 승인된 제목·본문·base·메타데이터만 `gh pr edit`로 갱신한다. 승인된 상태 변경이 필요할 때만 `gh pr ready` 또는 그 역동작을 실행한다.
- reviewer나 label을 정하지 못했으면 빈 값을 전달하지 말고 해당 옵션을 생략한다.

부분 실패 시 이미 만들어진 PR을 삭제하거나 중복 생성하지 않는다. 생성된 PR과 적용하지 못한 항목을 보고하고 가능한 항목만 승인 범위에서 보정한다.

## 7. 사후 검증과 보고

`gh pr view --json number,url,title,baseRefName,headRefName,body,isDraft,labels,assignees,reviewRequests`로 실제 상태를 다시 읽는다. 승인된 본문, 링크, Ready/Draft, base/head와 메타데이터를 대조한다. 누락된 승인 항목은 `gh pr edit` 또는 `gh pr ready`로 적용하고 다시 조회한다.

검증된 결과를 `#<번호> (PR)`과 URL로 보고한다. 보정하지 못한 차이가 있으면 성공으로 숨기지 말고 남은 위험과 함께 적는다.

## 중단 조건

| 조건 | 처리 |
| --- | --- |
| gh 미설치·미인증 | 설치 또는 사용자 인증 필요 보고 |
| base/head 부적합, commit 없음, 미커밋 변경 | PR 입력을 정리한 뒤 재시도 |
| 템플릿 없음·후보 모호 | 구조를 만들지 말고 경로 확인 요청 |
| 검증 `FAIL`·`UNVERIFIED`·coverage 누락 | findings 보고 후 원격 변경 금지 |
| 열린 동일 head PR 존재 | 중복 생성 대신 승인 후 갱신 |
| reviewer·label 미확정 | 옵션 생략, 임의 생성·추측 금지 |

## 흔한 실수

- `settlement-service/`가 없다는 이유로 중단: 전체 저장소 스킬이므로 잘못이다.
- 템플릿 체크리스트를 스킬 문구로 재작성: 현재 checkout 템플릿이 기준이다.
- 초안을 보여준 뒤 승인 없이 push: push도 승인 이후의 외부 변경이다.
- 기존 PR을 발견하고도 새 PR 생성: 동일 head의 열린 PR을 갱신한다.
- 생성 명령 성공만 보고 종료: 실제 PR 메타데이터 재검증까지 완료해야 한다.
