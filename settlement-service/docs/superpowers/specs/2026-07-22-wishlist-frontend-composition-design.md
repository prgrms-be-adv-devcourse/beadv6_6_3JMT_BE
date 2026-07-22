# 위시리스트 프론트 조합 전환 설계

- 작성일: 2026-07-22
- 관련 이슈: `#485 (이슈)`
- 선행 작업: `#478 (PR)`
- 대상 저장소: `beadv6_6_3JMT_BE`, `beadv6_6_3JMT_FE`

## 1. 배경

현재 User 서비스의 `GET /api/v2/wishlists`는 위시리스트 엔티티를 조회한 뒤 Product gRPC와 User 판매자 조회를 수행해 상품 카드 전체 데이터를 합성한다. 그러나 Wishlist가 사용하는 Product 로컬 gRPC 계약은 Product 서비스에 구현된 적이 없어 `UNIMPLEMENTED` fallback이 발생할 수 있으며, User 서비스가 Product 소유 데이터까지 책임지는 경계 문제도 있다.

프론트는 이미 `GET /api/v2/products/by-ids`를 이용해 상품 정보를 별도로 조회하는 1차 전환이 반영되어 있다. `#478 (PR)`은 이 Product API를 `POST /api/v2/products/wishlists`로 변경한다. 이번 작업은 User의 합성 책임을 제거하고, 판매자 정보까지 목적별 API로 분리해 프론트에서 최종 화면 모델을 조합하는 전환을 완성한다.

## 2. 목표

- User Wishlist API는 User가 소유한 위시리스트 식별자와 등록 시각만 반환한다.
- Wishlist 조회 경로에서 User → Product gRPC 통신을 제거한다.
- Wishlist 화면용 판매자 다건 조회 경로를 추가하되 기존 Seller 조회 UseCase와 DTO를 재사용한다.
- 프론트는 Wishlist, Product, Seller 응답을 식별자로 안전하게 조합한다.
- Product 작업은 `#478 (PR)`의 계약을 소비하고 중복 구현하지 않는다.

## 3. 비목표

- Product의 `POST /api/v2/products/wishlists` 구현을 이번 브랜치에서 수정하지 않는다.
- 기존 `POST /api/v2/sellers/products`의 계약이나 동작을 변경하지 않는다.
- Product 또는 User 서비스에 화면 전용 통합 API를 새로 만들지 않는다.
- Redis 캐시, BFF, GraphQL 같은 별도 조합 계층을 도입하지 않는다.
- 마이페이지의 전반적인 컴포넌트 리팩터링은 다루지 않는다.

## 4. 검토한 접근

### 4.1 채택: 기존 SellerController에 Wishlist 경로 추가

`POST /api/v2/sellers/wishlists` 메서드를 기존 `SellerController`에 추가한다. 요청·응답은 `SellerIdsRequest`, `SellerNamesResponse`를 사용하고 애플리케이션 호출은 `SellerQueryUseCase.findSellers()`를 그대로 사용한다.

새 도메인 로직 없이 표현 계층에서 호출 목적만 구분할 수 있고, 최신 `origin/develop`에서 판매자 등록·단건·다건 조회가 `SellerController`로 통합된 구조와도 맞는다.

### 4.2 기각: SellerWishlistController 별도 생성

Wishlist 전용 클래스라는 장점은 있지만 `/sellers/products`와 동일한 검증, 매핑, 응답 조립 코드가 중복된다. 독립적인 애플리케이션 동작이 없는 현재 범위에서는 분리 이득보다 중복 비용이 크다.

### 4.3 기각: 기존 /sellers/products 재사용

구현 변경은 줄지만 Product 목록용 경로를 Wishlist 화면이 사용하게 되어 URL이 호출 목적을 설명하지 못한다. 팀이 Product 쪽에도 `/products/wishlists`를 도입한 방향과 맞춰 `/sellers/wishlists`를 둔다.

## 5. API 계약

### 5.1 GET /api/v2/wishlists

인증과 페이지 파라미터는 기존 계약을 유지한다.

```json
{
  "success": true,
  "data": [
    {
      "wishlistId": "uuid",
      "productId": "uuid",
      "addedAt": "2026-07-22T12:00:00"
    }
  ],
  "message": "success",
  "meta": {
    "page": 0,
    "size": 20,
    "total": 1,
    "hasNext": false
  }
}
```

