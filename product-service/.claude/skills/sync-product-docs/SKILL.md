---
name: sync-product-docs
description: product-service의 controller/DTO, ProductErrorCode, @Entity, 도메인 모델 변경을 루트 docs(api-spec/product.md, error-codes.md의 PRODUCT 섹션, erd/schema.md의 Product Service 섹션, domain-glossary/product.md)와 대조하고 사용자 승인 하에 동기화한다. create-github-pr 실행 전 항상 먼저 호출된다.
---

# Sync Product Docs Skill

## 대상 문서 매핑

| 코드 변경 유형 | 대조 대상 | 비고 |
|---|---|---|
| controller/DTO 변경 | `docs/api-spec/product.md` | 전체 파일이 product 전용 |
| `ProductErrorCode` 신규/변경 | `docs/error-codes.md`의 "## 상품 (PRODUCT)" 섹션만 | 다른 서비스 섹션 절대 수정 금지 |
| `@Entity` 필드 변경 | `docs/erd/schema.md`의 "## Product Service" 섹션만 | 다른 서비스 섹션 절대 수정 금지 |
| 도메인 모델/상태(enum 등) 변경 | `docs/domain-glossary/product.md` | 전체 파일이 product 전용 |

## 절차

1. 이번 브랜치의 변경 파일 중 위 4가지 유형에 해당하는 변경을 찾는다.

```bash
git diff develop...HEAD --name-only -- product-service/src/main/java
```

2. 각 변경을 대응하는 문서(해당 섹션)와 대조한다.
3. 불일치를 발견하면 목록으로 사용자에게 보고한다. 코드가 맞고 문서가 틀렸는지, 문서가
   맞고 코드가 틀렸는지 판단 근거를 함께 제시한다.
4. **사용자 승인을 받은 항목만** 문서를 수정한다. 승인 없이 자동으로 고치지 않는다.
5. 코드가 틀린 경우(문서가 맞음)로 판단되면 문서는 건드리지 않고 코드 수정이 필요하다고만
   보고한다(자동 코드 수정 안 함).

## 금지 사항

- `docs/error-codes.md`, `docs/erd/schema.md`에서 product-service 소관 섹션 외의 다른 서비스
  섹션에는 어떤 diff도 만들지 않는다.
- 사용자 승인 없이 `docs/` 파일을 커밋하지 않는다.
