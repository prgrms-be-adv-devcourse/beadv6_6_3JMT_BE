# product-service `GetProductContent` gRPC 통합 변경 요청서

## 1. 요청 목적

order-service가 사용하는 다음 세 상품 조회 RPC를 장기적으로 하나의 `GetProductContent` 계약으로 통합해 주세요.

- 주문 생성: `GetOrderSnapshots`
- 장바구니 추가·조회: `GetCartSnapshots`
- 구매 완료 상품 조회·다운로드 확인: `GetProductContent`

이번 order-service 작업에서는 공유 proto와 product-service를 수정하지 않습니다. order-service 내부 어댑터의 호출 메타데이터만 타입화하며, 실제 RPC 전환은 이 요청서의 제공자 변경이 배포된 뒤 별도 작업으로 진행합니다.

## 2. 현재 코드 확인 결과

`ProductInternalService`의 세 조회는 모두 아래와 같이 같은 상품 선택 규칙을 사용합니다.

```java
resolveFamilyRepresentatives(productIds, ProductFamily::currentOnSale)
```

따라서 세 기능이 확인하는 “유효한 상품”의 현재 기준은 동일합니다.

- 요청한 상품이 존재해야 합니다.
- 상품 패밀리에서 현재 판매 중인 대표 상품을 선택할 수 있어야 합니다.

차이는 검증 규칙이 아니라 반환 투영과 부가 조회입니다.

| 호출 목적 | 현재 응답 | 추가 처리 |
|---|---|---|
| 주문 생성 | 판매자 ID, 제목, 유형, 금액, 모델 | 없음 |
| 장바구니 | 판매자 ID·닉네임, 제목, 유형, 금액, 썸네일 | 판매자 닉네임 조회 |
| 구매 후 조회 | 상품 ID, 전달 콘텐츠 | 파일 상품은 presigned URL 생성 |

현재 배치 조회인 주문·장바구니는 찾지 못한 상품을 결과에서 제외하지만, 단건 콘텐츠 조회는 `NOT_FOUND`를 반환합니다. 통합 구현에서도 이 차이를 의도적으로 보존하거나, 변경하기 전에 소비자와 별도 합의해야 합니다.

## 3. 요청하는 계약 방향

하나의 RPC에 조회 목적을 명시하고 목적별 응답을 `oneof`로 구분하는 방식을 요청합니다.

```proto
enum ProductContentPurpose {
  PRODUCT_CONTENT_PURPOSE_UNSPECIFIED = 0;
  ORDER_CREATION = 1;
  CART = 2;
  PURCHASED_CONTENT = 3;
}

message ProductContentQuery {
  ProductContentPurpose purpose = 1;
  repeated string product_ids = 2;
}

message ProductContentItem {
  string requested_product_id = 1;

  oneof payload {
    ProductOrderMetadata order = 2;
    ProductCartMetadata cart = 3;
    PurchasedProductContent purchased_content = 4;
  }
}

message ProductContentResult {
  repeated ProductContentItem products = 1;
}
```

목적별 필드 요구사항은 다음과 같습니다.

### `ORDER_CREATION`

- `product_id`
- `seller_id`
- `title`
- `product_type`
- `amount`
- `model`

콘텐츠, 외부 URL, 파일 URL, presigned URL은 반환하지 않습니다.

### `CART`

- `product_id`
- `seller_id`
- `seller_nickname`
- `title`
- `product_type`
- `amount`
- `thumbnail_url`

콘텐츠, 외부 URL, 파일 URL, presigned URL은 반환하지 않습니다.

### `PURCHASED_CONTENT`

- `product_id`
- 상품 유형에 따른 전달 값
  - `PROMPT`: prompt content
  - `PPT`, `EXCEL`: presigned download URL
  - `NOTION`: external URL

이 목적에서만 전달 콘텐츠를 계산하고 반환합니다.

## 4. 호환성 요구사항

현재 `GetProductContentRequest.product_id = 1`, `GetProductContentResponse.product_id = 1`, `content = 2`가 이미 사용 중이므로 필드 번호나 타입을 재사용하면 안 됩니다.

권장 배포 순서는 다음과 같습니다.

1. 공유 proto에 새 목적·응답 타입을 추가합니다.
2. 기존 세 RPC를 유지한 채 product-service가 통합 계약을 함께 제공합니다.
3. product-service 계약 테스트에서 기존 RPC와 신규 통합 RPC를 모두 검증합니다.
4. product-service 배포 후 order-service가 생성 코드를 갱신하고 어댑터의 실제 호출을 통합 RPC로 전환합니다.
5. 모든 소비자 전환과 관측 기간이 끝난 뒤 기존 RPC를 deprecated 처리합니다.
6. 기존 RPC 삭제는 별도의 breaking-change 버전에서 진행합니다.

기존 `GetProductContent` 메시지를 확장해 같은 RPC 이름을 유지해야 한다면 다음 조건이 필요합니다.

