# GetProductContent gRPC 통합 설계

**작성일:** 2026-07-20

**대상 서비스:** product-service, order-service

**상태:** 검토 요청

## 1. 배경

`ProductQueryService`는 order-service를 위해 다음 세 RPC를 제공한다.

- `GetOrderSnapshots`
- `GetCartSnapshots`
- `GetProductContent`

세 RPC의 product-service 내부 상품 선택 규칙은 동일하다. 모두 요청 상품 ID를 상품 family로 해석한 다음 `ProductFamily.currentOnSale()`로 현재 `ON_SALE` 버전을 선택한다.

```java
resolveFamilyRepresentatives(productIds, ProductFamily::currentOnSale)
```

현재 메서드별 차이는 상품 유효성 검증이 아니라 선택한 상품을 어떤 응답으로 변환하는지에 있다.

| 기존 RPC | 호출 시점 | 공통 상품 선택 이후 처리 |
|---|---|---|
| `GetOrderSnapshots` | 주문 생성 전 | 주문용 상품·판매자·가격 메타데이터 반환 |
| `GetCartSnapshots` | 장바구니 추가 및 조회 | 장바구니 표시용 메타데이터와 판매자 닉네임 반환 |
| `GetProductContent` | 결제 후 콘텐츠 접근 | 상품 유형에 따라 콘텐츠 또는 URL 반환 |

상품 조회와 `ON_SALE` 검증을 하나의 RPC 진입점으로 통합하되, 유료 콘텐츠가 주문·장바구니 응답에 포함되지 않도록 요청 목적과 응답 payload를 타입으로 구분한다.

## 2. 목표

- order-service가 사용하는 상품 gRPC 진입점을 `GetProductContent` 하나로 통합한다.
- 모든 호출 목적에서 기존 `currentOnSale()` 상품 선택 규칙을 유지한다.
- `purpose`는 상품 선택 규칙이 아니라 응답 projection만 결정한다.
- 주문·장바구니 호출에서는 실제 콘텐츠와 다운로드 URL을 반환하지 않는다.
- 구매 후 콘텐츠 호출은 기존 order-service의 주문 소유권·결제 상태 검증을 통과한 뒤에만 실행한다.
- 서버를 먼저 배포하고 소비자를 후속 단계에서 전환할 수 있도록 기존 wire field 번호와 기존 RPC를 전환 기간 동안 유지한다.
- 주문 생성, 장바구니, 콘텐츠 조회의 현재 애플리케이션 동작을 변경하지 않는다.
- 현재 모든 예외를 `INTERNAL` 또는 `NOT_FOUND`로 뭉뚱그리는 gRPC 처리만 원인에 맞게 정규화한다.

## 3. 비목표

- 상품 상태 정책을 변경하지 않는다. 장바구니에 `STOPPED` 상품 정보를 반환하는 기능은 이 작업에 포함하지 않는다.
- 콘텐츠 접근 권한 검증을 product-service로 이동하지 않는다.
- order-service의 `ProductClient` 애플리케이션 포트를 하나의 범용 메서드로 합치지 않는다.
- seller nickname 소유권이나 product-service와 user-service의 연동 구조를 재설계하지 않는다.
- `GetProductsByIds`, `GetSellerStats` 계약은 변경하지 않는다.
- 상품 DB, Kafka 이벤트 또는 주문 DB 스키마를 변경하지 않는다.

## 4. 설계 결정

### 4.1 단일 RPC와 목적 구분

최종적으로 order-service는 다음 RPC만 호출한다.

```protobuf
rpc GetProductContent(GetProductContentRequest) returns (GetProductContentResponse);
```

호출 목적은 `ProductContentPurpose`로 구분한다.

```protobuf
enum ProductContentPurpose {
  PRODUCT_CONTENT_PURPOSE_UNSPECIFIED = 0;
  ORDER_SNAPSHOT = 1;
  CART_SNAPSHOT = 2;
  PURCHASED_CONTENT = 3;
}
```

