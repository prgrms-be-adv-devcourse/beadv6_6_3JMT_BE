---
name: product-create-pr
description: branch, diff, build, 관련 issue를 확인한 뒤 Product Service PR을 준비하고 생성한다.
---

# Product PR 생성 Skill

Product Service 작업의 PR 생성을 요청받으면 이 절차를 따른다.

## 사전 조건

- `develop` 또는 `main` 브랜치에서 PR을 만들지 않는다.
- base branch 대비 commit이 없으면 PR을 만들지 않는다.
- push 또는 PR 생성 전 사용자 승인을 받는다.
- `PR 만들어줘` 요청을 받으면 현재 브랜치의 변경사항을 기준으로 PR 초안을 작성한다.
- 단, 초안 작성과 실제 push/PR 생성은 분리한다.

## 절차

1. `.github/PULL_REQUEST_TEMPLATE.md`를 읽는다.
2. `product-service/CLAUDE.md`를 읽는다.
3. `product-service/.claude/rules/architecture.md`, `product-service/.claude/rules/product-api.md`, `product-service/.claude/rules/testing.md`, `product-service/.claude/rules/git-workflow.md`를 읽는다.
4. 현재 branch를 확인한다.
5. 사용자가 다른 base를 지정하지 않았다면 `develop`과 비교한다.
6. commit과 diff를 확인한다.
7. 모듈 build를 실행한다. `build`는 `product-service/src/main` 컴파일, checkstyle, `product-service/src/test` 테스트를 포함하므로 `test`만 따로 실행하는 것으로 대체하지 않는다.

   ```bash
   cd product-service
   ./gradlew clean build --no-daemon
   ```

   Windows:

   ```powershell
   cd product-service
   .\gradlew.bat clean build --no-daemon
   ```

   CI와 유사하게 확인해야 하면 root `.github/workflows/reusable-build.yml`의 DB 환경변수를 맞춘 뒤 실행한다.

   ```powershell
   cd C:\programmers_prj\beadv6_6_3JMT_BE\product-service

   $env:DB_HOST="localhost"
   $env:DB_PORT="5432"
   $env:DB_NAME="prompthub_test"
   $env:DB_USERNAME="test"
   $env:DB_PASSWORD="test"

   .\gradlew.bat clean build --no-daemon
   ```

   로컬에 CI와 동일한 PostgreSQL DB/계정이 없어서 실패한 경우, 실패 원인과 CI 영향 가능성을 PR 본문에 적는다.

8. 실제 변경 내용과 테스트 결과로 PR template을 채운다.
9. PR template의 체크리스트는 `.github/PULL_REQUEST_TEMPLATE.md`에서 직접 가져온다.
10. 체크리스트 체크 여부는 product-service rules와 실제 diff를 기준으로 판단한다.
11. PR이 issue를 완료한다면 `Closed #<issue-number>`를 포함한다.
12. 최종 title/body를 사용자에게 보여주고 승인받는다.
13. 승인 후 branch를 push하고 `gh pr create`를 실행한다.

## 자동 작성 범위

PR 초안은 아래 정보를 기반으로 자동 작성한다.

- 현재 브랜치명
- base branch 대비 commit
- base branch 대비 diff
- 변경 파일 목록
- 테스트 실행 결과 또는 테스트 미실행 사유
- 관련 issue 번호

아래 작업은 사용자 승인 전 수행하지 않는다.

- `git push`
- `gh pr create`
- PR 본문 확정

## 체크리스트 작성 규칙

- 체크리스트 항목을 이 skill 문서에 복사해서 고정하지 않는다.
- 항상 현재 `.github/PULL_REQUEST_TEMPLATE.md`의 체크리스트를 직접 읽는다.
- Product Service 작업에서는 `architecture.md`, `product-api.md`, `git-workflow.md` 기준으로 체크 여부를 판단한다.
- 실제 diff와 테스트 결과로 확인 가능한 항목만 `[x]`로 표시한다.
- 문서 작업처럼 코드 구현이 없는 경우에는 억지로 체크하지 말고, PR 본문에 `문서 변경으로 해당 없음`처럼 짧게 적는다.
- 체크리스트 항목과 product-service rules가 충돌하거나 애매하면 사용자에게 확인한다.

## PR 제목

commit message 스타일을 사용한다.

```text
feat: implement product public query APIs
```

## 본문 규칙

- PR template placeholder를 그대로 남기지 않는다.
- 실패하거나 생략한 테스트는 숨기지 않고 적는다.
- schema, DDL, 프론트 응답 호환성 결정이 있으면 명시한다.
- datasource, migration, ID type 변경이 있으면 CI risk를 적는다.
