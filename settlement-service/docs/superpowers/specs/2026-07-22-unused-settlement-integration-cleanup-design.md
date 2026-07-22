# 정산 연동 미사용 코드 정리 설계

## 배경

#500 (이슈)은 정산 도메인이 `settlement-service`, user-service의 `sellersettlement`,
admin-service의 `admin.settlement`로 분리된 뒤 남은 gRPC와 Kafka 연동 잔재를 정리한다.

현재 세 모듈의 main·test 소스와 proto를 역참조한 결과, 실제 호출자가 사라진 코드는 두 묶음이다.

- user-service의 `ProductSellerQueryGrpcService`와 전용 proto·테스트·서버 설정
- settlement-service의 선언만 남은 `SettlementEventType`

반면 settlement-service의 Order gRPC 클라이언트와 `SETTLEMENT_CREATED` Kafka Outbox 발행,
user-service의 정산 이벤트 소비는 현재 데이터 흐름에 필요하다. admin-service의 정산 패키지는
Kafka나 gRPC를 사용하지 않고 JPA로 운영 테이블을 직접 접근한다.

## 목표

- 세 모듈 안에서 호출자가 없는 gRPC 서버와 이벤트 타입을 제거한다.
- 마지막 gRPC 서버 제거에 따라 user-service 내부의 전용 빌드·보안·로컬 설정도 함께 정리한다.
- 동작 중인 settlement → order gRPC와 settlement → user Kafka seed 흐름을 보존한다.
- admin settlement의 JPA 직접 접근에 회귀가 없음을 검증한다.
- 현재 통신 구조를 설명하는 settlement-service와 user-service 내부 문서를 코드와 동기화한다.

## 작업 범위

직접 수정 범위는 다음 세 곳으로 제한한다.

- `settlement-service/` 전체
- `user-service/` 전체
- `admin-service/src/main/java/com/prompthub/admin/settlement/`
- `admin-service/src/test/java/com/prompthub/admin/settlement/`

admin settlement에는 제거 대상이 없으므로 소스 변경 없이 회귀 테스트만 수행한다.

## 제외 범위

- 저장소 루트의 `config/`
- 저장소 루트의 `k8s/`
- 저장소 루트의 `docker-compose.yml`
- product-service와 order-service의 코드·설정
- 루트 `docs/`
- Kafka listener 활성화 여부 변경
- Kafka 토픽, consumer group, 재시도와 DLT 정책 변경
- settlement Outbox 스키마·발행·재처리 동작 변경
- settlement → order gRPC 계약·채널·오류 처리 변경
- admin settlement의 데이터 접근 방식 변경

범위 밖 파일에 user gRPC 포트나 과거 클라이언트 설정이 남더라도 이번 작업에서 수정하지 않는다.
구현 계획과 완료 보고에 잔여 참조를 기록하고 별도 정리 대상으로 남긴다.

## 현재 사용 여부 판정

### user-service Product Seller gRPC

Product 측 Seller gRPC 호출자는 #440 (이슈)에서 제거됐다. 저장소의 main 코드에서
`com.prompthub.product.grpc.seller` 생성 타입을 사용하는 곳은 user-service의
`ProductSellerQueryGrpcService`뿐이며, 서버를 호출하는 클라이언트는 없다.

따라서 다음 묶음은 함께 제거한다.

- `seller/presentation/grpc/ProductSellerQueryGrpcService.java`
- `src/main/proto/product_seller_query.proto`
- `ProductSettlementSellerQueryGrpcServiceTest.java`
- `global/config/GrpcSecurityConfig.java`
- `http/grpc.http`
- user-service의 protobuf plugin과 gRPC server·stub·protobuf 의존성
- user-service의 protobuf 코드 생성 설정
- user-service `application-local.yml`의 `grpc.server.port`

`SellerQueryUseCase`와 REST Seller 조회 API는 gRPC와 독립적으로 실제 사용되므로 유지한다.

### settlement-service 이벤트 타입

`application/event/SettlementEventType`은 main·test 어디에서도 참조되지 않는다.
`JsonOutboxEventAppender`는 현재 `SETTLEMENT_CREATED` 문자열을 직접 사용해 `EventMessage`를 만들므로
이 enum 삭제는 발행 payload나 토픽에 영향을 주지 않는다.