- `ORDER_SNAPSHOT`: 주문 생성에 필요한 상품 스냅샷을 반환한다.
- `CART_SNAPSHOT`: 장바구니 추가·조회에 필요한 상품 스냅샷을 반환한다.
- `PURCHASED_CONTENT`: 구매 후 실제 상품 콘텐츠를 반환한다.
- `PRODUCT_CONTENT_PURPOSE_UNSPECIFIED`: 전환 기간의 구형 콘텐츠 클라이언트 요청으로만 허용한다.

`purpose`는 접근 권한이 아니다. order-service가 수행하는 주문 소유권·결제 상태 검증을 대체하지 않는다.

`GetProductContent`라는 이름이 메타데이터까지 포괄하게 되는 단점은 있으나, 이 설계에서는 요청된 단일 RPC 이름을 유지한다. 대신 모든 신규 호출에 `purpose`를 필수로 하여 계약 의미를 명시한다.

### 4.2 하위 호환 요청 계약

기존 `GetProductContentRequest.product_id = 1`은 그대로 유지한다. 필드 타입이나 번호를 바꾸면 기존 클라이언트와 wire 호환성이 깨지므로 재사용하지 않는다.

```protobuf
message GetProductContentRequest {
  // PURCHASED_CONTENT와 구형 콘텐츠 요청에서 사용한다.
  string product_id = 1;

  // ORDER_SNAPSHOT과 CART_SNAPSHOT에서 사용한다.
  repeated string product_ids = 2;

  ProductContentPurpose purpose = 3;
}
```

요청 조합은 다음 규칙으로 검증한다.

| purpose | `product_id` | `product_ids` | 처리 |
|---|---:|---:|---|
| `ORDER_SNAPSHOT` | 비어 있어야 함 | 1개 이상 | 주문 스냅샷 배치 조회 |
| `CART_SNAPSHOT` | 비어 있어야 함 | 1개 이상 | 장바구니 스냅샷 배치 조회 |
| `PURCHASED_CONTENT` | 필수 | 비어 있어야 함 | 구매 콘텐츠 단건 조회 |
| `UNSPECIFIED` | 필수 | 비어 있어야 함 | 구형 콘텐츠 요청으로 처리 |

그 밖의 조합, 빈 문자열, UUID 형식 오류는 `INVALID_ARGUMENT`로 반환한다.

### 4.3 응답 payload 분리

주문·장바구니 메타데이터와 유료 콘텐츠가 같은 결과에 동시에 포함되지 않도록 `oneof`를 사용한다.

```protobuf
message GetProductContentResponse {
  // 구형 콘텐츠 클라이언트 호환용이다.
  string product_id = 1 [deprecated = true];
  string content = 2 [deprecated = true];

  // 신규 클라이언트는 results만 사용한다.
  repeated ProductContentResult results = 3;
}

message ProductContentResult {
  oneof payload {
    ProductOrderSnapshot order_snapshot = 1;
    ProductCartSnapshotMessage cart_snapshot = 2;
    PurchasedProductContent purchased_content = 3;
  }
}

message PurchasedProductContent {
  string product_id = 1;
  string content = 2;
}
```

기존 `ProductOrderSnapshot`과 `ProductCartSnapshotMessage`를 새 응답에서 재사용한다. 따라서 기존 필드 의미와 order-service DTO mapping을 유지할 수 있다.

응답 규칙은 다음과 같다.

- `ORDER_SNAPSHOT`: 모든 `results`가 `order_snapshot`이어야 한다.
- `CART_SNAPSHOT`: 모든 `results`가 `cart_snapshot`이어야 한다.
- `PURCHASED_CONTENT`: `results`가 정확히 하나이며 `purchased_content`여야 한다.
- `UNSPECIFIED`: 구형 응답 필드 `product_id`, `content`를 채운다. 전환 안전성을 위해 `purchased_content` 결과도 함께 채운다.
- 주문·장바구니 응답에서는 구형 `content`와 `purchased_content`를 설정하지 않는다.
- 배치 결과 순서는 기존처럼 요청 ID 순서를 따른다.
- 배치 요청에서 존재하지 않거나 현재 판매 버전이 없는 상품은 기존처럼 결과에서 제외한다. 주문 누락 검증은 order-service의 `OrderPolicyService`가 계속 담당한다.

