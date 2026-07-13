# common-module·order-service 보수적 공통화 설계

## 목표

`common-module`과 `order-service`만 수정하여 중복된 설정 및 일회성 하위 이벤트 타입을 정리한다.

이번 작업은 구조 정리만 수행하며 다음 동작을 유지한다.

- Kafka 이벤트 JSON 필드명과 계층 구조
- 기존 `EventMessage` 생성 및 역직렬화 허용 범위
- gRPC 대상 서비스 이름과 Bean 구성
- 주문·환불 비즈니스 흐름과 트랜잭션 경계
- API, DB 스키마, Proto 계약

## 변경 범위

### 1. EventMessage 동작 유지

`common-module`의 `EventMessage.create(...)`와 현재 생성자 동작을 그대로 유지한다.

- 필수 메타데이터를 강제하는 생성자 검증을 추가하지 않는다.
- 기존 메시지가 재시도 후 DLT로 이동할 수 있는 수신 동작 변경을 만들지 않는다.
- Spring, Kafka 등 인프라 의존성을 `common-module`에 추가하지 않는다.

### 2. gRPC 클라이언트 설정 통합

`order-service`의 아래 세 설정 클래스를 하나의 `GrpcClientConfig`로 통합한다.

- `ProductGrpcClientConfig`
- `SellerGrpcClientConfig`
- `PaymentRefundGrpcClientConfig`

통합 클래스는 기존과 동일하게 다음 조건을 유지한다.

- `@Configuration`
- `@Profile({"dev", "prod"})`
- `product`, `seller`, `payment-refund` 채널 이름
- 기존 Stub Bean 타입 및 Bean 이름

기존 `GrpcClientConfigTest`는 통합 클래스 기준으로 수정한다.

### 3. 환불 이벤트 하위 Payload 중첩

한 상위 Payload에서만 사용하는 하위 record를 해당 상위 record 안으로 이동한다.

- `RefundRequestedProductPayload` → `RefundRequestedPayload`의 중첩 record
- `OrderRefundedProductPayload` → `OrderProductRefundedPayload`의 중첩 record

중첩 record는 외부 테스트와 Jackson 직렬화에서 사용할 수 있도록 공개 타입으로 유지한다. 타입의 위치만 변경하며 record 컴포넌트 이름, 타입, 순서와 상위 JSON 필드 구조는 유지한다.

## 제외 범위

이번 작업에서는 다음 항목을 변경하지 않는다.

- 이벤트 필수 필드 검증 및 DLT 정책
- `OrderEventMessageFactory`와 `OrderEventAppender` 통합
- 주문 실패·취소 이벤트 Processor 통합
- Outbox Adapter·Repository 통합
- 스케줄러 설정 통합
- `product-service`, `payment-service`, `settlement-service` 등 다른 모듈
- API, DB 마이그레이션, Proto, 외부 설정

위 항목 중 Factory/Appender 및 Processor 통합은 이번 작업 검증 후 별도로 논의한다.

## 예상 효과

운영 코드 기준으로 파일 4개를 줄인다.

- gRPC 설정 3개를 1개로 통합: 2개 감소
- 독립 하위 Payload 파일 2개를 상위 타입에 중첩: 2개 감소

설계 문서는 작업 근거를 남기는 산출물이며 운영 코드 파일 감소 계산에서는 제외한다.

## 구현 및 검증 전략

테스트 우선으로 변경한다.

1. `GrpcClientConfigTest`를 목표 구조로 먼저 변경하여 새 통합 클래스가 없어 실패하는지 확인한다.
2. 통합 설정 클래스를 만들고 기존 세 설정 클래스를 제거한 뒤 해당 테스트를 통과시킨다.
3. 이벤트 Payload 테스트에서 목표 중첩 타입을 먼저 참조하여 새 타입이 없어 실패하는지 확인한다.
4. 하위 record를 중첩하고 기존 독립 파일을 제거한 뒤 직렬화 구조와 생성 결과를 확인한다.
5. 전체 모듈 테스트와 빌드, whitespace 검사를 수행한다.

검증 명령은 다음과 같다.

```bash
./gradlew :common-module:test :order-service:test --rerun-tasks
./gradlew :order-service:build
git diff --check
```

마지막으로 변경 파일을 확인하여 `common-module`과 `order-service` 밖의 파일을 수정하지 않았는지 검증한다. 기존 작업 트리의 다른 변경은 수정하거나 정리하지 않는다.
