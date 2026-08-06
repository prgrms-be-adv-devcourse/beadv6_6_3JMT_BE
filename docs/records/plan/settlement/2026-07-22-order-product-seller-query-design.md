# 구매한 상품 판매자 다건 조회 API 설계

- 작성일: 2026-07-22
- 관련 이슈: `#487 (이슈)`
- 대상 브랜치: `feat/#487-order-product-sellers (브랜치)`
- 대상 모듈: `user-service`
- 참고 화면: `beadv6_6_3JMT_FE`의 `/mypage?tab=purchased`

## 1. 배경

구매한 상품 페이지는 백엔드가 Order, Product, User 서비스 데이터를 gRPC로 모아 하나의 JSON으로 반환하던 방식에서 벗어난다. 프론트가 각 서비스의 공개 API를 호출한 뒤 식별자를 기준으로 화면 데이터를 조합한다.

User 서비스는 Product 응답에서 얻은 `sellerId` 목록을 판매자 이름으로 변환하는 역할만 맡는다. 기존 `POST /api/v2/sellers/products`는 상품 목록 화면을 위한 경로이므로 구매한 상품 화면이 해당 계약에 결합되지 않도록 전용 API와 전용 DTO를 추가한다. 실제 조회 로직은 이미 존재하는 `SellerQueryUseCase.findSellers()`를 재사용한다.

## 2. 목표

- `POST /api/v2/users/order-products`를 제공한다.
- 요청과 응답에 구매한 상품 화면 전용 DTO를 사용한다.
- 응답은 `sellerId`, `sellerName`만 노출한다.
- 기존 Seller 조회 UseCase와 Repository 흐름을 그대로 재사용한다.
- 기존 `POST /api/v2/sellers/products`의 계약과 동작을 보존한다.
- Gateway의 기본 인증 정책을 적용한다.

## 3. 비목표

- Order, Product, Frontend 저장소를 이번 브랜치에서 수정하지 않는다.
- 백엔드가 Order, Product, User 데이터를 다시 합성하는 API를 만들지 않는다.
- 새 UseCase, ApplicationService, Repository 메서드를 만들지 않는다.
- 기존 `/sellers/products` DTO를 새 API에서 공유하지 않는다.
- 캐시, BFF, GraphQL 같은 별도 조합 계층을 도입하지 않는다.
- 판매자 탈퇴 정책이나 Product 비활성화 절차를 변경하지 않는다.

## 4. 검토한 접근

### 4.1 채택: 기존 SellerController에 전용 경로와 DTO 추가

`SellerController`에 `POST /api/v2/users/order-products`를 추가한다. 컨트롤러는 전용 Request DTO를 검증하고 `SellerQueryUseCase.findSellers()`에 위임한 뒤 전용 Response DTO로 결과를 조립한다.

URL은 구매한 상품 화면의 호출 목적을 드러내고, 구현 클래스는 판매자 조회 책임과 기존 UseCase 의존성을 한곳에 유지한다. 새 비즈니스 동작이 없으므로 Application 계층은 변경하지 않는다.

### 4.2 기각: UserController에 경로 추가

URL과 컨트롤러 이름은 일치하지만 `UserController`가 Seller 패키지의 UseCase와 결과 DTO에 의존한다. 판매자 조회 진입점이 두 컨트롤러로 흩어지므로 채택하지 않는다.

### 4.3 기각: OrderProductSellerController 신규 생성

호출 목적은 가장 선명하지만 단일 메서드만 가진 컨트롤러가 추가되고 기존 Seller 조회 진입점과 Swagger 그룹이 분산된다. 별도 유스케이스가 없는 현재 범위에서는 분리 비용이 더 크다.

### 4.4 기각: 기존 /sellers/products 직접 재사용

변경량은 가장 적지만 Product 목록용 URL과 DTO에 구매 페이지가 결합된다. 호출 목적별 공개 계약을 독립적으로 유지한다는 요구와 맞지 않는다.