### 4.4 product-service 처리 흐름

`ProductQueryGrpcService.getProductContent()`가 요청을 검증한 후 목적에 따라 기존 `ProductInternalUseCase` 메서드를 호출한다.

```text
GetProductContent
  ├─ 요청 필드와 purpose 검증
  ├─ ORDER_SNAPSHOT
  │    └─ productInternalUseCase.getOrderSnapshots(productIds)
  ├─ CART_SNAPSHOT
  │    └─ productInternalUseCase.getCartSnapshots(productIds)
  └─ PURCHASED_CONTENT 또는 legacy UNSPECIFIED
       └─ productInternalUseCase.getProductContent(productId)
```

세 application 메서드는 이미 공통 `resolveFamilyRepresentatives(..., ProductFamily::currentOnSale)`를 사용한다. gRPC 통합을 이유로 서로 다른 반환 타입을 억지로 하나의 application DTO로 합치지 않는다. 이 결정은 transport 계약 통합과 application 유스케이스의 의미를 분리한다.

기존 gRPC handler인 `getOrderSnapshots()`와 `getCartSnapshots()`는 전환 기간 동안 유지하고 기존 use case에 위임한다. 소비자 전환과 운영 확인이 끝난 뒤 proto의 두 RPC 및 server override를 제거한다.

### 4.5 order-service 처리 흐름

order-service의 `ProductClient` 포트는 다음 메서드를 그대로 유지한다.

```java
List<ProductOrderSnapshot> getOrderSnapshots(List<UUID> productIds);
ProductCartSnapshot getCartSnapshot(UUID productId);
List<ProductCartSnapshot> getCartSnapshots(List<UUID> productIds);
ProductContent getProductContent(UUID productId);
```

도메인과 application 계층은 gRPC 통합 사실을 알 필요가 없다. `ProductGrpcClientAdapter`만 모든 포트 메서드를 단일 gRPC 호출로 변환한다.

| ProductClient 메서드 | gRPC purpose | 요청 필드 | 기대 payload |
|---|---|---|---|
| `getOrderSnapshots(ids)` | `ORDER_SNAPSHOT` | `product_ids` | `order_snapshot` |
| `getCartSnapshot(id)` | `CART_SNAPSHOT` | `product_ids=[id]` | `cart_snapshot` 1개 |
| `getCartSnapshots(ids)` | `CART_SNAPSHOT` | `product_ids` | `cart_snapshot` |
| `getProductContent(id)` | `PURCHASED_CONTENT` | `product_id` | `purchased_content` 1개 |

어댑터는 응답 `oneof` case가 요청 목적과 다르면 정상 응답으로 수용하지 않고 `PRODUCT_SERVICE_UNAVAILABLE`로 변환한다. `getCartSnapshot(id)`에서 결과가 비어 있으면 기존처럼 `PRODUCT_NOT_FOUND`를 반환한다.

### 4.6 콘텐츠 접근 경계

`PURCHASED_CONTENT` 요청 전의 검증 순서는 유지한다.

1. order-service가 주문을 조회한다.
2. 요청 사용자가 주문 소유자인지 검증한다.
3. 주문 상품이 결제 완료되어 콘텐츠 접근 가능한지 검증한다.
4. 검증 성공 후에만 product-service의 `GetProductContent(PURCHASED_CONTENT)`를 호출한다.

product-service는 내부 gRPC 호출이라는 현재 신뢰 경계를 유지한다. `purpose=PURCHASED_CONTENT` 값 자체는 구매 증명으로 간주하지 않는다. 실제 콘텐츠, 외부 URL, presigned URL은 로그에 기록하지 않는다.

## 5. 오류 계약

