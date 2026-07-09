---
name: create-github-pr
description: 현재 브랜치의 커밋·diff·build를 확인해 레포의 PR 템플릿을 채우고, 사용자 승인 후 gh pr create로 Pull Request를 생성한다. 리뷰어는 CODEOWNERS 전체(작성자 제외)를 제안한다. "PR 만들어줘", "풀리퀘 올려줘" 같은 요청에 사용한다.
---

# Product PR 생성 Skill

## 사전 조건

- `develop`/`main` 브랜치에서 PR을 만들지 않는다.
- base 대비 commit이 없으면 만들지 않는다.
- push/생성 전 사용자 승인을 받는다.

## 절차

1. `.github/PULL_REQUEST_TEMPLATE.md`를 읽는다.
2. `CLAUDE.md`, `.claude/rules/architecture.md`, `.claude/rules/product-api.md`,
   `.claude/rules/testing.md`, `.claude/rules/git-workflow.md`를 읽는다.
3. 현재 브랜치, base(기본 `develop`), commit, diff를 확인한다.

```bash
git branch --show-current
git log develop..HEAD --oneline
git diff develop...HEAD --stat
```

4. 모듈 build를 실행한다(`test`만 실행하는 것으로 대체하지 않는다).

```bash
cd product-service
./gradlew clean build --no-daemon
```

Windows:

```powershell
cd product-service
.\gradlew.bat clean build --no-daemon
```

5. `sync-product-docs`와 `verify-rules`를 먼저 실행해 통과 여부를 확인한다
   (`agents/rule-checker`가 최종 게이트로 다시 종합한다).
5.5. `agents/rule-checker`를 호출해 4가지 게이트(verify-rules, sync-product-docs, 모듈 경계,
   이슈/브랜치)를 종합 판정받는다. 하나라도 실패하면 실패 목록을 사용자에게 보여주고 PR
   생성을 진행하지 않는다. 모두 통과해야 다음 단계로 진행한다.
6. 라벨·assignee·리뷰어를 정한다.
   - **라벨**: 브랜치/커밋 타입에서 도출하되 `gh label list`로 실존 확인 후 사용. 없으면 생략.
   - **assignee**: 작성자 본인(`--assignee @me`).
   - **리뷰어**: `.github/CODEOWNERS` 전체 인원 − 작성자 본인. 리뷰어는 Slack 전체 알림을
     유발하므로 다음 단계(승인 화면)에서 반드시 다시 보여주고 확인받는다. 확정 못 하면
     `--reviewer`를 생략한다(추측으로 아무나 달지 않는다).
7. PR 본문을 템플릿 구조 그대로 채운다. 체크리스트는 실제 diff/테스트 결과로 판단 가능한
   항목만 체크한다.
8. **base·본문·리뷰어·라벨을 한 화면에 보여주고 승인받는다.** 승인 전에는 `git push`도
   `gh pr create`도 실행하지 않는다. **승인은 명확한 긍정 답변만 인정한다** — 슬래시
   커맨드 재호출, 화제 전환, 다른 질문 등 애매한 응답은 승인으로 해석하지 않고 반드시
   다시 확인한다. **본문은 요약이 아니라 실제 등록될 원문 전체를 그대로 보여준다.**
9. 승인되면 생성한다.

```bash
git push -u origin <head-branch>
gh pr create \
  --base develop \
  --head <head-branch> \
  --title "<type>: <설명>" \
  --body-file <body-file> \
  --assignee "@me" \
  --reviewer <리뷰어1>,<리뷰어2> \
  --label "<라벨>"
```

`--reviewer`·`--label`이 정해지지 않았으면 플래그 자체를 뺀다(빈 값이면 `gh`가 에러낸다).

## 엣지 케이스

- 이미 같은 head의 PR이 있으면 중복 생성하지 않고 기존 PR URL을 알린다
  (`gh pr list --head <branch>`).
- 리뷰어 태깅만 실패해도 PR 자체는 살아남는다. `gh pr edit <N> --add-reviewer ...`로 재시도를
  안내한다.
- build 실패 시 원인을 분류해 사용자에게 보고하고, 로컬 DB 환경 차이로 인한 실패인지 CI에서도
  재현될지 판단해 PR 본문에 남긴다.

## 톤

담백하게. 과장된 수식어, 정형화된 강조 문구를 피한다.
