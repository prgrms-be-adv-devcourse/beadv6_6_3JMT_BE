# Product API 규칙

## 공개 조회 API

아래 API는 인증이 필요 없다.

- `GET /api/v2/products`
- `GET /api/v2/products/{productId}`
- `GET /api/v2/products/{productId}/recommends`
- `GET /api/v2/products/{productId}/reviews`

API 명세에서 다르게 정의하지 않는 한, 공개 상품 목록/상세/관련 상품 조회는 `ON_SALE` 상태 상품만 노출한다.

## 인증 필요 API

Product 쓰기/리뷰/관리자 API는 JWT를 직접 파싱하지 않는다. Gateway가 검증 후 주입하는
`X-User-Id`, `X-User-Role` 헤더만 신뢰해서 읽는다. 헤더 주입 메커니즘 자체(전체 흐름,
Authorization 처리, 역할 값)는 루트 `CLAUDE.md` 시스템 전체 흐름, `apigateway/CLAUDE.md`를
따른다.

## ID 계약

`Product`·`Review` 등 API로 노출되는 도메인 엔티티의 PK는 `UUID`로 통일돼 있다. 새 도메인
엔티티도 이 기준을 따른다. `ProductProcessedEvent`(Kafka 멱등성 처리 이력)처럼 API로 노출되지
않는 내부 기술용 테이블은 예외적으로 auto-increment `Long` PK를 쓸 수 있다 — 이 예외는 외부
계약과 무관한 내부 원장에 한정한다.

## 응답 형식

프로젝트 공통 응답 형식을 따른다.

```json
{
  "success": true,
  "data": {},
  "message": "success"
}
```

목록/페이지 응답은 `docs/api-spec/product.md`를 따른다.

## gRPC 메서드 네이밍

product-service가 새로 gRPC 메서드를 추가할 때 지키는 공통 규칙(메서드 접두어, 계약 소유권,
쓰기 미노출)은 루트 `docs/architecture/grpc-contract-ownership.md`(요약: `grpc/README.md`)를 따른다.

product-service가 서버로서 제공하는 gRPC 계약은 **루트 `grpc/product/product_query.proto`의
단일 `ProductQueryService`** 로 관리한다(소유자=서버). 현재 3개 메서드: `GetOrderSnapshots`,
`GetCartSnapshots`, `GetProductContent` — 모두 `Get~` 규칙에 부합.
`GetProductsByIds`는 실제 호출자가 없어 제거했다(#431) — 대체 용도(찜 목록 상품 카드 조회)는
공개 REST `POST /products/wishlists`로 노출한다. `GetSellerStats`(셀러 통계, 옛 `CountBySeller`)도
실제 호출자가 없어 제거했다(#483) — 대체 용도는 공개 REST `GET /products/sellers/me/summary`로
노출한다.
`GetProductContent`는 `purpose`(`ProductContentPurpose`) enum으로 주문 스냅샷·장바구니
스냅샷·구매 콘텐츠 조회를 하나의 진입점으로 통합하는 전환 1단계가 적용돼 있다(#431, #433,
`docs/superpowers/specs/2026-07-20-unified-get-product-content-design.md`).
`GetOrderSnapshots`/`GetCartSnapshots`는 order-service 소비자 전환이 끝날 때까지
하위 호환용으로 유지한다.
wire `package`는 `prompthub.product`, `java_package`는 `com.prompthub.product.grpc`.

## DDL과 docs 일치 확인

`docs/erd/schema.md`는 실제 DDL의 열람용 미러이므로 최신 상태가 아닐 수 있다.

엔티티/컬럼과 관련된 구현을 시작하기 전에 아래를 확인한다.

- 실제 DB DDL(`init-script/postgres/schema.sql` 또는 DB 직접 조회)과 `schema.md`를 대조한다.
- 컬럼이 추가·변경·삭제됐는데 `schema.md`에 반영되지 않은 경우 `schema.md`를 먼저 수정한다.
- 도메인 모델(`@Entity`)이 실제 DDL과 다른 경우 엔티티도 함께 수정한다.
- docs와 실제 DB가 다르면 임의로 결정하지 않고 사용자에게 확인받는다.

## 프론트 호환성

Product API는 프론트 프로젝트 `C:\programmers_prj\beadv6_6_3JMT_FE`의 해당 메뉴 흐름과 호환되어야 한다.

공개 조회 응답은 최소한 아래 프론트 Product 화면 흐름과 맞아야 한다.

- `/browse`
- `/detail/[id]`
- home 상품 섹션

확인할 항목:

- 프론트가 호출하는 URL path
- 프론트가 넘기는 query param
- 프론트가 기대하는 response field
- 상품 ID 타입과 route param 처리 방식
- DDL 기준 실제 column/entity 구조

docs, DDL, 프론트 구현 중 하나라도 서로 맞지 않으면 임의로 결정하지 않는다.
특히 URL path, query param, ID 타입, response field가 다르면 사용자에게 확인받고 넘어간다.

docs와 프론트 mock data의 필드명이 다르면 DDL과 API docs를 먼저 확인한다.
그 뒤에도 어느 쪽으로 맞출지 불명확하면 사용자에게 확인받는다.