| 상황 | gRPC status | order-service 처리 |
|---|---|---|
| purpose와 ID 필드 조합 오류 | `INVALID_ARGUMENT` | `PRODUCT_REQUEST_INVALID` |
| UUID 형식 오류 | `INVALID_ARGUMENT` | `PRODUCT_REQUEST_INVALID` |
| 콘텐츠 단건 상품 없음 | `NOT_FOUND` | `PRODUCT_NOT_FOUND` |
| 주문·장바구니 일부 상품 없음 | 정상 응답의 결과 누락 | 기존 application 검증 유지 |
| 저장소·seller-service·storage 실패 | `INTERNAL` | `PRODUCT_SERVICE_UNAVAILABLE` |
| deadline 초과 | `DEADLINE_EXCEEDED` | `PRODUCT_SERVICE_UNAVAILABLE` |
| 알 수 없는 payload case | 클라이언트 mapping 실패 | `PRODUCT_SERVICE_UNAVAILABLE` |

기존 `ProductGrpcClientAdapter`의 CircuitBreaker와 Bulkhead를 유지한다. `ORDER_SNAPSHOT`은 기존 `productOrderGrpc`, 나머지 목적은 기존 `productQueryGrpc` CircuitBreaker를 사용하여 장애 격리 동작을 보존한다. gRPC method 로그 값은 `GetProductContent`로 통일하되, 별도 `purpose` 필드를 기록해 주문·장바구니·콘텐츠 호출을 구분한다. 콘텐츠 값은 로그 필드에 포함하지 않는다.

## 6. 수정 대상

### 6.1 공유 gRPC 계약

**수정:** `grpc/product/product_query.proto`

- `ProductContentPurpose` enum 추가
- `GetProductContentRequest`에 `product_ids=2`, `purpose=3` 추가
- `GetProductContentResponse`에 `results=3` 추가
- `ProductContentResult`, `PurchasedProductContent` 추가
- 전환 기간에는 `GetOrderSnapshots`, `GetCartSnapshots` RPC 유지
- 소비자 전환 완료 후 두 RPC 제거
- 제거한 field 번호는 다른 의미로 재사용하지 않음

생성된 Java 소스는 직접 수정하지 않는다.

### 6.2 product-service

**수정:** `product-service/src/main/java/com/prompthub/product/infra/grpc/ProductQueryGrpcService.java`

- `getProductContent()`에 purpose 및 요청 필드 검증 추가
- purpose별 기존 use case 호출 분기 추가
- 응답 `oneof` mapping 추가
- 구형 `UNSPECIFIED + product_id` 요청 호환 처리 추가
- `IllegalArgumentException`과 요청 계약 위반을 `INVALID_ARGUMENT`로 mapping
- 콘텐츠 단건 미존재는 `NOT_FOUND` 유지
- 전환 완료 후 `getOrderSnapshots()`, `getCartSnapshots()` override 제거

**유지:** `product-service/src/main/java/com/prompthub/product/application/service/ProductInternalService.java`

- 세 메서드가 이미 공통 `currentOnSale()` resolver를 사용하므로 동작 변경 없음
- 목적별 DTO 생성과 콘텐츠 유형별 deliverable 생성 책임 유지

**테스트 생성:** `product-service/src/test/java/com/prompthub/product/infra/grpc/ProductQueryGrpcServiceTest.java`

- `ORDER_SNAPSHOT` 요청이 order payload만 반환
- `CART_SNAPSHOT` 요청이 cart payload만 반환
- `PURCHASED_CONTENT` 요청이 purchased content payload만 반환
- `UNSPECIFIED + product_id` 구형 요청이 기존 필드와 신규 payload를 함께 반환
- 목적과 ID 필드 조합 오류가 `INVALID_ARGUMENT`
- 콘텐츠 미존재가 `NOT_FOUND`
- 주문·장바구니 결과에서 콘텐츠 필드가 설정되지 않음

**회귀 테스트:** `product-service/src/test/java/com/prompthub/product/application/service/ProductInternalServiceTest.java`

- 기존 `currentOnSale()` 선택, 구버전 ID 해석, 콘텐츠 유형별 결과 테스트 유지

### 6.3 order-service

**수정:** `order-service/src/main/java/com/prompthub/order/infra/grpc/client/product/ProductGrpcClientAdapter.java`

- 네 `ProductClient` 메서드가 모두 stub의 `getProductContent()`를 호출하도록 변경
- 메서드별 purpose와 ID 필드 설정
- `ProductContentResult.PayloadCase` 검증 및 기존 application DTO mapping 추가
- 로그와 metric에 purpose 추가
- 기존 deadline, CircuitBreaker, Bulkhead, gRPC status mapping 유지

