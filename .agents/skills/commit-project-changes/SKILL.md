---
name: commit-project-changes
description: 현재 저장소의 요청된 변경을 분석하고 적용되는 AGENTS.md와 Git 규칙에 맞춰 안전하게 stage·commit할 때 사용한다. 사용자가 "커밋해줘", "변경 커밋", "커밋 메시지 만들어줘"라고 요청하면 적용한다. push, PR 생성, 요청과 무관한 변경에는 사용하지 않는다.
---

# 프로젝트 변경 커밋

모든 경로는 `git rev-parse --show-toplevel`로 구한 저장소 루트를 기준으로 해석한다. 특정 서비스나 작업자별 경로 제한을 미리 가정하지 않고 사용자 요청과 실제 변경 파일로 커밋 범위를 정한다.

## 절차

1. `git status --short`, staged·unstaged diff와 관련 untracked 파일을 확인한다. 요청에 해당하는 변경이 없으면 stage나 commit 없이 보고하고 종료한다.
2. 저장소 루트부터 각 대상 경로까지 적용되는 `AGENTS.md`를 완전히 읽고 Git 규칙을 확인한다. 규칙이 충돌하거나 변경 집합이 불명확하면 사용자에게 질문한다.
3. 현재 브랜치가 `main` 또는 `develop`이면 작업 브랜치에서 커밋할지 확인한다. 현재 브랜치와 요청 변경의 관련성이 불명확해도 확인하며 임의로 브랜치를 만들지 않는다.
4. 요청과 관련 없는 사용자 변경은 stage하지 않는다. 파일 경로를 명시해 stage하고 `git add -A`를 저장소 전체에 무조건 적용하지 않는다.
5. 이미 staged된 변경에 요청과 무관한 파일이나 hunk가 있으면 임의로 unstage하거나 부분 stage하지 않는다. 포함 또는 분리 방법을 사용자에게 확인한다.
6. 커밋 직전에 staged 파일 목록과 전체 staged diff를 다시 확인한다.
7. 적용되는 Git 규칙과 diff의 주된 목적에 따라 `<type>: <한국어 설명>` 형식으로 메시지를 정한다. 목적이 다른 변경이 섞였으면 커밋 분리를 제안한다.
8. 커밋 메시지 본문이나 trailer에 `Co-Authored-By`를 추가하지 않는다.
9. hook을 우회하지 않고 `git commit`을 실행한 뒤 `<short-hash> (커밋)` 형식으로 해시와 제목을 보고한다.

push, PR, stash, reset은 별도 요청 없이 하지 않는다.
