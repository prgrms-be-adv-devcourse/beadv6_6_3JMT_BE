# gRPC Seller Settlement Delivery Implementation Plan

> **For Codex:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Kafka Outbox 기반 정산 전달을 정산 건별 gRPC 등록과 저장 결과 직접 대사로 교체하고, 전달 실패·불일치를 `settlement_delivery` 원장에 남긴다.

**Architecture:** Settlement 계산 트랜잭션에서 `Settlement`와 `SettlementDelivery(CALCULATED)`를 함께 저장한다. 배치 완료 뒤 Delivery Step이 정산을 순차 호출하고, User Service는 멱등 등록을 커밋한 다음 별도 읽기 트랜잭션으로 저장 Snapshot을 반환한다. Settlement Service는 응답 전체를 직접 비교해 `RECONCILED`, `MISMATCH`, `DELIVERY_FAILED` 중 하나를 저장한다.

**Tech Stack:** Java 21, Spring Boot 4.0, Spring Batch, Spring Data JPA, Flyway, PostgreSQL, Spring gRPC/Protocol Buffers, JUnit 5, Mockito, Testcontainers, Kubernetes Kustomize

---

## 구현 원칙

- 작업 위치는 `/Users/taetaetae/IdeaProjects/beadv6_6_3JMT_BE-637`이고 브랜치는 `feat/#637-grpc-settlement-delivery-reconciliation`을 유지한다.
- 기준 브랜치는 `feat/#635-settlement-batch-reconciliation`이다.
- Settlement Flyway는 V8, User Flyway는 V4만 사용한다. 적용 순서는 Settlement V6 → V7 → V8이다.
- 공유 proto와 운영 설정 변경은 사용자가 승인한 범위다.
- #639의 Order/SourceLine 대사, #642의 계산 합계와 SourceLine→Detail 연결, #655의 어드민 재전송은 구현하지 않는다.
- 모든 도메인 상태 전이, 멱등성, 금액 비교, 재시도는 실패 테스트를 먼저 만든다.
- 각 Task의 테스트가 통과한 뒤 해당 Task만 커밋한다. 관련 없는 사용자 변경은 stage하지 않는다.

## Task 1: 공용 gRPC Command 계약 추가

**Files:**

- Create: `grpc/user/seller_settlement_command.proto`
- Modify: `settlement-service/build.gradle`
- Create: `settlement-service/src/test/java/com/prompthub/settlement/infrastructure/client/user/SellerSettlementCommandGrpcContractTest.java`
- Create: `user-service/src/test/java/com/prompthub/user/sellersettlement/infrastructure/grpc/SellerSettlementCommandGrpcContractTest.java`

- [ ] **Step 1: 생성될 서비스와 필드 번호를 고정하는 계약 테스트 작성**

두 모듈의 계약 테스트는 생성 클래스가 존재하고 RPC가 하나뿐이며 이름이 `RegisterSellerSettlement`인지 확인한다.

```java
@Test
void commandServiceExposesOnlyRegisterSellerSettlement() {
    ServiceDescriptor descriptor =
            SellerSettlementCommandServiceGrpc.getServiceDescriptor();

    assertThat(descriptor.getMethods())
            .extracting(MethodDescriptor::getBareMethodName)
            .containsExactly("RegisterSellerSettlement");
}

@Test
void grossSalesAmountUsesStableFieldNumber() {
    Descriptors.FieldDescriptor field =
            SellerSettlementSnapshot.getDescriptor()
                    .findFieldByName("gross_sales_amount");

    assertThat(field.getNumber()).isEqualTo(7);
}
```

- [ ] **Step 2: 생성 코드가 없어 테스트 컴파일이 실패하는지 확인**

Run:

```bash
./gradlew :settlement-service:compileTestJava :user-service:compileTestJava
```

Expected: `SellerSettlementCommandServiceGrpc`와 Snapshot 생성 타입을 찾지 못해 실패.

- [ ] **Step 3: 공용 proto 작성**

protobuf package는 기존 Query 계약과 전역 심볼이 충돌하지 않도록 `prompthub.sellersettlement.command`, Java package는 `com.prompthub.user.grpc.sellersettlement.command`로 둔다.

```protobuf
syntax = "proto3";

package prompthub.sellersettlement.command;

option java_multiple_files = true;
option java_package = "com.prompthub.user.grpc.sellersettlement.command";
option java_outer_classname = "SellerSettlementCommandProto";

service SellerSettlementCommandService {
  rpc RegisterSellerSettlement(RegisterSellerSettlementRequest)
      returns (RegisterSellerSettlementResponse);
}

enum SellerSettlementLineType {
  SELLER_SETTLEMENT_LINE_TYPE_UNSPECIFIED = 0;
  SALE = 1;
  REFUND = 2;
}

message RegisterSellerSettlementRequest {
  string delivery_request_id = 1;
  SellerSettlementSnapshot settlement = 2;
}

message RegisterSellerSettlementResponse {
  SellerSettlementSnapshot stored_settlement = 1;
}

message SellerSettlementSnapshot {
  optional string delivery_request_id = 1;
  string settlement_id = 2;
  string seller_id = 3;
  string period_start = 4;
  string period_end = 5;
  int32 product_count = 6;
  string gross_sales_amount = 7;
  string refund_amount = 8;
  string fee_total_amount = 9;
  string settlement_total_amount = 10;
  string calculated_at = 11;
  repeated SellerSettlementDetailSnapshot details = 12;
}

message SellerSettlementDetailSnapshot {
  string settlement_detail_id = 1;
  string order_product_id = 2;
  SellerSettlementLineType line_type = 3;
  string line_amount = 4;
  string fee_rate = 5;
  string fee_amount = 6;
  string line_settlement_amount = 7;
  string occurred_at = 8;
}
```

- [ ] **Step 4: Settlement Service protobuf source에 `grpc/user` 추가**

기존 `grpc/order`를 유지하고 `grpc/user`를 추가한다.

```groovy
sourceSets {
    main {
        proto {
            srcDir "${rootProject.projectDir}/grpc/order"
            srcDir "${rootProject.projectDir}/grpc/user"
        }
    }
}
```

- [ ] **Step 5: 계약 테스트 실행**

Run:

```bash
./gradlew :settlement-service:test --tests '*SellerSettlementCommandGrpcContractTest' :user-service:test --tests '*SellerSettlementCommandGrpcContractTest'
```

Expected: PASS.

- [ ] **Step 6: 공용 이벤트 DTO와 독립된 proto 커밋 생성**

```bash
git add grpc/user/seller_settlement_command.proto settlement-service/build.gradle settlement-service/src/test/java/com/prompthub/settlement/infrastructure/client/user/SellerSettlementCommandGrpcContractTest.java user-service/src/test/java/com/prompthub/user/sellersettlement/infrastructure/grpc/SellerSettlementCommandGrpcContractTest.java
git commit -m "feat: 판매자 정산 gRPC 명령 계약 추가"
```

## Task 2: User Service V4와 전달 요청 식별자 모델 추가

**Files:**

