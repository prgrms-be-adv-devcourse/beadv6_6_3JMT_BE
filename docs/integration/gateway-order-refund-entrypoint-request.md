# Gateway 주문 다건 부분 환불 진입점 요청

## 요청 사항

- Gateway는 이미 `/api/v2/orders/**`를 Order Service로 라우팅한다. Order Service의 공개 환불 진입점은 `POST /api/v2/orders/{orderId}/refund`이며, 이 경로를 같은 라우팅 정책으로 제공해야 한다.
- Gateway는 `/api/v2/payments/{paymentId}/refund` 경로를 차단해야 한다. 클라이언트가 Payment Service 환불 API를 직접 호출해서 Order Service의 주문 상품 검증과 상태 전이를 우회하면 안 된다.
- 구매자 요청에는 기존처럼 `X-User-Id`와 구매자 역할을 나타내는 `X-User-Role: BUYER` trusted header를 계속 주입해야 한다.

## 롤아웃 조건

1. `POST /api/v2/orders/{orderId}/refund`가 Order Service에만 전달되는지 확인한다.
2. `/api/v2/payments/{paymentId}/refund`가 외부에서 차단되는지 확인한다.
3. 환불 요청에서 BUYER header 주입이 유지되는지 확인한 뒤 공개한다.
