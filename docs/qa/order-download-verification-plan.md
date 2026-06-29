# 백엔드 검증 계획서

## 1. 검증 목적

구매자가 다운로드 버튼을 클릭했을 때 Order Service에서 해당 주문상품의 다운로드 여부가 정상적으로 저장되는지 검증한다.

```text
order_product.downloaded = true
```

또한 다운로드 완료된 주문상품은 환불이 불가능해야 하므로, 환불/취소 요청 시 `O002` 에러로 차단되는지 확인한다.

응답 필드는 기존 `isRefund`가 아니라 `isRefundable`을 사용한다.

```text
isRefundable = true
→ 환불 가능

isRefundable = false
→ 환불 불가능
```

---

## 2. 검증 범위

이번 백엔드 검증 범위는 다음과 같다.

```text
1. 다운로드 확정 API 검증
2. order_product.downloaded 변경 검증
3. isRefundable 응답 필드 검증
4. 환불/취소 요청 차단 검증
5. 권한 검증
6. 주문 상태 검증
7. 주문상품 상태 검증
8. 재다운로드 요청 멱등성 검증
```

검증 제외 범위는 다음과 같다.

```text
1. downloaded_at 컬럼 검증
2. 관리자 CS 환불 예외 처리
3. 부분 환불 실제 처리
4. Settlement Service 정산 반영
5. Product Service 콘텐츠 조회 자체의 상세 검증
```

---

## 3. 사전 준비 데이터

### 3-1. 기본 주문 데이터

다음 상태의 주문과 주문상품을 준비한다.

```text
Order
- orderStatus = PAID
- buyerId = 테스트 구매자 UUID

OrderProduct
- orderProductStatus = PAID
- downloaded = false
```

### 3-2. 예외 검증용 데이터

```text
1. downloaded = true인 주문상품
2. orderStatus = PENDING인 주문
3. orderStatus = FAILED인 주문
4. orderProductStatus = PENDING인 주문상품
5. 다른 buyerId의 주문
6. 존재하지 않는 orderId
7. 존재하지 않는 orderProductId
```

---

## 4. 검증 대상 API

### 다운로드 확정 API

```http
PATCH /api/v1/orders/{orderId}/products/{orderProductId}/download
```

### 관련 조회 API

```http
GET /api/v1/orders
GET /api/v1/orders/{orderId}
GET /api/v1/orders/payments
```

### 환불/취소 API

현재 구현된 환불 또는 취소 API를 기준으로 검증한다.

예시:

```http
PATCH /api/v1/orders/{orderId}/cancel
```

---

## 5. Service 단위 테스트

### TC-BE-01. 다운로드 성공

#### Given

```text
주문 상태가 PAID이다.
주문상품 상태가 PAID이다.
order_product.downloaded = false이다.
요청 buyerId가 주문 buyerId와 일치한다.
```

#### When

```text
downloadOrderProduct(orderId, orderProductId, buyerId)를 호출한다.
```

#### Then

```text
order_product.downloaded = true로 변경된다.
응답의 downloaded는 true이다.
응답의 isRefundable은 false이다.
```

---

### TC-BE-02. 이미 다운로드된 상품 재요청

#### Given

```text
order_product.downloaded = true이다.
```

#### When

```text
동일한 다운로드 API를 다시 호출한다.
```

#### Then

```text
예외가 발생하지 않는다.
downloaded=true가 유지된다.
isRefundable=false가 반환된다.
```

---

### TC-BE-03. 주문 소유자 불일치

#### Given

```text
주문의 buyerId와 요청 buyerId가 다르다.
```

#### When

```text
다운로드 API를 호출한다.
```

#### Then

```text
권한 없음 예외가 발생한다.
order_product.downloaded 값은 변경되지 않는다.
```

---

### TC-BE-04. 주문 상태가 PAID가 아님

#### Given

```text
orderStatus = PENDING 또는 FAILED 또는 CANCELED이다.
```

#### When

```text
다운로드 API를 호출한다.
```

#### Then

```text
다운로드 처리가 실패한다.
order_product.downloaded 값은 변경되지 않는다.
```

---

### TC-BE-05. 주문상품 상태가 PAID가 아님

#### Given

```text
orderProductStatus = PENDING 또는 FAILED 또는 CANCELED 또는 REFUNDED이다.
```

#### When

```text
다운로드 API를 호출한다.
```

#### Then

```text
다운로드 처리가 실패한다.
order_product.downloaded 값은 변경되지 않는다.
```

---

### TC-BE-06. 주문에 속하지 않은 주문상품 요청

#### Given

```text
orderId는 존재한다.
orderProductId도 존재한다.
하지만 해당 orderProductId는 요청 orderId에 속하지 않는다.
```

#### When

```text
다운로드 API를 호출한다.
```

#### Then

```text
예외가 발생한다.
order_product.downloaded 값은 변경되지 않는다.
```

---

### TC-BE-07. isRefundable 계산 검증

#### Given / Then