**수정:** `order-service/src/test/java/com/prompthub/order/infra/grpc/client/product/ProductGrpcClientAdapterTest.java`

- in-process server stub을 단일 `getProductContent()` 구현으로 변경
- ORDER, CART, PURCHASED_CONTENT 요청 필드 검증
- purpose별 payload mapping 검증
- 잘못된 payload case와 빈 단건 cart 결과 검증
- deadline, circuit-open, bulkhead-full, status mapping 회귀 검증

**수정:** `order-service/src/test/java/com/prompthub/order/infra/grpc/client/product/ProductGrpcBulkheadTest.java`

- 기존 세 RPC override를 단일 `getProductContent()` override로 변경
- purpose가 달라도 동일 product gRPC bulkhead 제한이 적용되는지 검증

**검토 후 필요 시 수정:**

- `ProductGrpcCircuitBreakerTest.java`
- `ProductGrpcMetricsTest.java`
- `ProductGrpcFailurePredicateTest.java`

테스트가 기존 gRPC method 이름을 단언하면 `GetProductContent`와 purpose 조합을 기준으로 갱신한다.

다음 application 계층 파일은 인터페이스와 동작을 유지하므로 수정하지 않는다.

- `order-service/src/main/java/com/prompthub/order/application/client/ProductClient.java`
- `order-service/src/main/java/com/prompthub/order/application/service/order/OrderCommandHandler.java`
- `order-service/src/main/java/com/prompthub/order/application/service/cart/CartService.java`
- `order-service/src/main/java/com/prompthub/order/application/service/order/OrderQueryService.java`
- `order-service/src/main/java/com/prompthub/order/application/service/order/ConfirmDownloadCommandHandler.java`

### 6.4 문서

**수정:**

- `docs/api-spec/product.md`
- `docs/api-spec/order.md`
- `product-service/.claude/rules/product-api.md`

단일 RPC의 purpose별 요청·응답 예시, 오류 status, 콘텐츠 접근 선행 조건과 구형 RPC 제거 일정을 반영한다.

## 7. 배포 및 호환성 전략

### 단계 1: 계약과 서버 확장

1. 기존 field 번호와 기존 세 RPC를 유지한다.
2. `GetProductContent` 메시지에 새 필드와 enum을 추가한다.
3. product-service가 구형·신형 호출을 모두 처리하도록 배포한다.
4. order-service는 아직 기존 `GetOrderSnapshots`, `GetCartSnapshots`를 호출한다.

이 단계는 서버 우선 배포이며 기존 소비자에 영향을 주지 않는다.

### 단계 2: order-service 소비자 전환

1. `ProductGrpcClientAdapter`를 단일 RPC 호출로 변경한다.
2. application 포트와 서비스 코드는 유지한다.
3. 주문 생성, 장바구니 추가·조회, 콘텐츠 조회 회귀 테스트를 실행한다.
4. 운영 metric에서 purpose별 성공률, deadline, `INVALID_ARGUMENT`, payload mismatch를 확인한다.

### 단계 3: 구형 RPC 제거

1. 모든 order-service 인스턴스가 신형 클라이언트인지 확인한다.
2. 일정 기간 `GetOrderSnapshots`, `GetCartSnapshots` 호출량이 0인지 확인한다.
3. proto service에서 두 RPC를 제거한다.
4. product-service의 두 구형 handler를 제거한다.
5. 기존 request/response message는 신규 응답에서 재사용되는 타입을 제외하고 정리한다.

구형 `GetProductContentResponse.product_id=1`, `content=2`는 즉시 삭제하지 않는다. 별도의 major 계약 정리 시 제거하며 번호를 재사용하지 않는다.

## 8. 테스트 및 검증

### 계약·컴파일 검증

```bash
./gradlew :product-service:compileJava :order-service:compileJava
```

기대 결과: protobuf 생성과 두 모듈 Java 컴파일 성공.

### product-service 검증

