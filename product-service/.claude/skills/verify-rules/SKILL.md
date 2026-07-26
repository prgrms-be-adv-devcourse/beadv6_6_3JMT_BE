---
name: verify-rules
description: 현재 브랜치의 변경 코드를 product-service 규칙 4종(architecture, product-api, testing, git-workflow) + 공용 가이드(clean-architecture, controller-exception) 기준으로 검증한다. 위반이 있으면 위반 목록을 보여준다. "룰 검증해줘", "컨벤션 어긴 데 없는지 봐줘"라는 요청이나 create-github-pr 직전에 사용한다. 코드를 수정하지 않는다.
---

# Verify Rules Skill

## 절차

1. 이번 브랜치의 diff를 가져온다.

```bash
git diff develop...HEAD
```

2. `.claude/rules/architecture.md`, `.claude/rules/product-api.md`, `.claude/rules/testing.md`,
   `.claude/rules/git-workflow.md`를 각각 기준으로 diff를 검토한다. 추가로 레포 루트 공용
   가이드 `docs/guides/clean-architecture.md`, `docs/guides/controller-exception.md`도
   같이 확인한다 — product-service 자체 규칙과 취지는 같지만(계층 책임·Controller 얇게
   유지), 다른 서비스(user-service/admin-service 등) PR 리뷰에서 실제로 이 문서명으로
   지적이 나오므로 같은 표현 기준으로도 점검해둔다.

검사 항목 예:
- Controller에 비즈니스 로직/DB 접근이 있는가 (architecture.md 위반)
- 외부 API가 `ApiResult`/`PageResponse`로 안 감싸져 있는가 (product-api.md 위반)
- public setter로 상태를 바꾸는가 (architecture.md 위반)
- 변경 계층에 맞는 테스트가 없는가 (testing.md 위반)
- 브랜치명에 이슈 번호가 없는가 (git-workflow.md 위반)
- infra 계층(`infra/persistence` 등)에 상태 목록·판단 로직 같은 비즈니스 규칙이
  하드코딩돼 있는가 (`docs/guides/clean-architecture.md` 3번 — infra는 "비즈니스 규칙"을
  담으면 안 됨)
- Controller가 값 검증(`@Valid`) 범위를 넘어 분기·계산·상태 판단(예: switch로 상태값
  파싱 후 예외 처리)을 직접 하는가 (`docs/guides/controller-exception.md` 1번)

3. 위반을 목록으로 보고한다. 위반이 없으면 "위반 없음"을 명시적으로 보고한다.

## 금지 사항

- 코드를 직접 수정하지 않는다. 위반 보고만 한다.
- 확신 없는 항목을 위반으로 단정하지 않고 "확인 필요"로 표시한다.
