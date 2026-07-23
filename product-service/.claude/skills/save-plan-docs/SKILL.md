---
name: save-plan-docs
description: product-service 작업에서 브레인스토밍 설계 문서(spec)와 구현 계획 문서(plan)의 저장 위치를 정한다. spec(-design.md)은 저장소 루트 `docs/superpowers/specs/`(gitignore — 로컬 보관)에, plan(-plan.md)은 `product-service/docs/plans/`(git 추적 — 공유)에 저장한다. "설계 문서 저장", "plan 저장", "스펙 문서 작성" 시점에 사용한다.
---

# Plan/Spec 문서 저장 위치 Skill

## 절차

1. **설계 문서(spec, `-design.md`)**: superpowers 기본 경로인 저장소 루트
   `docs/superpowers/specs/`에 저장한다. 이 경로는 gitignore되어 있어 로컬에만
   남는다 (커밋하지 않는다).
2. **구현 계획 문서(plan, `-plan.md`)**: `product-service/docs/plans/`에 저장한다.
   git으로 추적·공유한다.
3. 파일명 규칙:
   - 설계 문서: `YYYY-MM-DD-<topic>-design.md`
   - 구현 계획 문서: `YYYY-MM-DD-<topic>-plan.md`
4. 잘못된 위치에 저장된 문서를 발견하면 위 기준 위치로 옮긴다. 이슈/PR이 그 경로를
   참조하고 있으면 함께 갱신한다.

## 문서 생명주기

- **설계 문서(`-design.md`)**: 로컬 영구 보관 (삭제하지 않는다). git에는 올리지
  않으므로, 팀 공유가 필요한 결정·계약(예: 타 서비스와의 이벤트 계약 제안)은
  이슈/PR 본문에 발췌해 담는다.
- **구현 계획 문서(`-plan.md`)**: 구현이 끝나도 삭제하지 않고 보관한다 — "무엇을
  어떤 순서로 구현했는지"의 공유 기록이자, git에 남는 유일한 설계 산출물이다.
  단, 본문의 코드 스니펫·줄번호는 작성 시점 기준이라 이후 코드와 다를 수 있다.

## 엣지 케이스

- 다른 서비스(user/order/settlement 등) 작업 중이면 이 skill을 적용하지 않는다 —
  `product-service/` 작업에만 해당한다.