- Create: `user-service/src/main/resources/db/migration/V4__add_delivery_request_id_to_seller_settlement.sql`
- Modify: `user-service/src/main/java/com/prompthub/user/sellersettlement/domain/model/SellerSettlement.java`
- Modify: `user-service/src/main/java/com/prompthub/user/sellersettlement/domain/repository/SellerSettlementRepository.java`
- Modify: `user-service/src/main/java/com/prompthub/user/sellersettlement/infrastructure/persistence/SellerSettlementJpaRepository.java`
- Modify: `user-service/src/main/java/com/prompthub/user/sellersettlement/infrastructure/persistence/SellerSettlementRepositoryAdapter.java`
- Modify: `user-service/src/test/java/com/prompthub/user/sellersettlement/domain/model/SellerSettlementTest.java`
- Create: `user-service/src/test/java/com/prompthub/user/sellersettlement/infrastructure/persistence/SellerSettlementDeliveryRequestMigrationTest.java`

- [ ] **Step 1: nullable 기존 행, 유일한 신규 ID, 도메인 연결 규칙 테스트 작성**

```java
@Test
void legacySettlementCanLinkDeliveryRequestIdOnce() {
    SellerSettlement settlement = legacySellerSettlement();
    UUID requestId = UUID.randomUUID();

    settlement.linkDeliveryRequestId(requestId);

    assertThat(settlement.getDeliveryRequestId()).isEqualTo(requestId);
}

@Test
void linkedDeliveryRequestIdCannotBeChanged() {
    SellerSettlement settlement = legacySellerSettlement();
    settlement.linkDeliveryRequestId(UUID.randomUUID());

    assertThatThrownBy(() -> settlement.linkDeliveryRequestId(UUID.randomUUID()))
            .isInstanceOf(SellerSettlementInvalidStateException.class);
}
```

마이그레이션 통합 테스트는 V3 데이터의 ID가 NULL로 유지되고, 동일한 non-null UUID 두 건 저장이 DB 제약으로 거절되는지 확인한다.

- [ ] **Step 2: 테스트 실패 확인**

Run:

```bash
./gradlew :user-service:test --tests '*SellerSettlementTest' --tests '*SellerSettlementDeliveryRequestMigrationTest'
```

Expected: 필드, 메서드, V4가 없어 실패.

- [ ] **Step 3: V4 마이그레이션 작성**

```sql
ALTER TABLE seller_settlement
    ADD COLUMN delivery_request_id UUID NULL;

CREATE UNIQUE INDEX uk_seller_settlement_delivery_request_id
    ON seller_settlement (delivery_request_id)
    WHERE delivery_request_id IS NOT NULL;
```

- [ ] **Step 4: 엔티티와 저장소 확장**

엔티티에 변경 가능한 nullable 필드를 추가하되 외부 setter는 만들지 않는다.

```java
@Column(name = "delivery_request_id", unique = true)
private UUID deliveryRequestId;

public void linkDeliveryRequestId(UUID requestId) {
    Objects.requireNonNull(requestId, "deliveryRequestId");
    if (deliveryRequestId != null && !deliveryRequestId.equals(requestId)) {
        throw new SellerSettlementInvalidStateException(
                "이미 다른 전달 요청과 연결된 Seller Settlement입니다.");
    }
    deliveryRequestId = requestId;
}
```

저장소 계약에는 상세까지 읽는 두 조회를 추가한다.

```java
Optional<SellerSettlement> findByDeliveryRequestId(UUID deliveryRequestId);

Optional<SellerSettlement> findBySettlementId(UUID settlementId);
```

JpaRepository에는 `@EntityGraph(attributePaths = "details")`를 적용해 read-back 중 지연 로딩에 의존하지 않는다.

- [ ] **Step 5: 대상 테스트 재실행**

Run:

```bash
./gradlew :user-service:test --tests '*SellerSettlementTest' --tests '*SellerSettlementDeliveryRequestMigrationTest'
```

Expected: PASS.

- [ ] **Step 6: 커밋**

```bash
git add user-service/src/main/resources/db/migration/V4__add_delivery_request_id_to_seller_settlement.sql user-service/src/main/java/com/prompthub/user/sellersettlement user-service/src/test/java/com/prompthub/user/sellersettlement
git commit -m "feat: 판매자 정산 전달 요청 식별자 저장"
```

## Task 3: User Service 멱등 등록과 커밋 후 read-back 구현

**Files:**

- Create: `user-service/src/main/java/com/prompthub/user/sellersettlement/application/dto/RegisterSellerSettlementCommand.java`
- Create: `user-service/src/main/java/com/prompthub/user/sellersettlement/application/dto/RegisteredSellerSettlementSnapshot.java`
- Create: `user-service/src/main/java/com/prompthub/user/sellersettlement/application/usecase/RegisterSellerSettlementUseCase.java`
- Create: `user-service/src/main/java/com/prompthub/user/sellersettlement/application/service/SellerSettlementRegistrationApplicationService.java`
- Create: `user-service/src/main/java/com/prompthub/user/sellersettlement/application/service/SellerSettlementReadBackApplicationService.java`
- Create: `user-service/src/main/java/com/prompthub/user/sellersettlement/application/service/SellerSettlementRegistrationFacade.java`
- Create: `user-service/src/main/java/com/prompthub/user/sellersettlement/application/service/SellerSettlementRegistrationMatcher.java`
- Modify: `user-service/src/main/java/com/prompthub/user/sellersettlement/domain/model/SellerSettlement.java`
- Modify: `user-service/src/main/java/com/prompthub/user/sellersettlement/domain/model/SellerSettlementDetail.java`
- Create: `user-service/src/test/java/com/prompthub/user/sellersettlement/application/service/SellerSettlementRegistrationApplicationServiceTest.java`
- Create: `user-service/src/test/java/com/prompthub/user/sellersettlement/application/service/SellerSettlementRegistrationFacadeTest.java`
- Create: `user-service/src/test/java/com/prompthub/user/sellersettlement/application/service/SellerSettlementRegistrationIntegrationTest.java`

- [ ] **Step 1: 등록 시나리오 테스트 작성**

다음 순서를 각각 독립 테스트로 고정한다.

1. 신규 요청은 본체와 상세를 한 번 저장한다.
2. 같은 `deliveryRequestId`는 새로 저장하지 않고 기존 Snapshot을 반환한다.
3. 같은 `settlementId`의 기존 nullable 행과 전체 값이 일치하면 ID만 연결한다.
4. 기존 행과 하나라도 다르면 덮어쓰지 않고 기존 Snapshot을 반환한다.
5. facade는 등록 트랜잭션이 끝난 뒤 read-back 서비스를 호출한다.
6. 응답 유실을 가정해 동일 요청을 두 번 호출해도 한 행만 남는다.

```java
@Test
void duplicateDeliveryRequestReturnsStoredSnapshotWithoutSecondInsert() {
    RegisterSellerSettlementCommand command = command();
    useCase.register(command);
    RegisteredSellerSettlementSnapshot second = useCase.register(command);

    assertThat(second.deliveryRequestId()).isEqualTo(command.deliveryRequestId());
    assertThat(repository.countBySettlementId(command.settlementId())).isEqualTo(1);
}

@Test
void mismatchedLegacySettlementIsNotOverwritten() {
    SellerSettlement legacy = saveLegacySettlementWithDifferentFee();

    RegisteredSellerSettlementSnapshot actual = useCase.register(command());

    assertThat(actual.deliveryRequestId()).isNull();
    assertThat(actual.feeTotalAmount()).isEqualByComparingTo(legacy.getFeeTotalAmount());
}
```

