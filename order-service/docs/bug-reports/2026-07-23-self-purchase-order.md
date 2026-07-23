---
name: 버그 리포트 (Bug Report)
about: 판매자가 본인의 상품을 주문할 수 있는 문제를 보고합니다.
title: "[BUG] 판매자가 본인 상품을 주문할 수 있음"
labels: bug
assignees: ''
---

## 🐞 버그 설명 (Description)

판매자가 구매자로서 본인이 판매하는 상품을 주문할 수 있습니다.

주문 생성 시 Product Service 상품 스냅샷에 포함된 `sellerId`와 요청자의 `X-User-Id`를 비교하지 않아, 본인 상품이 무료인지 유료인지와 관계없이 주문 생성 흐름이 진행됩니다. 여러 상품을 한 번에 주문할 때 본인 상품이 하나만 포함되어도 주문 전체가 생성될 수 있습니다.

- 관련 이슈: [#520](https://github.com/prgrms-be-adv-devcourse/beadv6_6_3JMT_BE/issues/520)
- 영향 API: `POST /api/v2/orders`

## 🔁 재현 단계 (Steps to Reproduce)

1. 판매자 ID를 `<seller-id>`로 사용하는 계정과, 해당 판매자가 등록한 상품 ID `<own-product-id>`를 준비합니다.
2. 다음 요청을 판매자 본인의 사용자 ID로 전송합니다.

   ```http
   POST /api/v2/orders
   X-User-Id: <seller-id>
   Content-Type: application/json
   ```

   ```json
   {
     "products": [
       {
         "productId": "<own-product-id>",
         "productTitle": "본인 판매 상품"
       }
     ]
   }
   ```

3. 응답 상태와 주문 저장 여부를 확인합니다.
4. 재현 범위를 확인하려면 다른 판매자의 상품 `<other-product-id>`를 같은 요청에 추가해 다건 주문으로도 시도합니다.

   ```json
   {
     "products": [
       {
         "productId": "<own-product-id>",
         "productTitle": "본인 판매 상품"
       },
       {
         "productId": "<other-product-id>",
         "productTitle": "다른 판매자 상품"
       }
     ]
   }
   ```

## ✅ 예상 결과 (Expected Behavior)

요청 상품의 `sellerId`가 `X-User-Id`와 같으면 주문 생성을 거부해야 합니다.

- HTTP 상태: `403 Forbidden`
- 응답 코드: `O015`
- 응답 메시지: `본인이 판매하는 상품은 구매할 수 없습니다.`
- 다건 주문에 본인 상품이 하나라도 있으면 주문 전체를 거부
- 주문·주문 상품 저장, 장바구니 삭제, 무료 주문 Outbox 추가, 유료 주문 이벤트 발행이 발생하지 않음

## ❌ 실제 결과 (Actual Behavior)

구매자 ID와 상품 판매자 ID가 같아도 주문 생성이 계속 진행됩니다.

- 본인 무료 상품은 주문이 생성·완료되고 후속 Outbox 처리가 진행될 수 있음
- 본인 유료 상품은 주문이 생성되고 결제 후속 이벤트 흐름으로 진행될 수 있음
- 다건 주문에 본인 상품이 포함되어도 전체 주문이 생성될 수 있음
- 주문 생성 과정에서 장바구니 상품이 제거될 수 있음

그 결과 판매량, 구매 권한, 결제·정산 대상 데이터에 실제 구매가 아닌 셀프 구매 기록이 남을 수 있습니다.

## 🖼 스크린샷 (Screenshots)

해당 없음. API 요청·응답과 주문 상태로 재현되는 서버 버그입니다.

## ⚙️ 환경 (Environment)

- 서비스: `order-service`
- 실행 환경: Java 21, Spring Boot 4.1.0
- API: `POST /api/v2/orders`
- 인증: Gateway가 전달하는 `X-User-Id` 헤더
- 기준 코드: `develop` 기준 이슈 보고 시점
- 브라우저: 해당 없음 (REST API)

## ℹ️ 추가 정보 (Additional Information)

- 무료·유료 상품, 단건·다건 주문, 직접 주문·장바구니 기반 주문 모두 동일한 주문 생성 흐름을 사용하므로 같은 정책이 적용되어야 합니다.
- `O018 이미 구매한 상품입니다.`는 과거 구매 이력에 대한 오류이고, 판매자가 본인 상품을 주문하는 행위는 구매 이력과 무관한 정책 위반이므로 별도의 `O015` 오류 계약이 필요합니다.
- 이 문서는 저장소의 `.github/ISSUE_TEMPLATE/bug_report.md` 형식을 따라 작성한 이슈 #520 버그 리포트입니다.
