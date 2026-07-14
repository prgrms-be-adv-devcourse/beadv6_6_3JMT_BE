# Payment 환불 상태 gRPC 계약 요청

## 요청 범위

Order Service는 누락된 Payment 결과 이벤트를 복구하기 위해 Payment Service에 환불 상태를 조회한다. Payment 팀이 최종 파일 `grpc/payment/payment_refund_query.proto`를 소유하며, Order Service는 이 작업에서 로컬 proto 복제본이나 임시 어댑터를 만들지 않는다.

## 제안 proto

```proto
syntax = "proto3";
package prompthub.payment;
option java_multiple_files = true;
option java_package = "com.prompthub.payment.grpc";
option java_outer_classname = "PaymentRefundQueryProto";

service PaymentRefundQueryService {
  rpc GetRefundStatus(GetRefundStatusRequest) returns (GetRefundStatusResponse);
}

message GetRefundStatusRequest { string refund_request_id = 1; }

enum RefundStatus {
  REFUND_STATUS_UNSPECIFIED = 0;
  REQUESTED = 1;
  PROCESSING = 2;
  COMPLETED = 3;
  FAILED = 4;
  NOT_FOUND = 5;
}

message GetRefundStatusResponse {
  string refund_request_id = 1;
  string payment_id = 2;
  string order_id = 3;
  int32 total_refund_amount = 4;
  RefundStatus status = 5;
  string refunded_at = 6;
  string failure_code = 7;
  string failure_reason = 8;
  bool retryable = 9;
  string failed_at = 10;
}
```

## 롤아웃 조건

- Payment 팀이 위 파일과 서비스 계약을 제공하고 호환 가능한 stub을 배포한 뒤에만 Order Service의 상태 조회 복구 작업을 활성화한다.
- `GetRefundStatus`는 `refund_request_id`로 조회하며, Payment가 보관한 환불 결과가 진실의 원천이다.
