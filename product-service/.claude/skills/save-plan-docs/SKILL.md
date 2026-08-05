---
name: save-plan-docs
description: product-service 작업에서 브레인스토밍 설계 문서(spec)와 구현 계획 문서(plan)의 저장 위치를 정한다. spec(-design.md)은 저장소 루트 `docs/superpowers/specs/`에, plan(-plan.md)은 `docs/records/plan/product/`에 저장하며 둘 다 git으로 추적·공유한다. "설계 문서 저장", "plan 저장", "스펙 문서 작성" 시점에 사용한다.
---

# Plan/Spec 문서 저장 위치 Skill

## 절차

1. **설계 문서(spec, `-design.md`)**: superpowers 기본 경로인 저장소 루트
   `docs/superpowers/specs/`에 저장하고 **git으로 추적·공유한다.**
2. **구현 계획 문서(plan, `-plan.md`)**: `docs/records/plan/product/`에 저장한다.
   git으로 추적·공유한다(product-service 다른 계획 문서들과 같은 위치).
   ⚠ **superpowers 기본 경로인 `docs/superpowers/plans/`에 두지 않는다.**
   계획 문서는 `docs/records/plan/{서비스}/`가 저장소 전체 규약이다
   (`docs/README.md`, order·payment·user·product 4개 서비스 공통).
3. 파일명 규칙:
   - 설계 문서: `YYYY-MM-DD-<topic>-design.md`
   - 구현 계획 문서: `YYYY-MM-DD-<topic>-plan.md`
4. 잘못된 위치에 저장된 문서를 발견하면 위 기준 위치로 옮긴다. 이슈/PR이 그 경로를
   참조하고 있으면 함께 갱신한다.

## 문서 생명주기

- **설계 문서(`-design.md`)**: 삭제하지 않고 보관한다. "왜 이렇게 정했는가"의 근거
  기록이다.
- **구현 계획 문서(`-plan.md`)**: 구현이 끝나도 삭제하지 않고 보관한다 — "무엇을
  어떤 순서로 구현했는지"의 공유 기록이다. 단, 본문의 코드 스니펫·줄번호는 작성
  시점 기준이라 이후 코드와 다를 수 있다.

## 엣지 케이스

- 다른 서비스(user/order/settlement 등) 작업 중이면 이 skill을 적용하지 않는다 —
  `product-service/` 작업에만 해당한다.
