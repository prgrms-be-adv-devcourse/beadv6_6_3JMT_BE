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
