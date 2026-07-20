# 결제 조회 gRPC(`PaymentQueryService.GetPayment`) 제거 작업 문서

order-service가 Kafka 결제 이벤트 유실 시 쓰던 gRPC 폴백 조회(`GetPayment`)와 관련 payment-service 코드 전체를 제거한다.

---

## 배경 및 목표

`grpc/payment/payment_query.proto`의 `GetPayment` rpc는 이슈 #344에서, order-service가 `PAYMENT_APPROVED`/`PAYMENT_FAILED` Kafka 이벤트를 못 받았을 때 payment-service에 직접 조회해 복구하는 폴백 수단으로 추가됐다.

같은 proto의 자매 rpc였던 `GetRefund`(환불 폴백 조회, #16 선례)는 이미 "order-service/settlement-service 어디서도 호출하지 않는 죽은 엔드포인트"로 판정되어 제거된 전례가 있다(커밋 `1def8de8`). 이번 작업은 그 뒤에 유일하게 남아있던 `GetPayment`까지 정리해 `PaymentQueryGrpcService`와 `payment_query.proto` 자체를 없애는 것이 목적이다.

작업은 현재 체크아웃돼 있던 브랜치 `refactor/#420-remove-payment-grpc-fix-event-schema`(이슈 #420) 위에서 진행했다. 브랜치명의 `-fix-event-schema` 부분은 이번 작업 범위가 아니며, 이번 세션은 `remove-payment-grpc` 부분만 다뤘다.

**남는 트레이드오프**: 이 제거로 order-service가 Kafka 이벤트를 놓쳤을 때의 복구 경로가 완전히 사라진다. `GetRefund`처럼 order-service 쪽에서 실제로 이 rpc를 호출하는 코드가 있었는지는 payment-service 리포지토리 범위 밖이라 이번 조사에서 확인하지 못했다. 사용자가 제거를 명시적으로 요청했으므로 그대로 진행했다.

## 조사 결과

- `PaymentQueryGrpcService`(`infrastructure.grpc`)가 유일한 gRPC 서버 구현체였고, `GetPaymentUseCase`/`GetPaymentService`/`GetPaymentCommand`/`PaymentQueryResult`는 전부 이 기능 전용으로 다른 호출자가 없었다.
- `PaymentRepository.findLatestByOrderId` → `PaymentJpaRepository.findTopByOrderIdOrderByCreatedAtDesc`도 `GetPaymentService` 외 호출자가 없어 함께 제거 대상이었다.
- `PaymentErrorCode.PAYMENT_NOT_FOUND`는 `RefundService`도 던지는 예외라 **삭제하지 않고 유지**했다(조사 없이 지웠다면 환불 흐름이 깨졌을 것).
- `build.gradle`의 `io.grpc:grpc-inprocess`는 처음엔 `PaymentQueryGrpcServiceTest` 전용으로 보여 제거 대상으로 잡았으나, 빌드 시 `OrderGrpcClientAdapterTest`(order gRPC 클라이언트 테스트)도 in-process 서버로 이 의존성을 쓰는 게 확인되어 제거 계획을 취소하고 유지했다. `spring-boot-starter-grpc-server`와 `grpc/payment` proto srcDir만 제거했다.
- `../k8s/base/services/payment/deployment.yaml`, `service.yaml`, `../docs/architecture/kubernetes.md`도 원래 계획에는 포함돼 있었으나, 사용자가 작업 중 "쿠버네티스 관련 수정은 하지마"라고 범위를 좁혀 최종적으로는 손대지 않았다(`kubernetes.md`는 한 차례 수정했다가 되돌림).

## 제거/수정 파일

**완전 삭제**
- `grpc/payment/payment_query.proto` (디렉터리 내 유일한 파일 — 디렉터리째 삭제)
- `payment-service/src/main/java/.../infrastructure/grpc/PaymentQueryGrpcService.java`
- `payment-service/src/main/java/.../application/usecase/GetPaymentUseCase.java`
- `payment-service/src/main/java/.../application/service/GetPaymentService.java`
- `payment-service/src/main/java/.../application/dto/command/GetPaymentCommand.java`
- `payment-service/src/main/java/.../application/dto/result/PaymentQueryResult.java`
- `payment-service/src/test/java/.../infrastructure/grpc/PaymentQueryGrpcServiceTest.java`
- `payment-service/src/test/java/.../application/service/GetPaymentServiceTest.java`

**부분 수정**
- `domain/repository/PaymentRepository.java`, `infrastructure/persistence/PaymentRepositoryAdapter.java`, `infrastructure/persistence/PaymentJpaRepository.java` — `findLatestByOrderId`/`findTopByOrderIdOrderByCreatedAtDesc` 제거
- `src/test/.../persistence/PaymentJpaRepositoryTest.java` — 관련 테스트 2개(`findLatestByOrderId_여러건_중_최신_반환`, `findLatestByOrderId_없으면_empty`) 제거
- `build.gradle` — `spring-boot-starter-grpc-server`, `grpc/payment` proto srcDir 제거 (`grpc-inprocess`, `grpc-client`, `grpc/order` srcDir은 order 클라이언트용이라 유지)
- `src/main/resources/application-local.yml` — `spring.grpc.server.port` 제거 (`grpc.client.channel.order`는 유지)
- `.claude/docs/events.md`, `../docs/architecture/overview.md` — gRPC 폴백 조회 서술 삭제

## 검증

`JAVA_HOME=~/.asdf/installs/java/temurin-21.0.5+11.0.LTS ../gradlew :payment-service:build` — 컴파일, 전체 테스트, checkstyle 모두 통과(`BUILD SUCCESSFUL`).

## 커밋

단일 커밋 `d7469d14` (`refactor: 결제 조회 gRPC(PaymentQueryService.GetPayment) 제거`), 16 files changed, +2/-385. k8s 관련 변경은 최종적으로 제외되어 별도 커밋이 필요 없었다.
