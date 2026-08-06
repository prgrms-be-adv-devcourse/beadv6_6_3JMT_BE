# Order 이벤트 수신 경로 제거 설계

## 배경

정산 원천 데이터 수급은 `order-events`의 `ORDER_PAID`, `ORDER_REFUNDED`를 실시간 소비하는 방식에서
Order 서비스의 `GetSettleableLines(period)` gRPC 조회 방식으로 전환됐다. Kafka 컨슈머는 기본값이
비활성화된 폴백으로 남아 있으며, gRPC pull 경로와 서로 다른 멱등키를 사용한다. 두 경로가 함께
활성화되면 같은 주문상품과 라인 타입이 중복 적재될 수 있다.

현재 `develop`의 Order 서비스에는 공유 proto만 있고 gRPC 서버 구현은 없다. 이번 작업은 이 과도기
공백을 감수하고 사용하지 않는 Kafka 폴백을 제거해 정산 원천 수급 경로를 gRPC pull 하나로 명확히 한다.

## 결정

Kafka 컨슈머만 삭제하지 않고 수신 경로에만 필요했던 DTO, 유스케이스, 서비스 메서드, 설정과 테스트를
함께 제거한다. 정산 결과를 `settlement-events`로 발행하는 Kafka producer와 Outbox 경로는 유지한다.

## 변경 범위

### 제거

- `infrastructure/messaging/kafka/consumer/order`의 `OrderEventConsumer`, `OrderEventType`
- `application/event`의 `OrderEventEnvelope`, `OrderPaidEvent`, `OrderPaidProduct`,
  `OrderRefundedEvent`, `OrderRefundedProduct`
- `application/usecase/SettlementSourceUseCase`
- `SettlementSourceApplicationService`의 `recordOrderPaid`, `recordOrderRefunded`와 push 전용 멱등키 생성식
- `KafkaConfig`의 Order 소비자 Factory, Listener Container Factory, 수동 Ack와 DLT 오류 처리 설정
- `application-local.yml`의 Order Kafka listener 활성화 플래그와 소비자 전용 설정
- `OrderEventConsumerTest`와 이벤트 push 경로만 검증하는 서비스 테스트

### 유지

- `LoadSettlementSourceUseCase`와 `OrderSettlementQueryPort`
- `OrderSettlementQueryClient`의 `GetSettleableLines(period)` 호출
- `SettlementSourceApplicationService.load(period)`의 bulk 적재와 `orderProductId | lineType` 멱등키
- 정산 이벤트 발행용 Kafka producer, Transactional Outbox, 재시도와 재처리 경로
- `settlement_source_line` 도메인과 저장소 계약

### 문서

현행 상태를 설명하는 내부통신 토폴로지와 통합 카탈로그는 Kafka 구독이 제거됐고 gRPC pull만 남았다는
내용으로 갱신한다. 과거 의사결정 문서는 이관 당시의 기록을 보존하되, 현재 상태를 오해할 수 있는 문구는
제거 완료 상태로 정정한다.

## 모듈 경계

`config/src/main/resources/configs/settlement-service.yml`의
`settlement.kafka.listener.order.enabled: false`는 정산 모듈 작업 범위 밖이다. 이번 브랜치에서는
수정하지 않고 `#317 (이슈)`의 후속 정리 항목으로 남긴다.

## 데이터 흐름

정산 배치가 시작되면 `LoadSettlementSourceTasklet`이 `LoadSettlementSourceUseCase.load(period)`를 호출한다.
서비스는 `OrderSettlementQueryPort`를 통해 Order gRPC 응답을 받고, 각 라인의
`orderProductId | lineType`으로 event ID를 파생해 기존 라인을 제외한 뒤 `settlement_source_line`에 bulk
저장한다. 이후 정산 계산과 Outbox 발행 흐름은 변경하지 않는다.

## 실패 처리

Order gRPC 호출이 실패하면 기존과 같이 `SETTLEMENT_SOURCE_QUERY_FAILED`로 배치를 실패시킨다. Kafka
폴백은 동작하지 않는다. Order gRPC 서버가 배포되기 전에는 정산 원천 수급 경로가 없다는 점을 이슈와
문서에 명시한다.

## 검증

- 제거 대상 타입과 `settlement.kafka.listener.order` 참조가 `settlement-service`의 실행 코드와 테스트에
  남지 않았는지 정적 검색한다.
- `SettlementSourceLoadServiceTest`와 `OrderSettlementQueryClientTest`로 gRPC pull 적재 경로를 검증한다.
- `settlement-service` 전체 테스트를 실행해 컴파일과 회귀 여부를 확인한다.
- Kafka producer와 Outbox 관련 테스트가 유지되는지 확인한다.