```text
PAID + downloaded=false
→ isRefundable=true

PAID + downloaded=true
→ isRefundable=false

REFUNDED + downloaded=false
→ isRefundable=false

CANCELED + downloaded=false
→ isRefundable=false

PENDING + downloaded=false
→ isRefundable=false

FAILED + downloaded=false
→ isRefundable=false
```

---

### TC-BE-08. 다운로드된 상품 환불 차단

#### Given

```text
주문 상태가 PAID이다.
주문상품 중 하나 이상이 downloaded=true이다.
```

#### When

```text
환불 또는 취소 API를 호출한다.
```

#### Then

```text
Payment Service 호출 전에 Order Service에서 차단된다.
응답 코드는 O002이다.
message는 "이미 다운로드한 상품은 환불할 수 없습니다."이다.
```

---

### TC-BE-09. 다운로드하지 않은 상품 환불 가능

#### Given

```text
주문 상태가 PAID이다.
모든 주문상품의 downloaded=false이다.
```

#### When

```text
환불 또는 취소 API를 호출한다.
```

#### Then

```text
Order Service의 다운로드 검증을 통과한다.
Payment Service 환불/취소 요청 로직으로 진행된다.
```

---

## 6. Controller 테스트

### TC-BE-10. 다운로드 API 성공 응답

#### Request

```http
PATCH /api/v1/orders/{orderId}/products/{orderProductId}/download
X-User-Id: {buyerId}
X-User-Role: USER
```

#### Expected Response

```json
{
  "success": true,
  "data": {
    "orderId": "uuid",
    "orderProductId": "uuid",
    "downloaded": true,
    "isRefundable": false
  },
  "message": "success"
}
```

---

### TC-BE-11. 권한 없는 사용자 요청

#### Request

```http
PATCH /api/v1/orders/{otherBuyerOrderId}/products/{orderProductId}/download
X-User-Id: {differentBuyerId}
```

#### Expected Response

```json
{
  "success": false,
  "data": null,
  "code": "A004"
}
```

---

### TC-BE-12. 환불 차단 응답

#### Request

```http
PATCH /api/v1/orders/{orderId}/cancel
```

#### Expected Response

```json
{
  "success": false,
  "data": null,
  "message": "이미 다운로드한 상품은 환불할 수 없습니다.",
  "code": "O002"
}
```

---

## 7. 조회 API 응답 검증

### TC-BE-13. GET /orders 응답 필드 변경

#### 검증 내용

```text
isRefund 필드가 응답에 없어야 한다.
isRefundable 필드가 응답에 있어야 한다.
downloaded 필드가 필요하다면 함께 내려준다.
```

#### Expected

```json
{
  "orderId": "uuid",
  "orderProductId": "uuid",
  "orderStatus": "PAID",
  "downloaded": true,
  "isRefundable": false
}
```

---

### TC-BE-14. GET /orders/{orderId} 응답 검증

#### 검증 내용

```text
hasDownloadedProduct가 true로 내려오는지 확인한다.
orderProducts[].downloaded가 true로 내려오는지 확인한다.
orderProducts[].isRefundable이 false로 내려오는지 확인한다.
```

---

### TC-BE-15. GET /orders/payments 응답 검증

#### 검증 내용

```text
기존 isRefund가 제거되었는지 확인한다.
isRefundable이 정상 반환되는지 확인한다.
다운로드 완료 상품은 isRefundable=false인지 확인한다.
```

---

## 8. DB 검증 쿼리

다운로드 API 호출 전:

```sql
SELECT id, order_id, downloaded
FROM order_product
WHERE id = '{orderProductId}';
```

기대값:

```text
downloaded = false
```

다운로드 API 호출 후:

```sql
SELECT id, order_id, downloaded
FROM order_product
WHERE id = '{orderProductId}';
```

기대값:

```text
downloaded = true
```

---

## 9. 수동 검증 절차

```text
1. PAID 상태의 주문을 준비한다.
2. 해당 주문상품의 downloaded=false를 확인한다.
3. 다운로드 API를 호출한다.
4. 응답에서 downloaded=true, isRefundable=false를 확인한다.
5. DB에서 downloaded=true를 확인한다.
6. GET /orders를 호출한다.
7. 해당 주문상품의 isRefundable=false를 확인한다.
8. 환불 또는 취소 API를 호출한다.
9. O002 응답을 확인한다.
10. 동일 다운로드 API를 다시 호출한다.
11. 정상 성공하는지 확인한다.
```

---

## 10. 백엔드 완료 기준

```text
1. 다운로드 API 호출 시 downloaded=true로 변경된다.
2. 이미 다운로드된 상품에 대해 재요청해도 정상 응답한다.
3. isRefund 필드는 더 이상 응답하지 않는다.
4. isRefundable 필드가 모든 관련 조회 API에 반영된다.
5. 다운로드된 상품은 isRefundable=false이다.
6. 다운로드된 상품이 포함된 주문은 환불/취소 요청 시 O002로 차단된다.
7. 주문 소유자가 아닌 사용자는 다운로드 처리할 수 없다.
8. PAID 상태가 아닌 주문/주문상품은 다운로드 처리할 수 없다.
9. 단위 테스트와 Controller 테스트가 통과한다.
```
