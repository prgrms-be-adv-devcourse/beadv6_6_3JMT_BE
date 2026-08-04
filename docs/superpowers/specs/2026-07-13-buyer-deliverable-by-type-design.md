# 구매자 산출물 유형별 전달 (Spec C)

- 작성일: 2026-07-13
- 대상 서비스: product-service (단독)
- 상태: 설계 확정 대기(사용자 리뷰)
- 이슈: #309
- 선행: Spec A(#306, 유형별 필드 `content`/`file_url`/`external_url`), Spec B(#308, presigned 업로드). 같은 브랜치 `feat/#306-product-type-fields`에서 이어서.

## 1. 배경 / 목적

구매 후 산출물 전달용 gRPC `GetProductContent`(`ProductInternalService.getProductContent`,
현재 ON_SALE 최신 버전 반환)는 응답 `content`에 **프롬프트 본문만** 담는다. Spec A로 유형별
필드(`file_url`, `external_url`)가 생겼지만, 구매자는 여전히 PROMPT의 content만 받을 수 있고
PPT/EXCEL(파일)·NOTION(외부 링크) 산출물은 전달되지 않는다.

이 spec은 `getProductContent`가 **유형별 산출물을 해석해 응답 `content`에 채우도록** 바꾼다.

## 2. 범위

### 이 spec(C)이 다루는 것 — product-service 단독
- `ProductInternalService.getProductContent`가 productType에 따라 산출물을 해석해 반환
- PPT/EXCEL 파일은 presigned 다운로드 URL로 변환(`StorageClient` 주입)
- 테스트, 내부 API 설명 문서 갱신

### 이 spec이 다루지 않는 것
- **proto 계약 무변경.** `product_query.proto`의 `GetProductContentResponse { product_id, content }`
  구조를 바꾸지 않는다. 응답 필드 이름·구조가 그대로이므로 **order-service 재컴파일·수정이
  필요 없고, 크로스서비스 승인 게이트도 없다.** (필드명을 `deliverable`로 바꾸는 안은 검토했으나
  order-service 소비자까지 바뀌어 제외 — `content` 유지로 결정.)
- order-service의 buyer 조회/다운로드 UI 렌더링: order 소유. 무변경으로도 값은 전달된다.
  (소비자는 자기가 저장한 `OrderProduct.productType`으로 텍스트/다운로드링크/외부링크를 구분 렌더링.)

## 3. 핵심 변경 — `ProductInternalService.getProductContent`

현재: `currentOnSale` 대표 row를 뽑아 `ProductContentResponse.from(productId, product)`(=`product.getContent()`) 반환.

변경 후: 대표 row는 그대로 뽑되, productType에 따라 산출물을 해석한다.

```java
Product product = resolved.get(productId);
if (product == null) {
    throw new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND);
}
String deliverable = switch (product.getProductType()) {
    case PROMPT     -> product.getContent();
    case PPT, EXCEL -> presignIfPresent(product.getFileUrl());   // presigned 다운로드 URL
    case NOTION     -> product.getExternalUrl();                 // 외부 노션 링크 원문
};
return new ProductContentResponse(productId, deliverable);
```

- `ProductInternalService`에 `StorageClient`를 주입 추가(파일 presign용).
- `presignIfPresent(key)`: key가 null/blank면 그대로 반환(방어), 아니면
  `storageClient.generatePresignedDownloadUrl(key)`.
- `ProductContentResponse`(record `(UUID productId, String content)`)는 형태 그대로. 서비스가
  해석한 값을 생성자로 넣는다(presign은 서비스 책임이므로 DTO의 `from` 대신 서비스에서 구성).

## 4. gRPC / 소비자 (무변경 확인)

- gRPC 매핑(`ProductQueryGrpcService` → `GetProductContentResponse.content`)은 그대로.
- order-service(`ProductGrpcClientAdapter.getProductContent` → `ProductContent(productId, content)`,
  `OrderService`의 `OrderContentResponse(..., content())`)는 코드 변경 없음.

## 5. 테스트

- `ProductInternalServiceTest`: `getProductContent`를 유형별로 검증
  - PROMPT → `content` 문자열 반환
  - PPT/EXCEL → `file_url` 키가 presigned URL로 변환돼 반환(StorageClient 목킹)
  - NOTION → `external_url` 원문 반환
  - 대상 row는 `currentOnSale` 기준(기존 동작 유지)
- Build: 루트에서 `.\gradlew.bat :product-service:build --no-daemon`.

## 6. docs 동기화 (`sync-product-docs`)

- `docs/api-spec/product.md`의 내부 API(`/internal` 또는 gRPC content 설명)에 "`content`는 유형별
  산출물(PROMPT=본문 텍스트 / PPT·EXCEL=파일 presigned URL / NOTION=외부 링크)"임을 한 줄 명시.
  proto 계약 구조는 무변경.

## 7. 작업 순서 (product-service CLAUDE.md 규칙)

이슈(#309) → 같은 브랜치 계속 → 구현 → 테스트 → docs 동기화 → 규칙 검증 → 커밋.
(PR/이슈 close는 A·B·C 완료 후 일괄)