응답에서 `title`, `thumbnailUrl`, `price`, `sellerNickname`, `averageRating`, `salesCount`, `model`을 제거한다. 페이지 번호 체계와 `meta` 계산 방식은 바꾸지 않는다.

### 5.2 POST /api/v2/products/wishlists

`#478 (PR)`이 제공하는 계약을 그대로 소비한다.

```json
{
  "productIds": ["uuid-1", "uuid-2"]
}
```

응답은 `productId`, `sellerId`, `title`, `amount`, `thumbnailUrl`, `productType`, `model`, `salesCount`, `averageRating`, `status`를 포함한다. 요청한 상품이 없거나 현재 Wishlist 대표 버전이 없으면 해당 상품은 응답에서 제외될 수 있다.

### 5.3 POST /api/v2/sellers/wishlists

```json
{
  "sellerIds": ["uuid-1", "uuid-2"]
}
```

```json
{
  "success": true,
  "data": {
    "sellers": [
      {
        "sellerId": "uuid-1",
        "sellerName": "판매자 이름"
      },
      {
        "sellerId": "uuid-2",
        "sellerName": null
      }
    ]
  },
  "message": "success"
}
```

기존 Seller 다건 조회와 동일하게 빈 목록과 30개 초과 요청은 `400`, 중복 ID는 서버에서 제거, 존재하지 않는 판매자는 `sellerName: null`로 반환한다. 인증 정책은 로그인 전용 Wishlist 흐름을 따르며 Gateway의 기본 인증 경로를 사용한다.

## 6. 백엔드 설계

### 6.1 Wishlist 소유 데이터만 조회

`WishlistApplicationService.getWishlists()`는 Repository가 반환한 `Wishlist`를 `WishlistItemResult`로 변환하는 일만 한다. `ProductClient`와 `UserRepository` 의존성, 상품 fallback 상수와 합성 메서드를 제거한다.

`WishlistItemResult`와 `WishlistItemResponse`는 다음 세 필드만 가진다.

- `UUID wishlistId`
- `UUID productId`
- `LocalDateTime addedAt`

### 6.2 죽은 Product gRPC 경로 제거

Wishlist에서만 사용되는 아래 요소를 제거한다.

- Wishlist 애플리케이션의 `ProductClient`
- `ProductSummaryDto`
- `ProductGrpcClient`
- Wishlist의 `GrpcClientConfig`
- User 로컬 `product_service.proto`
- `application-local.yml`과 Config Server의 User 설정에 남은 `grpc.client.product-service`

User 서비스는 Seller gRPC 서버를 계속 제공하므로 protobuf 플러그인과 서버 의존성은 유지한다. gRPC client starter와 직접 stub/protobuf 의존성은 전체 User 모듈 사용처를 다시 검색한 뒤, 다른 client가 없을 때만 제거한다.

### 6.3 Seller 경로 재사용

최신 `origin/develop`의 `SellerController`에 Wishlist용 `@PostMapping`을 추가한다. 메서드는 `SellerIdsRequest`를 검증하고 `SellerQueryUseCase.findSellers(request.sellerIdStrings())` 결과를 `SellerNamesResponse.of(request.sellerIds(), results)`로 변환한다.

UseCase, ApplicationService, Repository에는 새 메서드를 만들지 않는다. Swagger 설명만 Wishlist 호출 목적에 맞게 추가한다.

## 7. 프론트엔드 설계

### 7.1 API helper

- `WishlistItem` 타입을 `wishlistId`, `productId`, `addedAt`으로 축소한다.
- Product helper를 `POST /products/wishlists`와 `{ productIds }` body 계약으로 바꾼다.
- Seller helper에 `POST /sellers/wishlists` 호출을 추가한다. Seller 응답 타입과 30개 청크 정책은 기존 `/sellers/products` helper와 공유한다.

빈 ID 목록에서는 다음 API를 호출하지 않는다. ID는 각 단계에서 중복 제거하고 30개 단위로 나눈다.

### 7.2 화면 조합

마이페이지 Wishlist 로딩은 다음 waterfall을 사용한다.

1. Wishlist 목록을 조회한다.
2. `productId` 목록으로 Product를 조회한다.
3. Product 응답의 `sellerId` 목록으로 Seller를 조회한다.
4. Product는 `productId`, Seller는 `sellerId` Map으로 변환한다.
5. 원래 Wishlist 순서를 기준으로 카드 모델을 만든다.

Product 응답에 없는 항목은 렌더링하지 않는다. Seller가 없거나 이름이 `null`이면 `탈퇴한 판매자`를 표시한다. Product의 `amount`는 카드 가격, `averageRating`은 평점, `salesCount`는 판매량으로 매핑한다.