- [ ] **Step 2: 구현 전 실패 확인**

Run:

```bash
./gradlew :user-service:test --tests '*SellerSettlementRegistration*'
```

Expected: 등록 타입과 서비스가 없어 실패.

- [ ] **Step 3: 애플리케이션 계약 정의**

```java
public interface RegisterSellerSettlementUseCase {
    RegisteredSellerSettlementSnapshot register(
            RegisterSellerSettlementCommand command);
}
```

`RegisterSellerSettlementCommand`는 요청 ID, 본체 필드, 상세 리스트를 모두 불변 값으로 보관하고 생성 시 UUID, 기간, 금액, 상세 중복 ID를 검증한다.

- [ ] **Step 4: 쓰기와 read-back 트랜잭션 분리**

`SellerSettlementRegistrationApplicationService`는 `@Transactional`로 등록 또는 legacy ID 연결만 수행하고 settlement ID를 반환한다. `SellerSettlementReadBackApplicationService`는 별도 Bean의 `@Transactional(readOnly = true)` 메서드로 상세 포함 엔티티를 재조회해 Snapshot을 만든다.

```java
@Service
@RequiredArgsConstructor
public class SellerSettlementRegistrationFacade
        implements RegisterSellerSettlementUseCase {

    private final SellerSettlementRegistrationApplicationService writer;
    private final SellerSettlementReadBackApplicationService reader;

    @Override
    public RegisteredSellerSettlementSnapshot register(
            RegisterSellerSettlementCommand command) {
        UUID settlementId = writer.register(command);
        return reader.readBySettlementId(settlementId);
    }
}
```

동시 요청은 V4의 유니크 제약이 최종 방어선이다. 저장 경합에서 무결성 예외가 발생하면 facade 바깥의 별도 재조회 경로로 동일 `deliveryRequestId` 또는 `settlementId` 행을 반환하고, 기존 데이터를 수정하지 않는다.

- [ ] **Step 5: 직접 비교 규칙 구현**

Legacy 연결 판단은 다음 규칙을 사용한다.

- `BigDecimal.compareTo`
- `Instant` 마이크로초 정규화
- 상세 순서 무시
- `settlementDetailId` 매칭
- 상세 ID 중복, 누락, 추가 항목은 불일치

- [ ] **Step 6: 테스트 통과 확인**

Run:

```bash
./gradlew :user-service:test --tests '*SellerSettlementRegistration*'
```

Expected: PASS.

- [ ] **Step 7: 커밋**

```bash
git add user-service/src/main/java/com/prompthub/user/sellersettlement user-service/src/test/java/com/prompthub/user/sellersettlement
git commit -m "feat: 판매자 정산 멱등 등록 및 저장 결과 조회"
```

## Task 4: User Service Command gRPC 서버와 전용 인증 추가

**Files:**

- Create: `user-service/src/main/java/com/prompthub/user/sellersettlement/infrastructure/grpc/SellerSettlementCommandGrpcServer.java`
- Create: `user-service/src/main/java/com/prompthub/user/sellersettlement/infrastructure/grpc/SellerSettlementCommandGrpcRequestMapper.java`
- Create: `user-service/src/main/java/com/prompthub/user/sellersettlement/infrastructure/grpc/SellerSettlementCommandGrpcResponseMapper.java`
- Create: `user-service/src/main/java/com/prompthub/user/sellersettlement/infrastructure/grpc/SellerSettlementCommandGrpcAuthInterceptor.java`
- Create: `user-service/src/main/java/com/prompthub/user/sellersettlement/infrastructure/grpc/SellerSettlementCommandGrpcSecurityProperties.java`
- Modify: `user-service/src/main/java/com/prompthub/user/sellersettlement/infrastructure/grpc/SellerSettlementGrpcMetadata.java`
- Modify: `user-service/src/main/java/com/prompthub/user/sellersettlement/infrastructure/grpc/SellerSettlementGrpcServerConfig.java`
- Create: `user-service/src/test/java/com/prompthub/user/sellersettlement/infrastructure/grpc/SellerSettlementCommandGrpcServerTest.java`
- Create: `user-service/src/test/java/com/prompthub/user/sellersettlement/infrastructure/grpc/SellerSettlementCommandGrpcAuthInterceptorTest.java`

- [ ] **Step 1: 정상 호출과 인증 실패 테스트 작성**

테스트는 in-process gRPC server로 다음을 검증한다.

- 전용 토큰만 있으면 `x-user-id` 없이 성공
- 토큰 누락/불일치는 `UNAUTHENTICATED`
- 잘못된 UUID, 날짜, 금액은 `INVALID_ARGUMENT`
- use case 결과 전체가 proto Snapshot에 매핑됨

```java
@Test
void commandAcceptsInternalTokenWithoutUserId() {
    RegisterSellerSettlementResponse response =
            authorizedStub.registerSellerSettlement(validRequest());

    assertThat(response.getStoredSettlement().getSettlementId())
            .isEqualTo(SETTLEMENT_ID.toString());
}
```

- [ ] **Step 2: 실패 확인**

Run:

```bash
./gradlew :user-service:test --tests '*SellerSettlementCommandGrpc*'
```

Expected: 서버와 인터셉터가 없어 실패.

- [ ] **Step 3: 요청/응답 매퍼와 서버 구현**

```java
@Override
public void registerSellerSettlement(
        RegisterSellerSettlementRequest request,
        StreamObserver<RegisterSellerSettlementResponse> observer) {
    try {
        RegisterSellerSettlementCommand command = requestMapper.toCommand(request);
        RegisteredSellerSettlementSnapshot stored = useCase.register(command);
        observer.onNext(responseMapper.toResponse(stored));
        observer.onCompleted();
    } catch (IllegalArgumentException exception) {
        observer.onError(Status.INVALID_ARGUMENT
                .withDescription(exception.getMessage())
                .asRuntimeException());
    }
}
```

- [ ] **Step 4: Query 인증을 보존한 채 Command 인증 분리**

기존 Query interceptor는 AI 토큰과 `x-user-id` 규칙을 유지한다. 새 Command interceptor는 `x-internal-service-token`만 검증하며 prefix는 `user.grpc.seller-settlement-command.internal-token`을 쓴다. 서버 설정에서 각 서비스에 해당 interceptor를 개별 적용한다.

- [ ] **Step 5: 테스트 재실행**

Run:

```bash
./gradlew :user-service:test --tests '*SellerSettlementCommandGrpc*' --tests '*SellerSettlementQueryGrpc*'
```

Expected: PASS. 기존 Query 인증 테스트도 그대로 통과.

- [ ] **Step 6: 커밋**