이번 작업은 미사용 enum만 제거한다. enum 상수 사용으로 발행 코드를 재구성하는 리팩터링은 범위에 넣지
않는다.

### 유지할 통신 경로

다음 경로는 실제 사용 중이므로 삭제하거나 동작을 바꾸지 않는다.

- settlement-service `OrderSettlementQueryClient` → order-service `GetSettleableLines`
- settlement-service `JsonOutboxEventAppender` → `KafkaSettlementEventPublisher`
- `settlement-events`의 `SETTLEMENT_CREATED`
- user-service `SettlementEventConsumer` → `SeedSellerSettlementUseCase`
- user-service Kafka DLT producer와 error handler

### admin settlement

admin settlement는 `@KafkaListener`, `KafkaTemplate`, `@GrpcService`, gRPC stub과 client 설정을 사용하지
않는다. `SettlementApplicationService`가 JPA repository와 seller 이름 조회용 자체 adapter를 사용하므로
삭제할 통신 잔재가 없다.

## 문서 반영

현재 구조를 설명하는 settlement-service와 user-service 내부 문서만 갱신한다.

- `user-service/docs/settlement-convention-alignment-backlog.md`에서 이미 삭제된
  `SettlementSellerQueryGrpcService` 네이밍 백로그와 gRPC 서버 배치 설명을 제거한다.
- `settlement-service/docs/architecture/settlement-internal-comm-topology.md`에서 user-service Seller gRPC
  서버가 live라는 설명을 제거하고 공개 REST Seller 조회 경계로 현행화한다.
- `settlement-service/docs/architecture/kafka-messaging-design.md`의 구현 트리에서 미사용
  `SettlementEventType`을 제거한다.
- 과거 `docs/superpowers/specs/`와 `docs/superpowers/plans/`, user-service의 날짜 기반 설계 문서는 당시
  기록이므로 소급 수정하지 않는다.

루트 문서는 이번 작업 범위 밖이므로 수정하지 않는다.

## 구현 원칙

- 새 기능이나 대체 추상화를 추가하지 않는다.
- 삭제 대상의 마지막 main 사용처가 실제로 없는지 정적 검색으로 다시 확인한 뒤 제거한다.
- generated protobuf 산출물이 빌드 캐시에 남지 않도록 user-service 검증은 `clean`부터 시작한다.
- 범위 밖 참조를 없애기 위해 루트나 다른 서비스 파일을 수정하지 않는다.
- 삭제 중 Kafka seed나 Order gRPC에 영향을 주는 문제가 발견되면 구현을 확대하지 않고 별도 이슈 후보로
  보고한다.

## 검증

### 정적 검증

- user-service main·test·proto에서 `ProductSellerQueryGrpcService`,
  `com.prompthub.product.grpc.seller`, `product_seller_query.proto`, `GrpcSecurityConfig` 참조가 0건이어야 한다.
- user-service `build.gradle`에 gRPC server·stub·protobuf 의존성과 protobuf 코드 생성 블록이 없어야 한다.
- settlement-service main·test에서 `SettlementEventType` 참조가 0건이어야 한다.
- `SettlementEventConsumer`, `KafkaSettlementEventPublisher`, `OrderSettlementQueryClient`는 그대로 남아 있어야 한다.
- admin settlement main·test에는 변경 diff가 없어야 한다.

### 테스트와 빌드

1. user-service를 clean 후 컴파일하고 전체 테스트·Checkstyle을 실행한다.
2. settlement-service의 Outbox 발행·저장과 Order gRPC 클라이언트 테스트를 실행한다.
3. settlement-service 전체 테스트·Checkstyle을 실행한다.
4. admin-service 정산 애플리케이션·컨트롤러·repository 테스트를 실행한다.
5. 세 모듈의 전체 diff에서 Kafka seed와 Order gRPC 동작 변경이 없는지 확인한다.

## 완료 조건

- 세 모듈 안의 확정된 고아 gRPC 서버 묶음과 미사용 이벤트 enum이 제거된다.
- user-service가 protobuf 생성 없이 컴파일·테스트된다.
- settlement Kafka Outbox와 user Kafka consumer가 기존 구조로 유지된다.
- settlement → order gRPC 테스트가 통과한다.
- admin settlement 테스트가 통과하고 해당 패키지에 코드 변경이 없다.
- 범위 밖 잔여 참조가 구현 계획과 완료 보고에 명시된다.