```bash
./gradlew :product-service:test --tests "com.prompthub.product.infra.grpc.ProductQueryGrpcServiceTest"
./gradlew :product-service:test --tests "com.prompthub.product.application.service.ProductInternalServiceTest"
./gradlew :product-service:test
```

기대 결과: purpose별 응답·오류 계약과 기존 상품 선택 정책 테스트 통과.

### order-service 검증

```bash
./gradlew :order-service:test --tests "com.prompthub.order.infra.grpc.client.product.ProductGrpcClientAdapterTest"
./gradlew :order-service:test --tests "com.prompthub.order.infra.grpc.client.product.ProductGrpcBulkheadTest"
./gradlew :order-service:test --tests "com.prompthub.order.application.service.order.OrderCommandHandlerTest"
./gradlew :order-service:test --tests "com.prompthub.order.application.service.cart.CartServiceTest"
./gradlew :order-service:test --tests "com.prompthub.order.application.service.order.OrderQueryServiceTest"
./gradlew :order-service:test --tests "com.prompthub.order.application.service.order.ConfirmDownloadCommandHandlerTest"
./gradlew :order-service:test
```

기대 결과: 단일 gRPC 호출로 전환해도 주문·장바구니·콘텐츠 application 동작과 복원력 정책이 유지됨.

## 9. 수용 기준

- order-service의 네 `ProductClient` 메서드가 모두 `GetProductContent` RPC만 호출한다.
- 주문·장바구니·콘텐츠 호출이 모두 `currentOnSale()` 상품 선택 규칙을 유지한다.
- 주문·장바구니 응답에 `content`, 외부 URL 또는 presigned URL이 포함되지 않는다.
- `PURCHASED_CONTENT`는 order-service의 기존 접근 검증 뒤에만 호출된다.
- 구형 콘텐츠 클라이언트가 `purpose` 없이 호출해도 전환 기간 동안 정상 동작한다.
- 배치 결과 누락과 단건 `NOT_FOUND` 동작이 현재와 동일하다.
- 기존 CircuitBreaker, Bulkhead, deadline과 오류 mapping이 유지된다.
- product-service와 order-service 전체 테스트가 통과한다.
- 구형 두 RPC는 소비자 전환과 호출량 0 확인 전에는 제거하지 않는다.

## 10. 위험과 대응

### 목적 값만으로 콘텐츠 접근을 허용하는 것으로 오해할 위험

`PURCHASED_CONTENT`는 projection 선택값일 뿐 권한 증명이 아니다. order-service의 소유권·결제 검증을 제거하지 않고 문서와 테스트에 선행 조건을 고정한다.

### 범용 RPC가 잘못된 payload를 반환할 위험

응답을 `oneof`로 정의하고 order-service 어댑터가 기대 payload case를 검증한다. mismatch는 빈 값이나 기본값으로 처리하지 않고 서비스 연동 실패로 처리한다.

### 서버·클라이언트 배포 순서 문제

기존 RPC를 유지한 상태에서 서버를 먼저 확장하고, 클라이언트를 전환한 뒤, 운영 호출량을 확인하고 제거한다.

### 관측 지표가 하나의 RPC 이름으로 합쳐지는 문제

metric과 구조화 로그에 `purpose` label을 추가한다. 콘텐츠 원문과 URL은 label 또는 로그 값으로 사용하지 않는다.

### 단일 RPC 구현이 비대해질 위험

gRPC handler는 요청 검증과 목적별 mapping만 담당한다. 상품 조회, seller 조회, deliverable 생성은 기존 `ProductInternalUseCase` 메서드에 위임한다.

## 11. 롤백

단계 1과 단계 2에서는 구형 RPC가 유지되므로 order-service를 기존 버전으로 되돌리면 된다. product-service의 확장 필드는 구형 클라이언트가 무시하므로 서버 롤백 없이도 호환된다.

단계 3에서 구형 RPC를 제거한 뒤 문제가 발생하면 `GetOrderSnapshots`, `GetCartSnapshots` service 선언과 handler를 복구한 product-service를 먼저 배포하고 order-service를 구형 호출로 전환한다. 제거했던 field 번호를 다른 용도로 사용하지 않아야 이 롤백이 가능하다.
