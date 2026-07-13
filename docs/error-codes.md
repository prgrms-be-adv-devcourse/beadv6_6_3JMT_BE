# 에러 코드 명세

## 공통 (VALIDATION)

| enum | code | 의미 (message) | HTTP |
|------|------|----------------|------|
| `INVALID_INPUT_VALUE` | V001 | 입력값 검증 실패 | 400 |
| `INTERNAL_SERVER_ERROR` | SYS001 | 서버 내부 오류가 발생했습니다. | 500 |
| `PRODUCT_SERVICE_UNAVAILABLE` | SYS002 | 상품 서비스를 사용할 수 없습니다. | 503 |

---

## 인증 / 회원 (AUTH)

| enum | code | 의미 (message) | HTTP |
|------|------|----------------|------|
| `AUTH_NOT_FOUND` | A001 | 사용자가 없습니다. | 404 |
| `AUTH_INVALID_PASSWORD` | A002 | 비밀번호가 일치하지 않습니다. | 401 |
| `INVALID_AUTHENTICATION` | A003 | 토큰이 만료되었거나 유효하지 않습니다. | 401 |
| `FORBIDDEN` | A004 | 권한이 없습니다. | 403 |
| `AUTH_SELLER_ALREADY_APPLIED` | A005 | 이미 신청된 판매자입니다. | 409 |
| `AUTH_INVALID_REFRESH_TOKEN` | A006 | 리프레시 토큰이 유효하지 않습니다. | 401 |
| `AUTH_EMAIL_DUPLICATED` | A007 | 이미 사용 중인 이메일입니다. | 409 |
| `AUTH_SELLER_APPLICATION_NOT_FOUND` | A008 | 판매자 등록 신청 내역이 없습니다. | 404 |
| `UNSUPPORTED_OAUTH_PROVIDER` | A009 | 지원하지 않는 OAuth 공급자입니다. | 400 |
| `AUTH_WITHDRAW_ORDER_IN_PROGRESS` | A010 | 진행 중인 주문이 있어 탈퇴할 수 없습니다. | 400 |

---

## 상품 (PRODUCT)

| enum | code | 의미 (message) | HTTP |
|------|------|----------------|------|
| `PRODUCT_NOT_FOUND` | P001 | 상품이 존재하지 않습니다. | 404 |
| `PRODUCT_NOT_ON_SALE` | P002 | 판매 중인 상품이 아닙니다. | 400 |
| `PRODUCT_FORBIDDEN` | P003 | 본인의 상품만 수정/삭제할 수 있습니다. | 403 |
| `INVALID_PRODUCT_TYPE` | P004 | 올바르지 않은 상품 유형입니다. | 400 |
| `SELLER_NOT_ACTIVE` | P005 | 승인된 판매자만 상품을 등록할 수 있습니다. | 403 |
| `PRODUCT_INVALID_STATUS` | P006 | 현재 상태에서 처리할 수 없는 상품입니다. | 409 |
| `PRODUCT_TYPE_FIELD_MISMATCH` | P007 | 상품 유형에 맞지 않는 필드 구성입니다. | 400 |

---

## 리뷰 (REVIEW)

| enum | code | 의미 (message) | HTTP |
|------|------|----------------|------|
| `REVIEW_ALREADY_EXISTS` | R001 | 이미 리뷰를 작성한 상품입니다. | 409 |
| `REVIEW_NOT_PURCHASED` | R002 | 구매한 상품만 리뷰를 작성할 수 있습니다. | 403 |

---

## 찜 (WISHLIST)

| enum | code | 의미 (message) | HTTP |
|------|------|----------------|------|
| `WISHLIST_DUPLICATED` | W001 | 이미 찜한 상품입니다. | 409 |
| `WISHLIST_NOT_FOUND` | W002 | 찜 항목이 존재하지 않습니다. | 404 |
| `WISHLIST_FORBIDDEN` | W003 | 본인의 찜 항목이 아닙니다. | 403 |

---

## 장바구니 (CART)

| enum | code | 의미 (message) | HTTP |
|------|------|----------------|------|
| `CART_ITEM_DUPLICATED` | C001 | 이미 장바구니에 담긴 상품입니다. | 409 |
| `CART_ITEM_FORBIDDEN` | C003 | 본인의 장바구니 항목이 아닙니다. | 403 |

---