## 5. API 계약

### 5.1 요청

```http
POST /api/v2/users/order-products
Authorization: Bearer <access-token>
Content-Type: application/json
```

```json
{
  "sellerIds": [
    "3f1b1b0e-1111-2222-3333-444444444444",
    "9a2c2c1f-5555-6666-7777-888888888888"
  ]
}
```

`OrderProductSellerIdsRequest`는 다음 계약을 가진다.

- `sellerIds`: 필수 `UUID` 목록
- 빈 배열 금지
- 요청 한 번에 최대 30개
- 중복 ID는 첫 등장 순서를 기준으로 제거
- 프론트는 30개를 넘으면 여러 요청으로 나눈다.

### 5.2 성공 응답

```json
{
  "success": true,
  "data": {
    "sellers": [
      {
        "sellerId": "3f1b1b0e-1111-2222-3333-444444444444",
        "sellerName": "판매자 이름"
      },
      {
        "sellerId": "9a2c2c1f-5555-6666-7777-888888888888",
        "sellerName": null
      }
    ]
  },
  "message": "success"
}
```

`OrderProductSellerNamesResponse`는 요청 ID의 첫 등장 순서를 유지한다. 조회된 결과는 `sellerId`로 매핑하고, 조회되지 않은 ID는 해당 항목의 `sellerName`만 `null`로 반환한다. 정상 운영에서는 판매자가 탈퇴하기 전에 Product를 모두 내려야 하므로 누락이 예상되지 않지만, 데이터 정합성 예외가 전체 카드 조회 실패로 번지지 않도록 이 정책을 유지한다.

### 5.3 오류 응답

- body 누락, 빈 `sellerIds`, UUID 형식 오류, 30개 초과: `400 Bad Request`, `V001`
- 인증 토큰 누락 또는 유효하지 않음: Gateway 인증 정책에 따른 `401`
- 조회되지 않은 판매자 ID: 오류 응답으로 전환하지 않고 `sellerName: null`

## 6. 백엔드 구조

### 6.1 Controller

기존 `SellerController`에 `getOrderProductSellers()`를 추가한다.

1. `@Valid`로 `OrderProductSellerIdsRequest`를 검증한다.
2. DTO의 `sellerIdStrings()`로 UUID 목록을 문자열 목록으로 변환한다.
3. `sellerQueryUseCase.findSellers(...)`를 한 번 호출한다.
4. `OrderProductSellerNamesResponse.of(request.sellerIds(), results)`로 응답한다.

Swagger에는 Bearer 인증, `200`, `400`, 전용 Response DTO 스키마를 명시한다. User 서비스 내부 Security 설정은 Gateway 뒤에서 전체 요청을 허용하므로 인증 경로 판단은 Gateway가 담당한다. `/users/order-products`를 permit-all 목록에 추가하지 않아 기본 인증을 적용한다.

### 6.2 Request DTO

`OrderProductSellerIdsRequest`를 Seller presentation request 패키지에 추가한다.

- `record`로 선언한다.
- `@NotEmpty`, `@Size(max = 30)`을 적용한다.
- Swagger `@Schema`에 목적, 최대 개수, 유효한 UUID 예시를 적는다.
- UseCase 호출을 위한 `sellerIdStrings()` 변환 메서드를 제공한다.

기존 `SellerIdsRequest`를 상속하거나 포함하지 않는다. 검증 값이 같더라도 공개 API별 DTO를 분리해 이후 계약 변경이 서로 영향을 주지 않게 한다.

### 6.3 Response DTO

`OrderProductSellerNamesResponse`를 Seller presentation response 패키지에 추가한다.

- 최상위 필드: `List<Item> sellers`
- Item 필드: `UUID sellerId`, `String sellerName`
- `of(List<UUID>, List<SellerInfoResult>)` 정적 팩토리 제공
- 요청 순서 유지
- 중복 제거
- 조회 결과가 없는 ID의 `sellerName`은 `null`

