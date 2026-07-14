# Payment 환불 상태 gRPC 계약 요청

## 요청 범위

Order Service는 누락된 Payment 결과 이벤트를 복구하기 위해 Payment Service에 환불 상태를 조회한다. Payment 팀이 소유하는 `grpc/payment/payment_refund_query.proto`는 저장소 루트에 병합됐으며 Order Service는 공유 proto에서 코드를 생성한다.

## 병합된 proto

```proto
syntax = "proto3";
package prompthub.payment.refund.v1;
option java_multiple_files = true;
option java_package = "com.prompthub.grpc.payment.refund.v1";
option java_outer_classname = "PaymentRefundQueryProto";

service PaymentRefundQueryService {
  rpc GetRefundStatus(GetRefundStatusRequest) returns (GetRefundStatusResponse);
}

message GetRefundStatusRequest { string refund_request_id = 1; }

enum RefundStatus {
  REFUND_STATUS_UNSPECIFIED = 0;
  REFUND_STATUS_PROCESSING = 1;
  REFUND_STATUS_COMPLETED = 2;
  REFUND_STATUS_FAILED = 3;
  REFUND_STATUS_NOT_FOUND = 4;
}

message GetRefundStatusResponse {
  RefundStatus status = 1;
  string refunded_at = 2;
  string failure_code = 3;
  string failure_reason = 4;
}
```

## 롤아웃 조건

- proto 파일은 병합됐지만 현재 Payment Service에는 `PaymentRefundQueryService` 서버 구현이 확인되지 않는다. 서버가 배포되기 전에는 Order Service의 상태 조회 복구 작업을 활성화하지 않는다.
- `GetRefundStatus`는 `refund_request_id`로 조회하며, Payment가 보관한 환불 결과가 진실의 원천이다.
- 응답이 요청 식별자·금액·`retryable`·실패 시각을 반환하지 않으므로 Order Service는 저장된 환불 aggregate의 식별자와 금액을 authoritative 값으로 사용하고, gRPC 실패 결과는 non-retryable 및 조회 시각 기준으로 반영한다.