- 기존 필드 번호 1, 2를 보존합니다.
- 신규 필드는 사용하지 않은 번호에만 추가합니다.
- `purpose` 미지정 요청은 기존 단건 콘텐츠 조회로 처리합니다.
- 신규 order-service는 목적과 상품 ID 목록을 명시합니다.
- 기존 응답 필드는 구 소비자 전환이 끝날 때까지 채웁니다.

새 메시지 타입으로 RPC 입출력 타입을 즉시 교체하는 방식은 구 클라이언트와 wire 호환성을 보장하지 못하므로 피해야 합니다. 깨끗한 계약이 더 중요하다면 `GetProductContentV2`를 추가한 뒤 전환 완료 후 이름을 정리하는 방식을 권장합니다.

## 5. 서버 동작 요구사항

- 세 목적 모두 `ProductFamily::currentOnSale`을 사용해 현재와 같은 상품 유효성 기준을 적용합니다.
- 요청 순서와 응답 순서를 동일하게 유지합니다.
- 같은 상품 ID가 여러 번 들어오면 현재 배치 RPC와 동일한 중복 처리 규칙을 유지합니다.
- `ORDER_CREATION`, `CART`에서는 deliverable 계산과 presigned URL 생성을 수행하지 않습니다.
- `CART`에서만 판매자 닉네임을 조회합니다.
- `PURCHASED_CONTENT`는 단건만 허용하거나, 배치를 지원한다면 presigned URL 생성 비용과 실패 처리 규칙을 명시합니다.
- 목적과 다른 `oneof` payload를 채우지 않습니다.

## 6. 오류 계약 요청

| 조건 | gRPC status |
|---|---|
| UUID 형식 오류, 목적 누락, 허용하지 않은 상품 수 | `INVALID_ARGUMENT` |
| 단건 `PURCHASED_CONTENT` 대상 없음 | `NOT_FOUND` |
| 판매자·스토리지 등 하위 서비스 일시 장애 | `UNAVAILABLE` |
| 예상하지 못한 서버 오류 | `INTERNAL` |

주문·장바구니 배치에서 유효하지 않은 상품을 제외하는 현재 동작을 유지할 경우, 요청 ID와 응답 ID의 차이로 소비자가 실패 상품을 판별할 수 있어야 합니다. 전체 요청을 실패시키는 정책으로 변경한다면 order-service 주문·장바구니 동작이 달라지므로 별도 합의가 필요합니다.

현재 product-service의 `GetProductContent` gRPC 구현은 모든 예외를 `NOT_FOUND`로 변환합니다. 통합 시 스토리지 오류나 내부 오류까지 상품 없음으로 오인하지 않도록 위 상태 매핑을 적용해 주세요.

## 7. 권한 경계

`PURCHASED_CONTENT`라는 목적 값 자체는 구매 권한의 증명이 아닙니다. 호출자가 목적을 임의로 지정할 수 있기 때문입니다.

현재 구매·결제·소유권 검증은 order-service가 완료한 뒤 `GetProductContent`를 호출합니다. 이번 통합에서도 이 책임은 order-service에 유지합니다. product-service에서도 권한을 독립적으로 강제해야 한다면 신뢰할 수 있는 주문 증명 또는 order-service 조회 계약이 추가로 필요하므로 별도 보안 설계가 필요합니다.

## 8. product-service 완료 조건

- [ ] 공유 proto 변경이 기존 필드 번호를 보존합니다.
- [ ] 신규 목적 enum과 목적별 `oneof` 응답이 생성됩니다.
- [ ] 세 목적이 같은 `currentOnSale` 선택 규칙을 사용합니다.
- [ ] 주문·장바구니 응답에 전달 콘텐츠가 포함되지 않습니다.
- [ ] 구매 후 조회에서만 deliverable 또는 presigned URL을 생성합니다.
- [ ] 목적별 필수 필드와 상품 수를 검증합니다.
- [ ] 오류별 gRPC status 계약 테스트가 있습니다.
- [ ] 기존 세 RPC가 소비자 전환 기간 동안 정상 동작합니다.
- [ ] order-service 개발자가 사용할 proto 버전과 product-service 배포 순서가 공유됩니다.

## 9. order-service 후속 작업

product-service 변경이 배포되면 order-service는 다음을 별도 PR로 진행합니다.

1. 공유 proto 생성 코드를 갱신합니다.
2. `ProductGrpcClientAdapter`의 세 공개 포트 메서드는 유지합니다.
3. 세 메서드의 wire 호출만 통합 `GetProductContent`로 교체합니다.
4. `ProductGrpcOperation`의 실제 gRPC 메서드 메타데이터를 통합 RPC에 맞게 조정합니다.
5. 목적별 request·response 매핑, deadline, circuit breaker, bulkhead, status 매핑 테스트를 갱신합니다.
6. 콘텐츠 호출 전 order-service의 구매·소유권 검증이 유지되는지 회귀 테스트합니다.