Response DTO는 `SellerInfoResult`의 `profileImageUrl`, `status`를 공개하지 않는다.

### 6.4 Application과 Domain

`SellerQueryUseCase.findSellers(List<String>)`와 `SellerQueryApplicationService`를 변경하지 않는다. User Repository 조회와 `SellerInfoResult` 생성도 기존 동작을 그대로 사용한다. 이번 기능은 새로운 도메인 규칙이 아니라 공개 HTTP 계약을 추가하는 표현 계층 변경이다.

## 7. 프론트 데이터 흐름

이번 브랜치에서 프론트 코드는 수정하지 않지만 소비 흐름은 다음 계약을 전제로 한다.

1. Order API가 구매한 주문 상품과 `productId`를 반환한다.
2. Product API가 상품 표시 정보와 `sellerId`를 반환한다.
3. 프론트가 `sellerId`를 중복 제거하고 최대 30개씩 나눈다.
4. User의 `/users/order-products`를 호출한다.
5. 프론트가 Product의 `sellerId`와 User 응답의 `sellerId`를 기준으로 판매자 이름을 결합한다.

빈 판매자 ID 목록에서는 User API를 호출하지 않는다. User 호출 자체가 실패했을 때의 UI fallback은 프론트 구현 이슈에서 결정하며 이번 User API 계약에는 포함하지 않는다.

## 8. 테스트 전략

### 8.1 Controller 테스트

- 유효한 두 ID 요청이 `SellerQueryUseCase.findSellers()`에 문자열 목록으로 한 번 위임되는지 검증한다.
- 응답이 `data.sellers[].sellerId`, `sellerName`만 포함하는지 검증한다.
- 빈 배열이 `400 V001`이고 UseCase를 호출하지 않는지 검증한다.
- 31개 요청이 `400 V001`인지 검증한다.
- 기존 `/sellers/products` 테스트가 계속 통과하는지 확인한다.

### 8.2 Response DTO 테스트

- 요청의 첫 등장 순서를 유지하는지 검증한다.
- 중복 `sellerId`가 한 번만 반환되는지 검증한다.
- 조회 결과에 없는 ID가 `sellerName: null`인지 검증한다.
- record component가 `sellerId`, `sellerName`만 노출하는지 검증한다.

### 8.3 모듈 검증

- Seller Controller와 Response DTO 대상 테스트를 먼저 실행한다.
- User 서비스 전체 테스트를 실행한다.
- Checkstyle을 실행한다.
- API 명세와 Swagger 경로·필드명이 코드와 일치하는지 확인한다.

## 9. 문서와 배포

루트 `docs/api-spec/user.md`에 새 API의 인증, 요청, 성공 응답, 검증 오류와 누락 판매자 정책을 추가한다. 루트 문서 변경은 담당 범위 밖이지만 `#487 (이슈)`와 승인된 설계 범위에 명시되어 있으므로 구현 계획에 포함한다.

새 API는 기존 계약을 변경하지 않는 추가 변경이다. User 서비스가 먼저 배포된 뒤 프론트가 새 경로를 호출한다. Gateway에는 별도 whitelist를 추가하지 않으며 현재 user-service 버전 라우팅으로 전달되는지 통합 환경에서 확인한다.

## 10. 완료 조건

- `POST /api/v2/users/order-products`가 인증된 요청을 처리한다.
- API 전용 Request/Response DTO가 존재한다.
- 응답은 `sellerId`, `sellerName`만 포함한다.
- `SellerQueryUseCase.findSellers()` 외에 새 조회 UseCase나 Repository 메서드가 없다.
- 빈 배열과 30개 초과 요청이 `400 V001`이다.
- 중복 제거, 요청 순서, 누락 판매자 `null` 정책이 테스트로 고정된다.
- 기존 `/sellers/products`의 계약과 테스트가 유지된다.
- Swagger와 `docs/api-spec/user.md`가 새 계약을 반영한다.
