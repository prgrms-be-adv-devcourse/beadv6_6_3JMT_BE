# Order Service 도메인 용어 사전

---

## 주문 (order)

| 이름 | 영문 | DB 타입 | NOT NULL | 기본값 | 설명 |
|------|------|---------|:--------:|--------|------|
| 주문 ID * | order_id | UUID | ✓ | gen_random_uuid() | PK |
| 구매자 ID * | buyer_id | UUID | ✓ | | FK → user.user_id |
| 주문 번호 * | order_number | VARCHAR(30) | ✓ | | 사용자 노출 주문 번호. UNIQUE |
| 총 주문 금액 * | total_order_amount | INT | ✓ | 0 | |
| 총 상품 수 * | total_product_count | INT | ✓ | 0 | |
| 주문 상태 * | order_status | order_status_type | ✓ | PENDING | PENDING / PAID / FAILED / CANCELED / REFUNDED |
| 주문 일시 * | created_at | TIMESTAMPTZ | ✓ | | 불변 |
| 결제 완료 일시 | paid_at | TIMESTAMPTZ | | NULL | |
| 취소 일시 | canceled_at | TIMESTAMPTZ | | NULL | |
| 환불 일시 | refunded_at | TIMESTAMPTZ | | NULL | 부분환불 확장 예정 |
| 수정 일시 * | updated_at | TIMESTAMPTZ | ✓ | | |

---

## 주문 항목 (order_product)

| 이름 | 영문 | DB 타입 | NOT NULL | 기본값 | 설명 |
|------|------|---------|:--------:|--------|------|
| 주문 항목 ID * | order_product_id | UUID | ✓ | gen_random_uuid() | PK |
| 주문 ID * | order_id | UUID | ✓ | | FK → order.order_id |
| 상품 ID * | product_id | UUID | ✓ | | FK → product.product_id |
| 판매자 ID * | seller_id | UUID | ✓ | | FK → seller.seller_id. 정산 기준 |
| 상품명 스냅샷 * | product_title_snapshot | VARCHAR(200) | ✓ | | 구매 당시 상품명. 상품명 변경과 무관하게 보존 |
| 상품 유형 스냅샷 * | product_type_snapshot | VARCHAR(30) | ✓ | | 구매 당시 상품 유형 |
| 상품 가격 스냅샷 * | product_amount_snapshot | INT | ✓ | | 구매 당시 단가. 가격 변경과 무관하게 보존 |
| 주문 항목 상태 * | order_product_status | order_product_status_type | ✓ | | PENDING / PAID / FAILED / CANCELED / REFUNDED |
| 다운로드 여부 * | is_download | BOOLEAN | ✓ | FALSE | |
| 생성 일시 * | created_at | TIMESTAMPTZ | ✓ | | 불변 |
| 취소 일시 | canceled_at | TIMESTAMPTZ | | NULL | 부분 취소 확장용 |
| 환불 일시 | refunded_at | TIMESTAMPTZ | | NULL | 부분 환불 확장용 |
| 수정 일시 * | updated_at | TIMESTAMPTZ | ✓ | | |

---

## 장바구니 (cart)

| 이름 | 영문 | DB 타입 | NOT NULL | 기본값 | 설명 |
|------|------|---------|:--------:|--------|------|
| 식별자 * | cart_id | UUID | ✓ | gen_random_uuid() | PK |
| 구매자 ID * | buyer_id | UUID | ✓ | | FK → user.user_id |
| 총 금액 * | total_amount | INT | ✓ | 0 | 장바구니에 담은 상품의 총금액 |
| 생성 일시 * | created_at | TIMESTAMPTZ | ✓ | | |
| 수정 일시 * | updated_at | TIMESTAMPTZ | ✓ | | |

---

## 장바구니 항목 (cart_product)

| 이름 | 영문 | DB 타입 | NOT NULL | 기본값 | 설명 |
|------|------|---------|:--------:|--------|------|
| 식별자 * | cart_product_id | UUID | ✓ | gen_random_uuid() | PK |
| 장바구니 ID * | cart_id | UUID | ✓ | | FK → cart.cart_id |
| 상품 ID * | product_id | UUID | ✓ | | FK → product.product_id |
| 담은 시각 * | added_at | TIMESTAMPTZ | ✓ | | |
