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
| 총 상품 수 | `totalProductCount` | `total_product_count` | INT | 주문에 포함된 상품 수 |
| 주문 상태 | `orderStatus` | `order_status` | VARCHAR(20) | `PENDING` / `PAID` / `FAILED` / `CANCELED` / `REFUNDED` |
| 결제 완료 시각 | `paidAt` | `paid_at` | TIMESTAMP | 결제 승인 이벤트 반영 시 설정 |
| 취소 시각 | `canceledAt` | `canceled_at` | TIMESTAMP | 취소 처리 시 설정 |
| 환불 시각 | `refundedAt` | `refunded_at` | TIMESTAMP | 환불 이벤트 반영 시 설정 |
| 생성 시각 | `createdAt` | `created_at` | TIMESTAMP | `BaseEntity` 감사 필드 |
| 수정 시각 | `updatedAt` | `updated_at` | TIMESTAMP | `BaseEntity` 감사 필드 |

---

## 주문 상품 (`order_product`)

| 이름 | Java 필드 | DB 컬럼 | 타입 | 설명 |
|------|-----------|---------|------|------|
| 주문 상품 ID | `id` | `id` | UUID | PK |
| 주문 | `order` | `order_id` | UUID | 주문 FK |
| 상품 ID | `productId` | `product_id` | UUID | Product Service 상품 식별자 |
| 판매자 ID | `sellerId` | `seller_id` | UUID | 정산 기준 판매자 식별자 |
| 상품명 스냅샷 | `productTitle` | `product_title_snapshot` | VARCHAR(200) | 주문 시점 상품명 |
| 상품 유형 스냅샷 | `productType` | `product_type_snapshot` | VARCHAR(30) | 주문 시점 상품 유형 |
| 상품 모델 스냅샷 | `productModel` | `product_model_snapshot` | VARCHAR(50) | 주문 시점 모델명/분류 |
| 상품 금액 스냅샷 | `productAmount` | `product_amount_snapshot` | INT | 주문 시점 상품 금액 |
| 주문 상품 상태 | `orderStatus` | `order_product_status` | VARCHAR(20) | `PENDING` / `PAID` / `FAILED` / `CANCELED` / `REFUNDED` |
| 다운로드 확정 여부 | `downloaded` | `downloaded` | BOOLEAN | 콘텐츠 열람/다운로드 확정 여부 |
| 생성 시각 | `createdAt` | `created_at` | TIMESTAMP | 주문 상품 생성 시각 |
| 취소 시각 | `canceledAt` | `canceled_at` | TIMESTAMP | 취소 처리 시 설정 |
| 환불 시각 | `refundedAt` | `refunded_at` | TIMESTAMP | 환불 처리 시 설정 |
| 수정 시각 | `updatedAt` | `updated_at` | TIMESTAMP | 상태 또는 다운로드 여부 변경 시 갱신 |

---

## 장바구니 (`cart`)

| 이름 | Java 필드 | DB 컬럼 | 타입 | 설명 |
|------|-----------|---------|------|------|
| 장바구니 ID | `id` | `id` | UUID | PK |
| 구매자 ID | `buyerId` | `buyer_id` | UUID | 장바구니 소유자 |
| 생성 시각 | `createdAt` | `created_at` | TIMESTAMP | `BaseEntity` 감사 필드 |
| 수정 시각 | `updatedAt` | `updated_at` | TIMESTAMP | `BaseEntity` 감사 필드 |

> 장바구니 응답의 `totalAmount`, `totalItemCount`는 저장 컬럼이 아니라 Product Service 스냅샷과 장바구니 항목으로 계산하는 응답 필드이다.

---

## 장바구니 상품 (`cart_product`)

| 이름 | Java 필드 | DB 컬럼 | 타입 | 설명 |
|------|-----------|---------|------|------|
| 장바구니 상품 ID | `id` | `id` | UUID | PK |
| 장바구니 | `cart` | `cart_id` | UUID | 장바구니 FK |
| 상품 ID | `productId` | `product_id` | UUID | Product Service 상품 식별자 |
| 담은 시각 | `addedAt` | `added_at` | TIMESTAMP | 장바구니 상품 추가 시각 |

---

## 결제 내역 (`order_payment`)

| 이름 | Java 필드 | DB 컬럼 | 타입 | 설명 |
|------|-----------|---------|------|------|
| 결제 내역 ID | `id` | `id` | UUID | PK |
| 주문 ID | `orderId` | `order_id` | UUID | 주문 식별자 |
| 결제 ID | `paymentId` | `payment_id` | UUID | Payment Service 결제 식별자 |
| PG 거래 ID | `pgTxId` | `pg_tx_id` | String | PG 거래 식별자 |
| 승인 금액 | `amount` | `amount` | INT | 승인된 결제 금액 |
| 승인 시각 | `approvedAt` | `approved_at` | TIMESTAMP | 결제 승인 시각 |

---

## 주요 응답 파생 속성

| 이름 | DTO 필드 | 반환 타입 | 설명 |
|------|----------|-----------|------|
| 콘텐츠 열람 가능 여부 | `isContentAccessible` | Boolean | 주문 상품 상태가 `PAID`이면 true |
| 환불 가능 여부 | `isRefundable` | Boolean | 주문 상품 상태가 `PAID`, `downloaded=false`, 상품 금액이 0원보다 크면 true |
| 다운로드된 상품 포함 여부 | `hasDownloadedProduct` | Boolean | 주문 내 다운로드 확정된 상품이 하나 이상 있으면 true |
| 주문 목록 대표 결제 상태 | `paymentStatus` | `OrderStatus` | 주문 상태를 그대로 반환한 값 |

## 상품 단위 환불 상태

- 주문: 일부 상품만 환불 완료되면 `PARTIALLY_REFUNDED`, 모든 상품이 환불 완료되면 `REFUNDED`
- 주문 상품: `REFUND_REQUESTED`, `REFUNDED`, `REFUND_FAILED`, `REFUND_TIMEOUT`
- 환불 요청: `REQUESTED`, `COMPLETED`, `FAILED`, `TIMEOUT`
- `order_refund`는 요청 전체 상태와 공통 사유·총액·재조정 일정을, `order_refund_product`는 대상 주문 상품과 금액 스냅샷을 보관한다.