```bash
git add user-service/src/main/java/com/prompthub/user/sellersettlement/infrastructure/grpc user-service/src/test/java/com/prompthub/user/sellersettlement/infrastructure/grpc
git commit -m "feat: 판매자 정산 등록 gRPC 서버 및 전용 인증 추가"
```

## Task 5: Settlement Delivery 도메인, 저장소와 V8 추가

**Files:**

- Create: `settlement-service/src/main/resources/db/migration/V8__replace_outbox_with_settlement_delivery.sql`
- Create: `settlement-service/src/main/java/com/prompthub/settlement/domain/model/SettlementDelivery.java`
- Create: `settlement-service/src/main/java/com/prompthub/settlement/domain/model/enums/SettlementDeliveryStatus.java`
- Create: `settlement-service/src/main/java/com/prompthub/settlement/domain/repository/SettlementDeliveryRepository.java`
- Create: `settlement-service/src/main/java/com/prompthub/settlement/infrastructure/persistence/delivery/SettlementDeliveryJpaRepository.java`
- Create: `settlement-service/src/main/java/com/prompthub/settlement/infrastructure/persistence/delivery/SettlementDeliveryRepositoryAdapter.java`
- Create: `settlement-service/src/main/java/com/prompthub/settlement/infrastructure/persistence/delivery/SettlementDeliveryStatusCountProjection.java`
- Create: `settlement-service/src/test/java/com/prompthub/settlement/domain/model/SettlementDeliveryTest.java`
- Create: `settlement-service/src/test/java/com/prompthub/settlement/infrastructure/persistence/delivery/SettlementDeliveryRepositoryAdapterTest.java`
- Create: `settlement-service/src/test/java/com/prompthub/settlement/infrastructure/persistence/delivery/SettlementDeliveryMigrationTest.java`

- [ ] **Step 1: 상태 전이와 마이그레이션 테스트 작성**

```java
@Test
void calculatedDeliveryRecordsAttemptsAndBecomesReconciled() {
    SettlementDelivery delivery = SettlementDelivery.calculated(
            SETTLEMENT_ID, BATCH_ID, REQUEST_ID, NOW);

    delivery.recordAttempt(NOW.plusSeconds(1));
    delivery.reconcile(NOW.plusSeconds(2));

    assertThat(delivery.getAttemptCount()).isEqualTo(1);
    assertThat(delivery.getStatus()).isEqualTo(RECONCILED);
    assertThat(delivery.getStatusReason()).isNull();
}

@Test
void terminalDeliveryCannotBeAutomaticallyAttemptedAgain() {
    SettlementDelivery delivery = failedDelivery();

    assertThatThrownBy(() -> delivery.recordAttempt(NOW))
            .isInstanceOf(SettlementInvalidStateException.class);
}
```

마이그레이션 테스트는 V8 이후 `settlement_delivery`가 존재하고 settlement/request ID 유일 제약이 동작하는지 확인한다. 이 Task에서는 기존 Outbox 매핑과 전체 ApplicationContext가 계속 유효하도록 테이블 삭제를 아직 넣지 않는다. Outbox 코드와 테이블 삭제는 Task 8의 독립 커밋에서 함께 처리한다.

- [ ] **Step 2: 실패 확인**

Run:

```bash
./gradlew :settlement-service:test --tests '*SettlementDeliveryTest' --tests '*SettlementDeliveryRepositoryAdapterTest' --tests '*SettlementDeliveryMigrationTest'
```

Expected: Delivery 타입과 V8이 없어 실패.

- [ ] **Step 3: V8 작성**

```sql
CREATE TABLE settlement_delivery (
    settlement_delivery_id UUID PRIMARY KEY,
    delivery_request_id UUID NOT NULL UNIQUE,
    settlement_id UUID NOT NULL UNIQUE,
    settlement_batch_id UUID NOT NULL,
    status VARCHAR(30) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    status_reason TEXT NULL,
    first_attempt_at TIMESTAMP(6) WITHOUT TIME ZONE NULL,
    last_attempt_at TIMESTAMP(6) WITHOUT TIME ZONE NULL,
    reconciled_at TIMESTAMP(6) WITHOUT TIME ZONE NULL,
    created_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT fk_settlement_delivery_settlement
        FOREIGN KEY (settlement_id) REFERENCES settlement(settlement_id)
);

CREATE INDEX idx_settlement_delivery_batch_status
    ON settlement_delivery (settlement_batch_id, status);
```

- [ ] **Step 4: 도메인과 저장소 구현**

상태는 `CALCULATED`, `RECONCILED`, `DELIVERY_FAILED`, `MISMATCH`만 둔다. 저장소는 아래 기능을 제공한다.

```java
SettlementDelivery save(SettlementDelivery delivery);

Optional<SettlementDelivery> findById(UUID settlementDeliveryId);

List<SettlementDelivery> findCalculatedByBatchId(UUID settlementBatchId);

Map<SettlementDeliveryStatus, Long> countByStatus(UUID settlementBatchId);
```

`recordAttempt`, `reconcile`, `fail`, `mismatch`에서 누적 횟수와 시각, 사유 불변식을 도메인이 책임진다.

- [ ] **Step 5: 테스트 통과 확인**

Run:

```bash
./gradlew :settlement-service:test --tests '*SettlementDeliveryTest' --tests '*SettlementDeliveryRepositoryAdapterTest' --tests '*SettlementDeliveryMigrationTest'
```

Expected: PASS.

- [ ] **Step 6: 커밋**

```bash
git add settlement-service/src/main/resources/db/migration/V8__replace_outbox_with_settlement_delivery.sql settlement-service/src/main/java/com/prompthub/settlement/domain/model/SettlementDelivery.java settlement-service/src/main/java/com/prompthub/settlement/domain/model/enums/SettlementDeliveryStatus.java settlement-service/src/main/java/com/prompthub/settlement/domain/repository/SettlementDeliveryRepository.java settlement-service/src/main/java/com/prompthub/settlement/infrastructure/persistence/delivery settlement-service/src/test/java/com/prompthub/settlement/domain/model/SettlementDeliveryTest.java settlement-service/src/test/java/com/prompthub/settlement/infrastructure/persistence/delivery
git commit -m "feat: 정산 전달 원장 및 상태 관리 추가"
```

## Task 6: Settlement gRPC 클라이언트와 Snapshot 직접 비교 구현

**Files:**

- Create: `settlement-service/src/main/java/com/prompthub/settlement/application/dto/SellerSettlementRegistrationCommand.java`
- Create: `settlement-service/src/main/java/com/prompthub/settlement/application/dto/SellerSettlementStoredSnapshot.java`
- Create: `settlement-service/src/main/java/com/prompthub/settlement/application/dto/SettlementDeliveryComparison.java`
- Create: `settlement-service/src/main/java/com/prompthub/settlement/application/port/SellerSettlementRegistrationPort.java`
- Create: `settlement-service/src/main/java/com/prompthub/settlement/application/service/SettlementDeliveryReconciler.java`
- Create: `settlement-service/src/main/java/com/prompthub/settlement/application/exception/SellerSettlementDeliveryException.java`
- Create: `settlement-service/src/main/java/com/prompthub/settlement/infrastructure/client/user/SellerSettlementCommandGrpcClient.java`
- Create: `settlement-service/src/main/java/com/prompthub/settlement/infrastructure/client/user/SellerSettlementCommandGrpcMapper.java`
- Create: `settlement-service/src/main/java/com/prompthub/settlement/infrastructure/client/user/config/SellerSettlementCommandGrpcClientConfig.java`
- Create: `settlement-service/src/main/java/com/prompthub/settlement/infrastructure/client/user/config/SellerSettlementCommandGrpcProperties.java`
- Create: `settlement-service/src/test/java/com/prompthub/settlement/application/service/SettlementDeliveryReconcilerTest.java`
- Create: `settlement-service/src/test/java/com/prompthub/settlement/infrastructure/client/user/SellerSettlementCommandGrpcMapperTest.java`
- Create: `settlement-service/src/test/java/com/prompthub/settlement/infrastructure/client/user/SellerSettlementCommandGrpcClientTest.java`

