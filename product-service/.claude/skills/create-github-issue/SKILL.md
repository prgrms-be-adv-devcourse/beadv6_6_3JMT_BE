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
