---
name: save-plan-docs
description: product-service 작업에서 브레인스토밍 설계 문서(spec)나 구현 계획 문서(plan)를 저장할 때 사용한다. superpowers:brainstorming/writing-plans 등이 기본 경로(저장소 루트의 `docs/superpowers/specs|plans/`)에 저장하려 할 때, 대신 `product-service/docs/plans/`에 저장하도록 강제한다. "설계 문서 저장", "plan 저장", "스펙 문서 작성" 시점에 사용한다.
---

# Plan/Spec 문서 저장 위치 Skill

## 절차

1. product-service 관련 브레인스토밍 설계 문서(spec)나 구현 계획 문서(plan)를 저장할 때는
   저장소 루트의 `docs/superpowers/specs/` 또는 `docs/superpowers/plans/`가 아니라 항상
   `product-service/docs/plans/`에 저장한다.
2. 파일명 규칙은 기존 브레인스토밍/계획 skill과 동일하게 유지한다.
   - 설계 문서: `YYYY-MM-DD-<topic>-design.md`
   - 구현 계획 문서: `YYYY-MM-DD-<topic>-plan.md`
3. 이미 다른 위치(예: 저장소 루트 `docs/superpowers/specs/`)에 잘못 저장된 문서를 발견하면
   `product-service/docs/plans/`로 옮기고, 이슈/PR 등에서 그 경로를 참조하고 있으면 함께
   갱신한다.

## 문서 생명주기

- **설계 문서(`-design.md`)**: 구현이 끝나도 삭제하지 않고 영구 보관한다. 이슈/PR에서 "왜
  이렇게 설계했는지"의 근거 문서로 계속 참조되기 때문이다.
- **구현 계획 문서(`-plan.md`)**: 구현이 모두 끝나고 merge/완료되면 삭제한다. 일회성 실행
  계획이라 완료 후에는 근거로서의 가치가 없다.

## 엣지 케이스

- 다른 서비스(user/order/settlement 등) 작업 중이면 이 skill을 적용하지 않는다 —
  `product-service/` 작업에만 해당한다.
- plan 삭제 시점이 애매하면(부분 merge, 후속 이슈로 이어지는 경우 등) 삭제 전에 사용자에게
  확인한다.