- [ ] **Step 1: 직접 비교 테스트 작성**

다음 필드를 고정 순서로 모두 비교한다.

- deliveryRequestId, settlementId, sellerId
- periodStart, periodEnd, productCount
- grossSalesAmount, refundAmount, feeTotalAmount, settlementTotalAmount
- calculatedAt
- detail ID 중복·누락·추가
- 각 detail의 orderProductId, lineType, lineAmount, feeRate, feeAmount, lineSettlementAmount, occurredAt

```java
@Test
void ignoresMoneyScaleAndDetailOrder() {
    SellerSettlementStoredSnapshot actual =
            snapshotWithReorderedDetailsAndScaledMoney();

    SettlementDeliveryComparison result =
            reconciler.compare(command(), actual);

    assertThat(result.matched()).isTrue();
    assertThat(result.reason()).isNull();
}

@Test
void reportsFirstMismatchAndTotalCount() {
    SettlementDeliveryComparison result =
            reconciler.compare(command(), snapshotWithThreeMismatches());

    assertThat(result.matched()).isFalse();
    assertThat(result.reason())
            .contains("feeTotalAmount 불일치")
            .contains("mismatchCount=3");
}
```

- [ ] **Step 2: gRPC 매핑과 오류 분류 테스트 작성**

클라이언트 테스트는 10초 deadline, 전용 metadata, 상태 분류를 확인한다.

```java
@ParameterizedTest
@ValueSource(strings = {"UNAVAILABLE", "DEADLINE_EXCEEDED"})
void mapsTransientGrpcStatusesAsRetryable(String code) {
    SellerSettlementDeliveryException exception =
            clientException(Status.Code.valueOf(code));

    assertThat(exception.isRetryable()).isTrue();
}
```

`RESOURCE_EXHAUSTED`, 인증, 검증 상태는 `retryable=false`를 확인한다.

- [ ] **Step 3: 실패 확인**

Run:

```bash
./gradlew :settlement-service:test --tests '*SettlementDeliveryReconcilerTest' --tests '*SellerSettlementCommandGrpcMapperTest' --tests '*SellerSettlementCommandGrpcClientTest'
```

Expected: DTO, port, client, reconciler가 없어 실패.

- [ ] **Step 4: DTO, port, mapper와 비교기 구현**

금액은 `compareTo`, 시각은 `ChronoUnit.MICROS`로 truncate, 상세는 ID map으로 비교한다. 중복 ID를 map으로 덮어쓰지 말고 별도 mismatch로 집계한다.

```java
public interface SellerSettlementRegistrationPort {
    SellerSettlementStoredSnapshot register(
            SellerSettlementRegistrationCommand command);
}

public record SettlementDeliveryComparison(
        boolean matched,
        String reason,
        int mismatchCount) {
}
```

- [ ] **Step 5: 10초 deadline과 토큰을 호출마다 적용**

```java
SellerSettlementCommandServiceBlockingStub authenticated =
        MetadataUtils.attachHeaders(stub, metadata)
                .withDeadlineAfter(properties.deadline().toMillis(),
                        TimeUnit.MILLISECONDS);
```

기본 메시지 제한은 변경하지 않는다. Status 예외는 이름, description, retryable만 애플리케이션 예외로 옮기고 토큰이나 상세를 메시지에 포함하지 않는다.

- [ ] **Step 6: 테스트 통과 확인**

Run:

```bash
./gradlew :settlement-service:test --tests '*SettlementDeliveryReconcilerTest' --tests '*SellerSettlementCommandGrpcMapperTest' --tests '*SellerSettlementCommandGrpcClientTest'
```

Expected: PASS.

- [ ] **Step 7: 커밋**

```bash
git add settlement-service/src/main/java/com/prompthub/settlement/application settlement-service/src/main/java/com/prompthub/settlement/infrastructure/client/user settlement-service/src/test/java/com/prompthub/settlement/application/service/SettlementDeliveryReconcilerTest.java settlement-service/src/test/java/com/prompthub/settlement/infrastructure/client/user
git commit -m "feat: 판매자 정산 gRPC 호출 및 직접 대사 추가"
```

## Task 7: 정산 단위 재시도, 실패 격리와 집계 구현

**Files:**

- Create: `settlement-service/src/main/java/com/prompthub/settlement/application/dto/SettlementDeliverySummary.java`
- Create: `settlement-service/src/main/java/com/prompthub/settlement/application/service/SettlementDeliveryApplicationService.java`
- Create: `settlement-service/src/main/java/com/prompthub/settlement/application/service/SettlementDeliveryTransactionService.java`
- Create: `settlement-service/src/main/java/com/prompthub/settlement/application/service/DeliveryRetrySleeper.java`
- Create: `settlement-service/src/main/java/com/prompthub/settlement/infrastructure/time/ThreadDeliveryRetrySleeper.java`
- Create: `settlement-service/src/test/java/com/prompthub/settlement/application/service/SettlementDeliveryApplicationServiceTest.java`
- Create: `settlement-service/src/test/java/com/prompthub/settlement/application/service/SettlementDeliveryTransactionServiceTest.java`

- [ ] **Step 1: 재시도와 실패 격리 테스트 작성**

```java
@Test
void retriesUnavailableTwiceThenReconciles() {
    when(port.register(any()))
            .thenThrow(retryable("UNAVAILABLE"))
            .thenThrow(retryable("UNAVAILABLE"))
            .thenReturn(matchingSnapshot());

    service.deliverBatch(BATCH_ID);

    verify(port, times(3)).register(any());
    verify(sleeper).sleep(Duration.ofSeconds(1));
    verify(sleeper).sleep(Duration.ofSeconds(3));
    assertThat(savedDelivery().getStatus()).isEqualTo(RECONCILED);
}

@Test
void finalFailureDoesNotStopNextDelivery() {
    arrangeFirstDeliveryToFailAndSecondToMatch();

    SettlementDeliverySummary summary = service.deliverBatch(BATCH_ID);

    assertThat(summary.deliveryFailed()).isEqualTo(1);
    assertThat(summary.reconciled()).isEqualTo(1);
}
```

추가 테스트:

- non-retryable 상태는 1회만 시도
- 3회 소진 후 `DELIVERY_FAILED`
- 응답 불일치는 재호출 없이 `MISMATCH`
- 재실행은 `CALCULATED`만 선택
- 시도마다 같은 `deliveryRequestId`
- 시도 카운트가 누적됨