## 주문/장바구니 - order-service 현재 구현

| enum | code | 의미 (message) | HTTP |
|------|------|----------------|------|
| `ORDER_NOT_FOUND` | O001 | 주문을 찾을 수 없습니다. | 404 |
| `ORDER_CANCEL_NOT_ALLOWED` | O002 | 취소할 수 없는 주문 상태입니다. | 400 |
| `PRODUCT_NOT_ON_SALE` | O003 | 판매 중이 아닌 상품입니다. | 400 |
| `CART_EMPTY` | O004 | 장바구니가 비어 있습니다. | 400 |
| `CART_NOT_FOUND` | O005 | 장바구니를 찾을 수 없습니다. | 404 |
| `CART_PRODUCT_NOT_FOUND` | O006 | 장바구니 상품을 찾을 수 없습니다. | 404 |
| `CART_PRODUCT_ACCESS_DENIED` | O007 | 해당 장바구니 상품에 접근할 수 없습니다. | 403 |
| `ORDER_ACCESS_DENIED` | O008 | 해당 주문에 접근할 수 없습니다. | 403 |
| `INVALID_ORDER_STATUS_TRANSITION` | O009 | 허용되지 않는 주문 상태 변경입니다. | 400 |
| `ORDER_ALREADY_PROCESSED` | O010 | 이미 처리된 주문입니다. | 409 |
| `ORDER_PRICE_CHANGED` | O011 | 상품 가격이 변경되었습니다. | 409 |
| `ORDER_PRODUCT_NOT_FOUND` | O012 | 주문 상품을 찾을 수 없습니다. | 404 |
| `ORDER_PAYMENT_STATUS_INVALID` | O013 | 결제 완료 처리할 수 없는 주문 상태입니다. | 400 |
| `ORDER_PAYMENT_AMOUNT_MISMATCH` | O014 | 주문 금액과 결제 승인 금액이 일치하지 않습니다. | 400 |
| `ORDER_EXPIRED` | O015 | 만료된 주문입니다. | 409 |
| `ORDER_CONTENT_ACCESS_DENIED` | E001 | 구매 콘텐츠를 열람할 수 없습니다. | 403 |
| `ORDER_REVIEW_ACCESS_DENIED` | E002 | 구매한 상품에만 리뷰를 작성할 수 있습니다. | 403 |

> `O003`은 현재 order-service에서 `PRODUCT_NOT_ON_SALE`로 사용한다. 이전 문서의 `ORDER_FORBIDDEN` 의미와 다르므로 API 문서에서는 현재 구현을 우선한다.
---

## 결제 (PAYMENT)

| enum | code | 의미 (message) | HTTP |
|------|------|----------------|------|
| `PAYMENT_AMOUNT_MISMATCH` | PAY001 | 결제 금액이 일치하지 않습니다. | 400 |
| `PAYMENT_ALREADY_PAID` | PAY002 | 이미 결제된 주문입니다. | 409 |
| `PAYMENT_PG_ERROR` | PAY003 | PG사 처리 중 오류가 발생했습니다. | 502 |
| `PAYMENT_INVALID_STATUS` | PAY004 | 환불 가능한 상태가 아닙니다. | 400 |
| `PAYMENT_CANCEL_FAILED` | PAY005 | 결제 취소 처리에 실패했습니다. | 502 |
| `PAYMENT_REFUND_FAILED` | PAY006 | 환불 처리에 실패했습니다. | 502 |
| `PAYMENT_PG_REJECTED` | PAY007 | PG사에서 결제를 거부했습니다. | 400 |
| `PAYMENT_NOT_FOUND` | PAY008 | 결제 건이 존재하지 않습니다. | 404 |

> PAY003 (502): PG사 서버 오류 / PAY007 (400): PG사 거절 (카드 한도 초과 등 정상 처리된 거부)

---

## 정산 (SETTLEMENT)

| enum | code | 의미 (message) | HTTP |
|------|------|----------------|------|
| `SETTLEMENT_ALREADY_PAID` | S001 | 이미 지급이 완료된 정산입니다. | 409 |
| `SETTLEMENT_NOT_FOUND` | S002 | 정산이 존재하지 않습니다. | 404 |
| `SETTLEMENT_INVALID_STATUS` | S003 | 현재 상태에서 수행할 수 없는 작업입니다. | 409 |
