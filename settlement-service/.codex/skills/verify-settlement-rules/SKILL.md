---
name: verify-settlement-rules
description: Use when 정산 코드 리뷰, "정산 룰 검증", "세틀먼트 컨벤션 확인", 정산 PR 전 규칙 체크를 요청받았을 때.
---

# 정산 규칙 검증 호환 진입점

**REQUIRED SUB-SKILL:** 먼저 `verify-project-changes`를 사용한다.

이 스킬은 정산 규칙을 강조하는 호환 진입점이다. 검증 범위를 정산 경로로 제한하지 않는다.

1. `verify-project-changes`의 절차로 base, head, 전체 `base...HEAD` diff, commit, 작업 트리 변경을 수집한다.
2. 전체 변경 경로에 적용되는 `AGENTS.md`, `CLAUDE.md`, `.claude/rules/*.md`를 매핑한다.
3. 정산 경로에는 다음 규칙을 포함한다.
   - `settlement-service/.claude/rules/clean-architecture.md`
   - `settlement-service/.claude/rules/domain-model.md`
   - `settlement-service/.claude/rules/controller-exception.md`
   - `settlement-service/.claude/rules/code-style.md`
   - `settlement-service/.claude/rules/swagger.md`
   - `settlement-service/.claude/rules/kafka-event.md`
   - `settlement-service/.claude/rules/security.md`
   - `settlement-service/.claude/rules/git-convention.md`
4. commit message의 `Co-Authored-By` trailer와 이슈·PR 초안의 번호 유형, 담백한 문체, 실제 메타데이터도 해당 규칙과 지침에 따라 확인한다.
5. 정산 외 변경은 해당 경로의 프로젝트 규칙으로 검사하고, 규칙이 없으면 일반 정확성·회귀·테스트·보안 검토를 수행한다.
6. `.claude/worktrees/`와 과거 plan·review 산출물은 현재 규칙으로 사용하지 않는다.
7. 결과는 `verify-project-changes`의 `RULE / SCOPE / STATUS / FINDINGS` 형식을 유지한다. 정산 규칙 결과를 먼저 보여줄 수 있지만 다른 경로 결과와 `coverage-completeness`를 생략하지 않는다.

규칙이나 증거를 확인하지 못한 항목은 `UNVERIFIED`로 보고하며 `PASS`로 간주하지 않는다. 별도 수정 요청이 없으면 코드를 바꾸지 않는다.