- [ ] **Step 2: 실패 확인**

Run:

```bash
./gradlew :settlement-service:test --tests '*SettlementDeliveryApplicationServiceTest' --tests '*SettlementDeliveryTransactionServiceTest'
```

Expected: orchestration 서비스가 없어 실패.

- [ ] **Step 3: 원격 호출 밖으로 로컬 트랜잭션 분리**

`SettlementDeliveryTransactionService`의 시도 기록과 최종 상태 저장은 각각 `REQUIRES_NEW`를 사용한다. 원격 호출 중에는 트랜잭션을 유지하지 않는다.

```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public SellerSettlementRegistrationCommand beginAttempt(
        UUID settlementDeliveryId, Instant attemptedAt) {
    SettlementDelivery delivery = findCalculated(settlementDeliveryId);
    delivery.recordAttempt(attemptedAt);
    return commandFactory.create(delivery);
}
```

- [ ] **Step 4: 순차 처리와 재시도 구현**

`deliverBatch`는 repository가 반환한 `CALCULATED` ID를 순서대로 처리한다. `UNAVAILABLE`, `DEADLINE_EXCEEDED`만 1초, 3초 간격으로 재시도한다. 한 정산의 최종 상태 저장 후 다음 ID로 진행한다. 로컬 DB 저장 실패는 삼키지 않아 Job 실패로 전파한다.

- [ ] **Step 5: 구조화 로그와 배치 집계 구현**

호출 로그에는 `settlementId`, `deliveryRequestId`, attempt, gRPC status, elapsedMs만 넣는다. 최종 집계는 total, calculated, reconciled, deliveryFailed, mismatch를 반환하고 로그에 기록한다.

- [ ] **Step 6: 테스트 통과 확인**

Run:

```bash
./gradlew :settlement-service:test --tests '*SettlementDeliveryApplicationServiceTest' --tests '*SettlementDeliveryTransactionServiceTest'
```

Expected: PASS.

- [ ] **Step 7: 커밋**

```bash
git add settlement-service/src/main/java/com/prompthub/settlement/application settlement-service/src/main/java/com/prompthub/settlement/infrastructure/time settlement-service/src/test/java/com/prompthub/settlement/application/service
git commit -m "feat: 정산 전달 재시도 및 실패 격리 추가"
```

## Task 8: 배치 흐름에 Delivery를 연결하고 Outbox 제거

**Files:**

- Modify: `settlement-service/src/main/java/com/prompthub/settlement/application/service/SettlementCalculationApplicationService.java`
- Modify: `settlement-service/src/main/java/com/prompthub/settlement/infrastructure/batch/config/SettlementJobConfig.java`
- Modify: `settlement-service/src/main/java/com/prompthub/settlement/infrastructure/batch/config/SettlementStepConfig.java`
- Modify: `settlement-service/src/main/resources/db/migration/V8__replace_outbox_with_settlement_delivery.sql`
- Create: `settlement-service/src/main/java/com/prompthub/settlement/infrastructure/batch/tasklet/DeliverSellerSettlementsTasklet.java`
- Delete: `settlement-service/src/main/java/com/prompthub/settlement/application/port/OutboxEventAppender.java`
- Delete: `settlement-service/src/main/java/com/prompthub/settlement/application/service/OutboxEventApplicationService.java`
- Delete: `settlement-service/src/main/java/com/prompthub/settlement/application/usecase/OutboxEventUseCase.java`
- Delete: `settlement-service/src/main/java/com/prompthub/settlement/domain/exception/OutboxEventInvalidStateException.java`
- Delete: `settlement-service/src/main/java/com/prompthub/settlement/domain/model/SettlementOutboxEvent.java`
- Delete: `settlement-service/src/main/java/com/prompthub/settlement/domain/model/enums/OutboxEventStatus.java`
- Delete: `settlement-service/src/main/java/com/prompthub/settlement/domain/repository/OutboxEventRepository.java`
- Delete: `settlement-service/src/main/java/com/prompthub/settlement/infrastructure/batch/config/OutboxRedriveJobConfig.java`
- Delete: `settlement-service/src/main/java/com/prompthub/settlement/infrastructure/batch/tasklet/FlushCurrentBatchOutboxTasklet.java`
- Delete: `settlement-service/src/main/java/com/prompthub/settlement/infrastructure/batch/tasklet/RedriveOutboxTasklet.java`
- Delete: `settlement-service/src/main/java/com/prompthub/settlement/infrastructure/batch/tasklet/RetryPendingOutboxTasklet.java`
- Delete: `settlement-service/src/main/java/com/prompthub/settlement/infrastructure/persistence/outbox/`
- Delete: `settlement-service/src/main/java/com/prompthub/settlement/application/event/SettlementCreatedEvent.java`
- Delete: `settlement-service/src/main/java/com/prompthub/settlement/application/event/SettlementDetailEvent.java`
- Modify/Delete: Outbox 전용 테스트와 정산 이벤트 테스트
- Create: `settlement-service/src/test/java/com/prompthub/settlement/infrastructure/batch/SettlementDeliveryJobIntegrationTest.java`

- [ ] **Step 1: 원자적 생성과 Job 순서 테스트 작성**

```java
@Test
void calculationCreatesSettlementAndDeliveryInSameTransaction() {
    calculationService.calculate(command());

    Settlement saved = settlementRepository.findById(SETTLEMENT_ID).orElseThrow();
    SettlementDelivery delivery =
            deliveryRepository.findBySettlementId(saved.getSettlementId())
                    .orElseThrow();

    assertThat(delivery.getStatus()).isEqualTo(CALCULATED);
    assertThat(delivery.getSettlementBatchId()).isEqualTo(saved.getSettlementBatchId());
}
```

통합 테스트는 batch 상태가 `COMPLETED`된 다음 Delivery Step이 실행되고, 한 Delivery 실패에도 다음 Delivery가 처리되며, Job 재시작이 `CALCULATED`만 처리하는지 확인한다.

- [ ] **Step 2: 기존 구현에서 테스트 실패 확인**

Run:

```bash
./gradlew :settlement-service:test --tests '*SettlementCalculationApplicationServiceTest' --tests '*SettlementDeliveryJobIntegrationTest'
```

Expected: Delivery가 생성되지 않고 Job에 Delivery Step이 없어 실패.

- [ ] **Step 3: Settlement와 Delivery를 같은 계산 트랜잭션에 저장**

Outbox append 호출을 제거하고 Settlement 저장 직후 안정적인 `deliveryRequestId`를 한 번 생성해 Delivery를 저장한다.

```java
Settlement saved = settlementRepository.save(settlement);
settlementDeliveryRepository.save(SettlementDelivery.calculated(
        saved.getSettlementId(),
        saved.getSettlementBatchId(),
        UUID.randomUUID(),
        clock.instant()));
```

- [ ] **Step 4: Job 순서를 Delivery 기준으로 변경**

최종 순서는 다음과 같이 고정한다.

