# Order Service 도메인 용어 사전

현재 `order-service` 구현의 엔티티와 DTO 필드명을 기준으로 정리한다. DB 컬럼명은 JPA 매핑을 따른다.

---

## 주문 (`order`)

| 이름 | Java 필드 | DB 컬럼 | 타입 | 설명 |
|------|-----------|---------|------|------|
| 주문 ID | `id` | `id` | UUID | PK |
| 구매자 ID | `buyerId` | `buyer_id` | UUID | Gateway가 전달한 구매자 식별자 |
| 주문 번호 | `orderNumber` | `order_number` | VARCHAR(30) | 사용자 노출 주문 번호. UNIQUE |
| 총 주문 금액 | `totalOrderAmount` | `total_order_amount` | INT | 주문 상품 금액 합계 |
| 주문 상태 | `orderStatus` | `order_status` | VARCHAR(20) | `CREATED` / `COMPLETED` / `FAILED` / `REFUND_REQUESTED` / `PARTIAL_REFUNDED` / `ALL_REFUNDED` |
| 결제 완료 시각 | `completedAt` | `completed_at` | TIMESTAMP | 주문 완료(`markCompleted`) 시 설정. V2에서 `paid_at`을 대체 |
| 환불 완료 시각 | `refundedAt` | `refunded_at` | TIMESTAMP | 전체 환불(`ALL_REFUNDED`) 완료 시 설정 |
| 생성 시각 | `createdAt` | `created_at` | TIMESTAMP | `BaseEntity` 감사 필드 |
| 수정 시각 | `updatedAt` | `updated_at` | TIMESTAMP | `BaseEntity` 감사 필드 |

> - `getPaidAt()`은 하위 호환용 getter로 `completedAt` 값을 그대로 반환한다(별도 컬럼 아님).
> - 총 상품 수(`getTotalProductCount()`)는 저장 컬럼이 아니라 `orderProducts.size()`로 계산하는 파생값이다. `total_product_count` 컬럼은 `V2__migrate_order_schema_to_single_order.sql`에서 삭제됐다.
> - 취소 시각(`getCanceledAt()`)은 `V2`에서 `canceled_at` 컬럼이 삭제되어 이제 `@Transient` 필드(`legacyCanceledAt`)로만 존재한다. DB에 영속되지 않으므로 재조회하면 항상 `null`이다.

---

## 주문 상품 (`order_product`)

| 이름 | Java 필드 | DB 컬럼 | 타입 | 설명 |
|------|-----------|---------|------|------|
| 주문 상품 ID | `id` | `id` | UUID | PK |
| 주문 | `order` | `order_id` | UUID | 주문 FK |
| 구매자 ID | `buyerId` | `buyer_id` | UUID | `V7`에서 추가. 주문 배정 시 `order.buyerId`로 채워짐(그 전엔 NULL 가능). `PENDING` 상태 중복 방지용 부분 UNIQUE 인덱스 `(buyer_id, product_id) WHERE order_product_status = 'PENDING'`의 대상 |
| 상품 ID | `productId` | `product_id` | UUID | Product Service 상품 식별자 |
| 판매자 ID | `sellerId` | `seller_id` | UUID | 정산 기준 판매자 식별자 |
| 상품명 스냅샷 | `productTitle` | `product_title_snapshot` | VARCHAR(200) | 주문 시점 상품명 |
| 상품 금액 스냅샷 | `productAmount` | `product_amount_snapshot` | INT | 주문 시점 상품 금액 |
| 주문 상품 상태 | `orderStatus` | `order_product_status` | VARCHAR(20) | `PENDING` / `PAID` / `FAILED` / `REFUND_REQUESTED` / `REFUNDED`(`V6`에서 `CANCELED` 제거, `REFUND_REQUESTED` 추가) |
| 다운로드 확정 여부 | `downloaded` | `downloaded` | BOOLEAN | 콘텐츠 열람/다운로드 확정 여부 |
| 생성 시각 | `createdAt` | `created_at` | TIMESTAMP | 주문 상품 생성 시각 |
| 수정 시각 | `updatedAt` | `updated_at` | TIMESTAMP | 상태 또는 다운로드 여부 변경 시 갱신 |
| 환불 시각 | `refundedAt` | `refunded_at` | TIMESTAMP | 환불 완료 처리 시 설정 |

> - 상품 유형 스냅샷(`productType`)·상품 모델 스냅샷(`productModel`)·취소 시각(`canceledAt`)은 각각 `product_type_snapshot`·`product_model_snapshot`·`canceled_at` 컬럼이 `V2`에서 삭제되어, 이제 `@Transient` 필드(`legacyProductType`·`legacyProductModel`·`legacyCanceledAt`)로만 존재한다. DB에 영속되지 않으므로 재조회하면 항상 `null`이다.
> - `Order`와 달리 `BaseEntity`를 상속하지 않고 `createdAt`·`updatedAt`을 엔티티 내부에서 직접 관리한다.

---

## 장바구니 (`cart`)

| 이름 | Java 필드 | DB 컬럼 | 타입 | 설명 |
|------|-----------|---------|------|------|
| 장바구니 ID | `id` | `id` | UUID | PK |
| 구매자 ID | `buyerId` | `buyer_id` | UUID | 장바구니 소유자. `V5`에서 UNIQUE 제약(`uk_cart_buyer_id`) 추가 |
| 총 금액 | `totalAmount` | `total_amount` | INT | 저장 컬럼(V1부터 존재). 생성 시 0, 상품 추가/삭제 시 재계산 |
| 생성 시각 | `createdAt` | `created_at` | TIMESTAMP | `BaseEntity` 감사 필드 |
| 수정 시각 | `updatedAt` | `updated_at` | TIMESTAMP | `BaseEntity` 감사 필드 |

> `total_amount`는 저장 컬럼이지만, 현재 `CartProduct`에 가격 스냅샷이 없어 `Cart.recalculateTotalAmount()`가 재계산할 때마다 항상 0으로 리셋된다. 장바구니 조회 응답의 `totalAmount`는 이 컬럼 값이 아니라 Product Service 스냅샷으로 매 요청 다시 계산한 값이다. 응답의 `totalItemCount`도 저장 컬럼이 아니라 응답 생성 시 계산하는 필드다.

---

## 장바구니 상품 (`cart_product`)

| 이름 | Java 필드 | DB 컬럼 | 타입 | 설명 |
|------|-----------|---------|------|------|
| 장바구니 상품 ID | `id` | `id` | UUID | PK |
| 장바구니 | `cart` | `cart_id` | UUID | 장바구니 FK |
| 상품 ID | `productId` | `product_id` | UUID | Product Service 상품 식별자 |
| 담은 시각 | `addedAt` | `added_at` | TIMESTAMP | 장바구니 상품 추가 시각 |

> `V5`에서 `(cart_id, product_id)` UNIQUE 제약(`uk_cart_product_cart_product`)이 추가되어, 같은 장바구니에 같은 상품을 중복으로 담을 수 없다.

---

## 주요 응답 파생 속성

| 이름 | DTO 필드 | 반환 타입 | 설명 |
|------|----------|-----------|------|
| 콘텐츠 열람 가능 여부 | `isContentAccessible` | Boolean | 주문 상품 상태가 `PAID`이면 true |
| 환불 가능 여부 | `isRefundable` | Boolean | 주문 상품 상태가 `PAID`이고 `downloaded`가 false이며 금액이 0보다 크면 true |
| 다운로드된 상품 포함 여부 | `hasDownloadedProduct` | Boolean | 주문 내 다운로드 확정된 상품이 하나 이상 있으면 true |
