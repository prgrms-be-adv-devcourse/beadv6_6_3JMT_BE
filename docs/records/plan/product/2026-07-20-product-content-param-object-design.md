# Product 도메인 Long Parameter List 정리 — ProductContent 파라미터 객체 설계

- 날짜: 2026-07-20
- 이슈: #405 [REFACTOR] Product 도메인 메서드의 Long Parameter List 정리
- 브랜치: `refactor/#405-product-domain-long-param-list`
- 상태: 사용자 설계 승인 완료 (A안 채택)

## 배경과 문제

`Product.java`의 세 메서드가 "상품 내용" 필드 12개를 매번 개별 파라미터로 반복 수신한다
(Long Parameter List 코드 스멜).

- `create()` — 파라미터 14개 (id, sellerId + 내용 12개)
- `update()` — 파라미터 14개 (내용 12개 + changeReason, isMajor)
- `nextVersion()` — 파라미터 14개 (isMajor + 내용 12개 + changeReason)

문제점:

- 같은 타입(String)이 연달아 나와 호출부에서 순서를 착각해도 컴파일러가 못 잡는다
  (예: `fileUrl` ↔ `externalUrl`).
- 필드 추가/변경 시 세 메서드 시그니처 + 호출부 전부를 고쳐야 한다.
- `validateTypeFields()` 호출과 `imageUrls`/`tags` null→빈리스트 정규화가 세 메서드에
  중복되어 있다.
- `ProductSellerService.updateProduct()`는 같은 12개 나열을 세 분기(draft update /
  major / minor)에 복붙하고 있다.

## 검토한 대안

| 안 | 내용 | 판단 |
|---|---|---|
| **A. 파라미터 객체 (채택)** | 순수 record `ProductContent`로 12개 필드를 묶어 메서드 시그니처만 변경. 엔티티 필드·DB 매핑은 그대로. | 이슈 Suggested direction과 일치, 리스크 최소. Fowler 『Refactoring』의 표준 처방(Introduce Parameter Object). |
| B. `@Embeddable`로 엔티티 내부까지 | 엔티티의 12개 필드 자체를 embedded 객체로 재구성. | 응집도는 최고지만 `getName()` 등 모든 조회부(응답 DTO, 조회 서비스) 수십 곳 수정 — 이슈 scope 초과. 필요 시 A 위에 별도 이슈로 얹을 수 있음. |
| C. 빌더 패턴 | 세 메서드에 빌더 도입. | 순서 실수는 막지만 수정 지점 축소·검증 집중화는 달성 못 함. 미완성 객체 생성 가능성으로 도메인 규칙 강제도 느슨해짐. |

## 설계 (A안)

### 1. 새 타입: `ProductContent` record

- 위치: `product-service/src/main/java/com/prompthub/product/domain/model/vo/ProductContent.java`
  (`domain.model.vo` 패키지 신설)
- 필드 12개, 순서는 기존 시그니처와 동일:
  `productType, name, description, model, amountType, amount, thumbnailUrl, imageUrls,
  content, fileUrl, externalUrl, tags`
- compact constructor에서:
  - `imageUrls`/`tags` null → `new ArrayList<>()` 정규화 (기존 세 메서드의 중복 로직 이동)
  - 유형별 필수 필드 검증 (기존 `Product.validateTypeFields()` 이동):
    PROMPT→`content`만 / PPT·EXCEL→`fileUrl`만 / NOTION→`externalUrl`만.
    위반 시 `ProductException(ProductErrorCode.PRODUCT_TYPE_FIELD_MISMATCH)`.
- 효과: 잘못된 조합의 `ProductContent`는 생성 자체가 불가능 — 검증 누락 원천 차단.

### 2. `Product.java` 시그니처 변경

```java
// Before → After
public static Product create(UUID id, UUID sellerId, /* 내용 12개 */)
    → public static Product create(UUID id, UUID sellerId, ProductContent content)

public void update(/* 내용 12개 */, String changeReason, boolean isMajor)
    → public void update(ProductContent content, String changeReason, boolean isMajor)

public Product nextVersion(boolean isMajor, /* 내용 12개 */, String changeReason)
    → public Product nextVersion(boolean isMajor, ProductContent content, String changeReason)
```

- `changeReason`, `isMajor`는 상품 내용이 아닌 버전 관리 메타데이터이므로 개별 파라미터 유지.
- 12개 필드 대입 중복은 `private void applyContent(ProductContent content)` 헬퍼로 통합
  (create/update는 `this`에, nextVersion은 `next.applyContent(...)`).
- `validateTypeFields()`는 record로 이동했으므로 `Product`에서 삭제.
- 엔티티 필드 선언, DB 컬럼 매핑, 그 외 메서드(stop, approve 등)는 변경 없음.

### 3. 호출부: `ProductSellerService`

- `createProduct()`: 파생값 계산(productType 파싱, amountType 계산, storage key 이동) 후
  `new ProductContent(...)` 1회 → `Product.create(productId, sellerId, content)`.
- `updateProduct()`: `ProductContent`를 **한 번만** 만들어 세 분기(draft update /
  nextVersion major / nextVersion minor)에서 재사용. 기존 3중 복붙 제거.
- 예외 발생 시점 주의: 기존에는 `Product.create()` 안에서 검증했으나 이제
  `new ProductContent(...)` 시점에 검증된다. 서비스 흐름상 동일 지점(저장 전)이므로
  동작 차이 없음.

### 4. 테스트

- 호출부 전체 수정: main 4곳 + 테스트 약 20곳
  (ProductTest, ProductFamilyTest, ProductSellerServiceTest, ProductAdminServiceTest,
  ProductQueryServiceTest, ProductJpaRepositoryTest).
- 테스트 픽스처 헬퍼 도입: 반복되는 `ProductContent` 생성을 static 헬퍼
  (예: `promptContent()`)로 모아 이후 필드 변경 시 한 곳만 수정하게 함.
- 유형별 필드 검증 테스트는 예외 발생 지점이 `new ProductContent(...)`로 이동하므로,
  검증 로직의 새 거처인 `ProductContentTest`(신규)로 옮긴다. 검증하는 규칙(유형별
  필수/금지 필드, 에러 코드)은 기존과 동일하게 유지.
- 기존 테스트가 검증하던 동작(상태 전이, 버전 증가, 에러 코드 등)은 전부 그대로 통과해야 한다.

### 5. 동작 불변 보장 / 완료 기준

- API 계약, DB 스키마, 에러 코드 변경 없음 — 순수 구조 리팩토링.
- docs 영향 없음 (sync-product-docs에서 확인만).
- 완료 기준: `product-service` 기준 `.\gradlew.bat clean build --no-daemon` 통과
  (컴파일 + checkstyle + 전체 테스트).

## 이슈 scope 밖으로 미룬 것

- `@Embeddable`로 엔티티 내부 구조까지 묶는 것 (B안) — 필요해지면 별도 이슈.
- `ProductAdminService` 등 다른 서비스는 create/update/nextVersion을 직접 호출하지
  않으므로 수정 대상 아님 (이슈 본문의 언급과 달리 실제 호출부는 ProductSellerService뿐,
  테스트 제외).