```text
create batch
→ load/source reconciliation (#639 통합 지점)
→ settlement calculation
→ calculation reconciliation (#642 통합 지점)
→ complete batch
→ deliver seller settlements
```

현재 브랜치에 #639/#642 코드가 없으면 주석이나 가짜 Step을 만들지 않고, 기존 계산 완료 Step 뒤에 Delivery Step을 둔다. Outbox retry/flush/redrive Step과 Job bean은 제거한다.

- [ ] **Step 5: Outbox 코드·테스트·테이블 삭제**

Settlement Service의 Outbox 도메인, 포트, 저장 어댑터, producer, retry/redrive tasklet, 정산 이벤트 DTO를 모두 삭제한다. 같은 커밋에서 V8 마지막에 `DROP TABLE settlement_outbox_event;`를 추가하고 마이그레이션 테스트에 테이블 부재 검증을 추가한다. 다른 서비스가 사용하는 공용 Kafka 코드와 `EventMessage`는 건드리지 않는다.

- [ ] **Step 6: 대상 테스트와 컴파일 확인**

Run:

```bash
./gradlew :settlement-service:test --tests '*SettlementCalculationApplicationServiceTest' --tests '*SettlementDeliveryJobIntegrationTest' --tests '*SettlementJobConfigTest'
./gradlew :settlement-service:compileJava
```

Expected: PASS. Outbox 타입 참조가 남지 않음.

- [ ] **Step 7: Outbox 교체를 독립 커밋으로 생성**

```bash
git add -u settlement-service/src/main settlement-service/src/test
git add settlement-service/src/main/resources/db/migration/V8__replace_outbox_with_settlement_delivery.sql
git add settlement-service/src/main/java/com/prompthub/settlement/infrastructure/batch/tasklet/DeliverSellerSettlementsTasklet.java settlement-service/src/test/java/com/prompthub/settlement/infrastructure/batch/SettlementDeliveryJobIntegrationTest.java
git commit -m "refactor: Kafka Outbox 전달을 gRPC Delivery로 교체"
```

## Task 9: User Service 정산 Kafka 소비 경로 제거

**Files:**

- Delete: `user-service/src/main/java/com/prompthub/user/sellersettlement/infrastructure/messaging/`
- Delete: `user-service/src/main/java/com/prompthub/user/sellersettlement/application/event/`
- Delete: `user-service/src/main/java/com/prompthub/user/sellersettlement/application/usecase/SeedSellerSettlementUseCase.java`
- Modify: `user-service/src/main/java/com/prompthub/user/sellersettlement/application/service/SellerSettlementApplicationService.java`
- Delete/Modify: 정산 Consumer, DLT, Seed 전용 테스트
- Modify: `user-service/build.gradle`
- Modify: `user-service/src/main/resources/application-local.yml`

- [ ] **Step 1: gRPC 등록 테스트가 기존 Kafka 타입 없이 컴파일되도록 사용처 조사**

Run:

```bash
rg -n "SettlementEventConsumer|SeedSellerSettlementUseCase|SettlementCreatedEvent|KafkaListener|DeadLetter|DLT" user-service/src/main user-service/src/test
rg -n "spring-kafka|KafkaTemplate|ConsumerFactory" user-service
```

Expected: 정산 Consumer/Seed/DLT 및 그 테스트만 검색됨. 사전 조사에서 다른 User 도메인의 Kafka 사용이 없음을 확인했으므로 User Service Kafka 의존성과 설정 전체를 제거 대상으로 확정한다.

- [ ] **Step 2: gRPC 등록 회귀 테스트를 먼저 실행**

Run:

```bash
./gradlew :user-service:test --tests '*SellerSettlementRegistration*' --tests '*SellerSettlementCommandGrpc*'
```

Expected: PASS.

- [ ] **Step 3: 정산 Kafka 소비·DLT·Seed 경로 삭제**

`SellerSettlementApplicationService`는 조회/승인/보류/지급 같은 기존 운영 기능만 유지한다. gRPC 등록은 Task 3의 전용 use case가 책임진다. 정산 Kafka DTO와 Consumer 전용 검증 코드는 삭제한다.

- [ ] **Step 4: Kafka 의존성과 로컬 listener 설정 정리**

`spring-boot-starter-kafka`, Kafka test dependency, settlement listener와 DLT Slack 설정을 제거한다. Query gRPC와 AI 토큰 설정은 유지한다.

- [ ] **Step 5: 전체 User Service 테스트 실행**

Run:

```bash
./gradlew :user-service:test
```

Expected: PASS.

- [ ] **Step 6: 커밋**

```bash
git add -u user-service/src/main user-service/src/test user-service/build.gradle
git add user-service/build.gradle user-service/src/main/resources/application-local.yml
git commit -m "refactor: 정산 Kafka 소비 경로를 gRPC 등록으로 교체"
```

## Task 10: Config Server와 Kubernetes 운영 계약 변경

**Files:**

- Modify: `config/src/main/resources/configs/settlement-service.yml`
- Modify: `config/src/main/resources/configs/user-service.yml`
- Create: `config/src/test/java/com/prompthub/config/SettlementDeliveryConfigurationContractTest.java`
- Modify: `config/src/test/java/com/prompthub/config/AiSettlementConfigurationContractTest.java`
- Modify: `k8s/base/services/settlement/cronjob.yaml`
- Modify: `k8s/base/services/user/deployment.yaml`

- [ ] **Step 1: 운영 계약 테스트 작성**

Config 계약 테스트는 다음 키를 확인한다.

- Settlement: `spring.grpc.client.channel.user-service.target`
- Settlement: `settlement.delivery.grpc.internal-token`
- Settlement: `settlement.delivery.grpc.deadline=10s`
- User: `user.grpc.seller-settlement-command.internal-token`
- 기존 User Query용 AI 토큰 유지
- 제거된 settlement Kafka listener 설정 부재

- [ ] **Step 2: 계약 테스트 실패 확인**

Run:

```bash
./gradlew :config:test --tests '*SettlementDeliveryConfigurationContractTest' --tests '*AiSettlementConfigurationContractTest'
```

Expected: 신규 target/token/deadline 키가 없어 실패.

- [ ] **Step 3: Config Server 설정 변경**

토큰은 `${SETTLEMENT_USER_GRPC_TOKEN}`만 참조하고 기본값을 두지 않는다. deadline은 10초로 명시한다.

```yaml
spring:
  grpc:
    client:
      channel:
        user-service:
          target: ${USER_GRPC_TARGET:static://user-service:9091}

settlement:
  delivery:
    grpc:
      internal-token: ${SETTLEMENT_USER_GRPC_TOKEN}
      deadline: 10s

user:
  grpc:
    seller-settlement-command:
      internal-token: ${SETTLEMENT_USER_GRPC_TOKEN}
```

- [ ] **Step 4: Settlement CronJob 설정 변경**

- User Service gRPC 준비 상태를 기다린다.
- `USER_GRPC_TARGET=static://user-service:9091`을 주입한다.
- `SETTLEMENT_USER_GRPC_TOKEN`은 Kubernetes Secret key reference로 주입한다.
- Settlement Service의 Kafka init wait와 환경 변수를 제거한다.
- 토큰을 args나 로그에 직접 넣지 않는다.