현재 마이페이지는 실제로 `tab` query key와 `wishlist` 값을 사용한다. 데이터 조합 변경은 이 라우팅 계약을 바꾸지 않는다.

### 7.3 전역 Wishlist 동기화

`AuthSync`는 상품 표시 정보를 User Wishlist 응답에서 읽지 않는다. 전역 `useWishStore`가 실제로 사용하는 기능은 찜 여부와 `wishlistId` 조회이므로 서버 동기화 시 최소 식별자만 저장할 수 있게 `WishItem`의 표시 필드를 선택값으로 바꾸거나, 동기화 전용 최소 타입으로 정리한다.

카드에서 새로 찜을 추가할 때는 현재 화면이 이미 가진 상품 표시 정보를 계속 저장할 수 있다. 서버 재동기화 뒤에도 `id`와 `wishlistId`로 찜 활성 상태와 삭제 요청이 동작해야 한다.

## 8. 실패 처리

- Wishlist 조회 실패: 현재 Wishlist 로딩을 실패로 종료하고 빈 목록으로 덮어쓰지 않는다.
- Product 조회 실패: 완전한 카드 구성이 불가능하므로 Wishlist 화면 전체를 실패 상태로 처리한다.
- 일부 Product 누락: 누락된 상품만 제외하고 나머지를 표시한다.
- Seller 조회 실패: 상품 카드는 유지하고 판매자명을 `탈퇴한 판매자`로 표시한다.
- 일부 Seller 누락 또는 `null`: 해당 카드만 `탈퇴한 판매자`로 표시한다.
- 빈 Wishlist: Product와 Seller API를 호출하지 않고 빈 상태를 표시한다.

## 9. 테스트 전략

### 9.1 User 서비스

- Wishlist 서비스가 Repository 결과를 세 필드 결과로 변환하는 단위 테스트
- 빈 Wishlist 결과에서 외부 의존성 없이 빈 목록을 반환하는 테스트
- Controller 페이지 응답이 축소 계약과 `meta`를 유지하는 테스트
- `/sellers/wishlists`가 요청 검증 후 기존 `SellerQueryUseCase.findSellers()`에 위임하는 Controller 테스트
- 중복, 누락 판매자와 30개 제한은 공유 DTO/응답 테스트를 재사용하고 새 경로의 연결만 추가 검증
- User 모듈 전체 테스트와 API 버전 매핑 테스트

### 9.2 프론트엔드

- Wishlist/Product/Seller 응답의 정상 조합과 원래 Wishlist 순서 유지
- 빈 Wishlist에서 후속 호출이 없는지 검증
- 중복 ID 제거와 30개 초과 청크 분할 검증
- 누락 Product 제외, 누락 Seller fallback 검증
- Product 실패와 Seller 실패가 서로 다른 화면 정책을 따르는지 검증
- `AuthSync`가 축소된 Wishlist 응답만으로 찜 여부와 삭제용 `wishlistId`를 동기화하는지 검증

## 10. 배포와 의존성

구현과 배포 순서는 다음과 같다.

1. `#478 (PR)`을 병합해 Product POST 계약을 제공한다.
2. User 서비스에 축소 응답과 Seller Wishlist 경로를 배포한다.
3. 프론트를 새 계약으로 배포한다.

User 응답 필드 제거는 하위 호환되지 않는다. 따라서 프론트 변경을 함께 준비하고 배포 간격을 최소화한다. 현재 프론트 마이페이지는 이미 Product를 별도로 조회하지만 `AuthSync`는 구 응답 필드에 의존하므로 User를 먼저 배포할 때 이 부분이 일시적으로 비어 있을 수 있다. 가능하면 User와 프론트를 같은 배포 창에서 전환한다.

## 11. 완료 조건

- User Wishlist 조회 시 Product gRPC와 판매자 Repository를 호출하지 않는다.
- Wishlist 응답에는 `wishlistId`, `productId`, `addedAt`만 존재한다.
- `/sellers/wishlists`가 기존 Seller 다건 조회 UseCase와 DTO로 응답한다.
- 프론트 Wishlist 카드가 세 API 응답을 조합해 상품과 판매자명을 표시한다.
- 전역 찜 상태 동기화와 찜 삭제가 축소 응답에서도 동작한다.
- 관련 백엔드·프론트 테스트와 문서가 새 계약을 반영한다.