- [ ] **Step 5: User Deployment 설정 변경**

동일 Secret key를 Command 서버 토큰으로 주입한다. User Service의 listener env, Kafka wait/env를 제거한다. 기존 `AI_USER_GRPC_TOKEN`은 유지한다.

- [ ] **Step 6: 설정과 manifest 검증**

Run:

```bash
./gradlew :config:test --tests '*SettlementDeliveryConfigurationContractTest' --tests '*AiSettlementConfigurationContractTest'
kubectl kustomize k8s/base
```

Expected: 테스트 PASS, Kustomize 렌더링 성공, 렌더링 결과에 `SETTLEMENT_USER_GRPC_TOKEN` secretKeyRef가 두 workload에 존재.

- [ ] **Step 7: 커밋**

```bash
git add config/src/main/resources/configs/settlement-service.yml config/src/main/resources/configs/user-service.yml config/src/test/java/com/prompthub/config/SettlementDeliveryConfigurationContractTest.java config/src/test/java/com/prompthub/config/AiSettlementConfigurationContractTest.java k8s/base/services/settlement/cronjob.yaml k8s/base/services/user/deployment.yaml
git commit -m "chore: 정산 gRPC 전달 운영 설정 추가"
```

## Task 11: 통합·회귀 테스트와 잔여 참조 정리

**Files:**

- Modify/Create: `settlement-service/src/test/java/com/prompthub/settlement/integration/SettlementDeliveryIntegrationTest.java`
- Modify/Create: `user-service/src/test/java/com/prompthub/user/sellersettlement/integration/SellerSettlementCommandIntegrationTest.java`
- Modify: 앞선 Task에서 드러난 회귀 테스트와 설정 테스트

- [ ] **Step 1: end-to-end 모듈 통합 테스트 작성**

Settlement 통합 테스트는 실제 생성 proto와 in-process gRPC 서버를 사용해 다음을 검증한다.

- 정상 등록 및 `RECONCILED`
- 첫 응답 유실 뒤 같은 요청 재호출
- 금액 불일치와 detail 불일치
- 4MiB 초과 `RESOURCE_EXHAUSTED` 즉시 실패
- 실패한 첫 Settlement 뒤 두 번째 Settlement 성공

User 통합 테스트는 실제 JPA와 gRPC 서버를 사용해 신규 등록, 중복 호출, legacy 연결, mismatch 비덮어쓰기를 확인한다.

- [ ] **Step 2: 통합 테스트 실패 확인**

Run:

```bash
./gradlew :settlement-service:test --tests '*SettlementDeliveryIntegrationTest' :user-service:test --tests '*SellerSettlementCommandIntegrationTest'
```

Expected: 테스트 fixture 또는 경계 처리 누락 때문에 실패하는 케이스를 확인.

- [ ] **Step 3: 최소 구현으로 통합 실패 수정**

오류 상태 매핑, 트랜잭션 read-back, 시간 정규화, 상세 중복 감지 중 테스트가 지적한 경계만 수정한다. 자동 재처리, hash, mismatch 테이블, admin API는 추가하지 않는다.

- [ ] **Step 4: 잔여 Kafka/Outbox 참조 검사**

Run:

```bash
rg -n "SettlementOutboxEvent|OutboxEventAppender|OutboxEventUseCase|FlushCurrentBatchOutbox|RetryPendingOutbox|RedriveOutbox" settlement-service/src
rg -n "SettlementEventConsumer|SeedSellerSettlementUseCase|SETTLEMENT_CREATED|settlement.*dlt" user-service/src
```

Expected: 검색 결과 없음.

- [ ] **Step 5: 모듈 전체 테스트와 정적 검증**

Run:

```bash
./gradlew :settlement-service:test :user-service:test :config:test
./gradlew :settlement-service:check :user-service:check :config:check
```

Expected: 모두 PASS.

- [ ] **Step 6: 통합 테스트 보완분 커밋**

```bash
git add settlement-service/src user-service/src config/src
git commit -m "test: gRPC 정산 전달 통합 시나리오 검증"
```

변경이 없으면 빈 커밋은 만들지 않는다.

## Task 12: 전체 변경 검증, push와 Draft PR 생성

**Files:**

- Review: 현재 브랜치 전체 diff
- Update if required: `settlement-service/docs/superpowers/specs/2026-07-29-grpc-seller-settlement-delivery-design.md`
- Create through GitHub: Draft PR

- [ ] **Step 1: 프로젝트 검증 스킬로 전체 변경 검토**

`verify-project-changes`를 사용해 기준 브랜치 `feat/#635-settlement-batch-reconciliation`과 비교한다. 특히 다음을 확인한다.

- 허용 범위와 사용자 승인 범위 밖 수정 없음
- Settlement V8, User V4 외 마이그레이션 없음
- Query gRPC 인증 회귀 없음
- 기본 4MiB 제한을 덮어쓰는 설정 없음
- terminal Delivery 자동 재시도 없음
- #639, #642, #655 구현 유입 없음
- Outbox 삭제와 proto 변경이 각각 독립 커밋으로 남음

- [ ] **Step 2: 최종 테스트 재실행**

Run:

```bash
./gradlew :settlement-service:test :user-service:test :config:test
./gradlew :settlement-service:check :user-service:check :config:check
git status --short
git log --oneline feat/#635-settlement-batch-reconciliation..HEAD
```

Expected: 테스트와 check 모두 PASS, 의도하지 않은 미커밋 변경 없음, 커밋이 Task 단위로 분리됨.

- [ ] **Step 3: 현재 브랜치 push**

```bash
git push -u origin feat/#637-grpc-settlement-delivery-reconciliation
```

- [ ] **Step 4: Draft PR 생성**

`create-project-pr`을 사용해 base를 `feat/#635-settlement-batch-reconciliation`, head를 `feat/#637-grpc-settlement-delivery-reconciliation`로 지정한다. PR은 Draft로 만들고 merge하지 않는다.

PR 본문 필수 내용:

```markdown
## 변경 사항
- Kafka Outbox 전달을 정산 건별 gRPC 등록으로 교체
- User Service 멱등 저장과 커밋 후 read-back 추가
- Settlement Delivery 상태, 재시도, 직접 대사와 배치 집계 추가
- Settlement V8, User V4 및 서비스 간 전용 토큰 설정 추가

## 테스트
- `./gradlew :settlement-service:test :user-service:test :config:test`
- `./gradlew :settlement-service:check :user-service:check :config:check`

## 의존성과 제외 범위
- base: `feat/#635-settlement-batch-reconciliation`
- #642가 detail 계약을 확장하면 기존 proto 필드 번호를 유지하고 신규 필드로 통합
- #639 SourceLine 대사와 #655 어드민 재전송은 제외

Refs #637
```

- [ ] **Step 5: 결과 보고**

다음을 사용자에게 보고한다.

- Draft PR 번호와 URL
- 최종 커밋 목록
- 실행한 테스트와 결과
- Settlement V8/User V4 및 V6→V7→V8 적용 순서
- #642 proto 통합 지점
- merge하지 않았다는 사실
