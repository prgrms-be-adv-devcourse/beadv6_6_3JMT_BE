# 환불 조회 gRPC 서버 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** order-service가 Kafka `payment-events`(`PAYMENT_REFUNDED`/`PAYMENT_REFUND_FAILED`) 이벤트를 못 받았을 때 폴백 조회할 수 있도록, payment-service에 gRPC 서버(`PaymentQueryService.GetRefund`)를 신설한다.

**Architecture:** payment-service는 지금까지 gRPC를 클라이언트로만 썼다(order-service 호출용). 이번엔 반대 방향 — payment-service가 서버 — 로 신규 proto 계약(`../grpc/payment/payment_query.proto`)을 정의하고, 클린 아키텍처 규칙에 따라 domain(조회 메서드) → application(usecase/service) → infrastructure.grpc(입력 어댑터, Kafka 컨슈머와 동일하게 usecase에 직접 의존) 순으로 쌓는다.

**Tech Stack:** Spring Boot 4.1 네이티브 gRPC(`spring-boot-starter-grpc-server`, `@GrpcService` — `org.springframework.grpc.server.service.GrpcService`), JPA(Spring Data), JUnit5 + Mockito + AssertJ, `io.grpc:grpc-inprocess`(서버 테스트), Testcontainers PostgreSQL(JPA 테스트).

## Global Constraints

- 문서·커밋 메시지·테스트 메서드명은 한국어, 클래스/필드/메서드 식별자는 영어 (`payment-service/CLAUDE.md`).
- 클린 아키텍처 의존 방향 준수: `infrastructure`는 `application.usecase`/`domain.*`만 참조(단, gRPC 서버는 Kafka 컨슈머와 같은 "입력 어댑터" 예외로 `application.usecase` 직접 의존 허용) — `.claude/rules/architecture.md`.
- 도메인 에러 코드는 `application.exception.PaymentErrorCode`(common-module `ErrorCode` 구현)에 정의 — `.claude/rules/api-error-handling.md`.
- 단언은 AssertJ(`assertThat`), Kafka/DB 통합 테스트는 Testcontainers(H2 금지) — `payment-service/CLAUDE.md`.
- gRPC 서버 스타터는 `org.springframework.boot:spring-boot-starter-grpc-server`(Spring Boot 4.1 네이티브, 서드파티 아님) — 이미 user-service가 동일 스타터로 서버 운영 중임을 확인함.
- 조회 키는 `paymentId` + `orderProductId`만 사용(userId 조회 키/소유자 검증 없음) — 설계 확정 사항, `.claude/plans/16-refund-query-grpc.md`.
- FAILED 환불의 실패 사유/시각은 DB에 없으므로 응답 계약에 포함하지 않는다 — 스키마 변경 범위 밖.

---

### Task 1: proto 계약 + gRPC 서버 빌드 설정

**Files:**
- Create: `grpc/payment/payment_query.proto` (모노레포 루트 기준, payment-service에서는 `../grpc/payment/payment_query.proto`)
- Modify: `grpc/README.md` ("현재 레이아웃" 표)
- Modify: `payment-service/build.gradle`

**Interfaces:**
- Produces: `com.prompthub.payment.grpc.PaymentQueryServiceGrpc`(`.PaymentQueryServiceImplBase`, `.PaymentQueryServiceBlockingStub`), `com.prompthub.payment.grpc.GetRefundRequest`(`payment_id`, `order_product_id` — Task 4의 gRPC 어댑터가 사용), `com.prompthub.payment.grpc.GetRefundResponse`(`payment_id`, `order_id`, `user_id`, `order_product_id`, `amount`, `payment_status`, `refund_status`, `refunded_at`).

- [ ] **Step 1: proto 계약 파일 작성**

`grpc/payment/payment_query.proto` (신규 파일):

```proto
syntax = "proto3";

package prompthub.payment;

option java_multiple_files = true;
option java_package = "com.prompthub.payment.grpc";
option java_outer_classname = "PaymentQueryProto";

service PaymentQueryService {
  rpc GetRefund(GetRefundRequest) returns (GetRefundResponse);
}

message GetRefundRequest {
  string payment_id = 1;
  string order_product_id = 2;
}

message GetRefundResponse {
  string payment_id = 1;
  string order_id = 2;
  string user_id = 3;
  string order_product_id = 4;
  int32 amount = 5;
  string payment_status = 6;   // Payment.status (예: PARTIAL_REFUNDED, ALL_REFUNDED)
  string refund_status = 7;    // RefundStatus (COMPLETED, FAILED, REQUESTED)
  string refunded_at = 8;      // ISO 8601, refund_status=COMPLETED일 때만 값 존재
}
```

- [ ] **Step 2: `grpc/README.md`의 "현재 레이아웃" 표에 신규 행 추가**

기존 표(파일 상단 "## 현재 레이아웃" 섹션):

```
grpc/
├── user/seller_query.proto      ← SellerQueryService.GetSellers        (소유: user)
├── order/order_query.proto      ← OrderQueryService.GetSettleableLines (소유: order, 서버 미구현)
└── product/product_query.proto  ← ProductQueryService.Get* (셀러통계·스냅샷·콘텐츠·상품조회, 소유: product)
```

아래로 교체(payment 행 추가):

```
grpc/
├── user/seller_query.proto      ← SellerQueryService.GetSellers        (소유: user)
├── order/order_query.proto      ← OrderQueryService.GetSettleableLines (소유: order, 서버 미구현)
├── product/product_query.proto  ← ProductQueryService.Get* (셀러통계·스냅샷·콘텐츠·상품조회, 소유: product)
└── payment/payment_query.proto  ← PaymentQueryService.GetRefund        (소유: payment)
```

- [ ] **Step 3: `payment-service/build.gradle`에 gRPC 서버 스타터 + srcDir 추가**

`payment-service/build.gradle`의 `dependencies { }` 블록에서 아래 줄 바로 뒤:

```gradle
    // gRPC (Spring Boot 4.1 네이티브 starter — grpc-netty/grpc-stub 전이 포함, 버전은 Boot BOM 관리)
    implementation 'org.springframework.boot:spring-boot-starter-grpc-client'
    // 생성된 스텁이 io.grpc.protobuf.ProtoUtils를 직접 참조 — client starter에는 전이되지 않아 명시 필요
    implementation 'io.grpc:grpc-protobuf'
```

바로 아래에 추가:

```gradle
    // gRPC 서버 (Spring Boot 4.1 네이티브 — order-service향 환불 조회 응답용)
    implementation 'org.springframework.boot:spring-boot-starter-grpc-server'
```

그리고 파일 하단 `protobuf { ... }` 블록 바로 위에 아래 블록 신규 추가:

```gradle
// 루트 공유 gRPC 계약을 이 모듈 proto 소스에 더한다.
// grpc/payment: 소유 payment(이 모듈이 서버). settlement-service/docs/architecture/grpc-contract-ownership.md 참고.
sourceSets {
    main {
        proto {
            srcDir "${rootProject.projectDir}/grpc/payment"
        }
    }
}
```

- [ ] **Step 4: proto 스텁 생성 확인**

Run: `../gradlew :payment-service:generateProto`
Expected: `BUILD SUCCESSFUL`. 아래 파일들이 생성되어 있는지 확인:

```bash
find /Users/anjinpyo/developments/dev-course/projects/beadv6_6_3JMT_BE/payment-service/build/generated/sources/proto/main -iname "PaymentQuery*"
```

Expected 출력에 `PaymentQueryProto.java`, `PaymentQueryServiceGrpc.java`, `GetRefundRequest.java`, `GetRefundResponse.java` 등이 포함되어야 함(정확한 파일 분할은 protoc 버전에 따라 java/grpc-java 두 하위 디렉터리로 나뉠 수 있음 — `find` 결과로 존재만 확인).

- [ ] **Step 5: Commit**

```bash
git add grpc/payment/payment_query.proto grpc/README.md payment-service/build.gradle
git commit -m "$(cat <<'EOF'
feat: 환불 조회 gRPC 계약(payment_query.proto) 추가

- order-service가 Kafka 환불 이벤트 유실 시 폴백 조회할 PaymentQueryService.GetRefund 계약 신설
- 모노레포 grpc 계약 소유 컨벤션에 따라 grpc/payment/에 위치(서버=payment 소유)
- payment-service build.gradle에 gRPC 서버 스타터(Spring Boot 4.1 네이티브) + srcDir 추가
EOF
)"
```

---

### Task 2: RefundRepository 조회 메서드 (`findByPaymentIdAndOrderProductId`)

**Files:**
- Modify: `payment-service/src/main/java/com/prompthub/paymentservice/domain/repository/RefundRepository.java`
- Modify: `payment-service/src/main/java/com/prompthub/paymentservice/infrastructure/persistence/RefundJpaRepository.java`
- Modify: `payment-service/src/main/java/com/prompthub/paymentservice/infrastructure/persistence/RefundRepositoryAdapter.java`
- Test: `payment-service/src/test/java/com/prompthub/paymentservice/infrastructure/persistence/RefundJpaRepositoryTest.java`

**Interfaces:**
- Consumes: 기존 `Refund.create(UUID paymentId, UUID userId, int refundAmount, String reason, UUID orderProductId)` 팩토리(`domain/model/Refund.java`), `RefundJpaRepository extends JpaRepository<Refund, UUID>`.
- Produces: `RefundRepository.findByPaymentIdAndOrderProductId(UUID paymentId, UUID orderProductId): Optional<Refund>` — Task 3의 `GetRefundService`가 이 시그니처로 호출한다.

- [ ] **Step 1: 실패하는 테스트 작성**

`RefundJpaRepositoryTest.java`의 마지막 테스트(`findByPaymentIdAndStatus_COMPLETED_건만_조회`) 뒤에 아래 두 메서드 추가:

```java
    @Test
    void findByPaymentIdAndOrderProductId_정상_조회() {
        UUID paymentId = UUID.randomUUID();
        UUID orderProductId = UUID.randomUUID();
        Refund refund = Refund.create(paymentId, UUID.randomUUID(), 4_000, null, orderProductId);
        refundJpaRepository.saveAndFlush(refund);

        java.util.Optional<Refund> found = refundJpaRepository.findByPaymentIdAndOrderProductId(paymentId, orderProductId);

        assertThat(found).isPresent();
        assertThat(found.get().getRefundAmount()).isEqualTo(4_000);
    }

    @Test
    void findByPaymentIdAndOrderProductId_없으면_empty() {
        java.util.Optional<Refund> found = refundJpaRepository
            .findByPaymentIdAndOrderProductId(UUID.randomUUID(), UUID.randomUUID());

        assertThat(found).isEmpty();
    }
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `../gradlew :payment-service:test --tests "com.prompthub.paymentservice.infrastructure.persistence.RefundJpaRepositoryTest"`
Expected: FAIL — `cannot find symbol: method findByPaymentIdAndOrderProductId` (컴파일 에러)

- [ ] **Step 3: `RefundJpaRepository`에 메서드 추가**

`RefundJpaRepository.java` 전체를 아래로 교체:

```java
package com.prompthub.paymentservice.infrastructure.persistence;

import com.prompthub.paymentservice.domain.model.Refund;
import com.prompthub.paymentservice.domain.model.RefundStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundJpaRepository extends JpaRepository<Refund, UUID> {
    List<Refund> findByPaymentIdAndStatus(UUID paymentId, RefundStatus status);
    Optional<Refund> findByPaymentIdAndOrderProductId(UUID paymentId, UUID orderProductId);
}
```

- [ ] **Step 4: `RefundRepository`(domain 인터페이스)에 메서드 추가**

`RefundRepository.java` 전체를 아래로 교체:

```java
package com.prompthub.paymentservice.domain.repository;

import com.prompthub.paymentservice.domain.model.Refund;
import com.prompthub.paymentservice.domain.model.RefundStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefundRepository {
    Refund save(Refund refund);
    Optional<Refund> findById(UUID id);
    List<Refund> findByPaymentIdAndStatus(UUID paymentId, RefundStatus status);
    Optional<Refund> findByPaymentIdAndOrderProductId(UUID paymentId, UUID orderProductId);
}
```

- [ ] **Step 5: `RefundRepositoryAdapter`에 위임 구현 추가**

`RefundRepositoryAdapter.java` 전체를 아래로 교체:

```java
package com.prompthub.paymentservice.infrastructure.persistence;

import com.prompthub.paymentservice.domain.repository.RefundRepository;
import com.prompthub.paymentservice.domain.model.Refund;
import com.prompthub.paymentservice.domain.model.RefundStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;


@Component
@RequiredArgsConstructor
public class RefundRepositoryAdapter implements RefundRepository {

    private final RefundJpaRepository jpaRepository;

    @Override
    public Refund save(Refund refund) {
        return jpaRepository.save(refund);
    }

    @Override
    public Optional<Refund> findById(UUID id) {
        return jpaRepository.findById(id);
    }

    @Override
    public List<Refund> findByPaymentIdAndStatus(UUID paymentId, RefundStatus status) {
        return jpaRepository.findByPaymentIdAndStatus(paymentId, status);
    }

    @Override
    public Optional<Refund> findByPaymentIdAndOrderProductId(UUID paymentId, UUID orderProductId) {
        return jpaRepository.findByPaymentIdAndOrderProductId(paymentId, orderProductId);
    }
}
```

- [ ] **Step 6: 테스트 실행해서 통과 확인**

Run: `../gradlew :payment-service:test --tests "com.prompthub.paymentservice.infrastructure.persistence.RefundJpaRepositoryTest"`
Expected: PASS (5개 테스트 모두 통과 — 기존 3개 + 신규 2개)

- [ ] **Step 7: Commit**

```bash
git add payment-service/src/main/java/com/prompthub/paymentservice/domain/repository/RefundRepository.java \
        payment-service/src/main/java/com/prompthub/paymentservice/infrastructure/persistence/RefundJpaRepository.java \
        payment-service/src/main/java/com/prompthub/paymentservice/infrastructure/persistence/RefundRepositoryAdapter.java \
        payment-service/src/test/java/com/prompthub/paymentservice/infrastructure/persistence/RefundJpaRepositoryTest.java
git commit -m "$(cat <<'EOF'
feat: paymentId+orderProductId로 Refund 단건 조회 메서드 추가

환불 조회 gRPC 응답 구성에 필요한 조회 경로. (payment_id, order_product_id)
유니크 인덱스(uk_refund_payment_order_product)가 이미 보장하므로 단건 조회로 충분.
EOF
)"
```

---

### Task 3: 환불 조회 UseCase/Service (`GetRefundService`)

**Files:**
- Modify: `payment-service/src/main/java/com/prompthub/paymentservice/application/exception/PaymentErrorCode.java`
- Create: `payment-service/src/main/java/com/prompthub/paymentservice/application/dto/command/GetRefundCommand.java`
- Create: `payment-service/src/main/java/com/prompthub/paymentservice/application/dto/result/RefundQueryResult.java`
- Create: `payment-service/src/main/java/com/prompthub/paymentservice/application/usecase/GetRefundUseCase.java`
- Create: `payment-service/src/main/java/com/prompthub/paymentservice/application/service/GetRefundService.java`
- Test: `payment-service/src/test/java/com/prompthub/paymentservice/application/service/GetRefundServiceTest.java`

**Interfaces:**
- Consumes: `PaymentRepository.findById(UUID): Optional<Payment>`(`domain/repository/PaymentRepository.java`), `RefundRepository.findByPaymentIdAndOrderProductId(UUID, UUID): Optional<Refund>`(Task 2), `Payment.getId()/getOrderId()/getUserId()/getStatus()`(`PaymentStatus`), `Refund.getRefundAmount()/getStatus()/getCompletedAt()`(`RefundStatus`).
- Produces: `GetRefundUseCase.getRefund(GetRefundCommand): RefundQueryResult` — Task 4의 `PaymentQueryGrpcService`가 이 시그니처로 호출한다. `RefundQueryResult(UUID paymentId, UUID orderId, UUID userId, UUID orderProductId, int amount, String paymentStatus, String refundStatus, OffsetDateTime refundedAt)`.

- [ ] **Step 1: `PaymentErrorCode`에 `REFUND_NOT_FOUND` 추가**

`PaymentErrorCode.java`의 enum 상수 목록에서 마지막 상수를 아래로 교체(세미콜론 위치 이동):

```java
    NOT_ORDER_OWNER(HttpStatus.FORBIDDEN, "PAY010", "본인 주문만 결제할 수 있습니다."),
    REFUND_NOT_FOUND(HttpStatus.NOT_FOUND, "PAY011", "환불 내역을 찾을 수 없습니다.");
```

- [ ] **Step 2: 실패하는 테스트 작성**

`GetRefundServiceTest.java` (신규 파일):

```java
package com.prompthub.paymentservice.application.service;

import com.prompthub.exception.BusinessException;
import com.prompthub.paymentservice.application.dto.command.GetRefundCommand;
import com.prompthub.paymentservice.application.dto.result.RefundQueryResult;
import com.prompthub.paymentservice.application.exception.PaymentErrorCode;
import com.prompthub.paymentservice.domain.model.Payment;
import com.prompthub.paymentservice.domain.model.PaymentStatus;
import com.prompthub.paymentservice.domain.model.Refund;
import com.prompthub.paymentservice.domain.repository.PaymentRepository;
import com.prompthub.paymentservice.domain.repository.RefundRepository;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetRefundServiceTest {

    @Mock
    PaymentRepository paymentRepository;
    @Mock
    RefundRepository refundRepository;

    GetRefundService service;

    @BeforeEach
    void setUp() {
        service = new GetRefundService(paymentRepository, refundRepository);
    }

    @Test
    void 결제_건_없으면_PAYMENT_NOT_FOUND_예외() {
        UUID paymentId = UUID.randomUUID();
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.empty());

        GetRefundCommand command = new GetRefundCommand(paymentId, UUID.randomUUID());

        assertThatThrownBy(() -> service.getRefund(command))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getErrorCode())
            .isEqualTo(PaymentErrorCode.PAYMENT_NOT_FOUND);
    }

    @Test
    void 환불_건_없으면_REFUND_NOT_FOUND_예외() {
        Payment payment = 결제_생성_후_승인(10_000);
        UUID orderProductId = UUID.randomUUID();
        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
        when(refundRepository.findByPaymentIdAndOrderProductId(payment.getId(), orderProductId))
            .thenReturn(Optional.empty());

        GetRefundCommand command = new GetRefundCommand(payment.getId(), orderProductId);

        assertThatThrownBy(() -> service.getRefund(command))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getErrorCode())
            .isEqualTo(PaymentErrorCode.REFUND_NOT_FOUND);
    }

    @Test
    void 정상_조회_시_필드_매핑_확인() {
        Payment payment = 결제_생성_후_승인(10_000);
        UUID orderProductId = UUID.randomUUID();
        OffsetDateTime completedAt = OffsetDateTime.now();
        Refund refund = Refund.create(payment.getId(), payment.getUserId(), 4_000, null, orderProductId);
        refund.complete(completedAt);
        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
        when(refundRepository.findByPaymentIdAndOrderProductId(payment.getId(), orderProductId))
            .thenReturn(Optional.of(refund));

        GetRefundCommand command = new GetRefundCommand(payment.getId(), orderProductId);
        RefundQueryResult result = service.getRefund(command);

        assertThat(result.paymentId()).isEqualTo(payment.getId());
        assertThat(result.orderId()).isEqualTo(payment.getOrderId());
        assertThat(result.userId()).isEqualTo(payment.getUserId());
        assertThat(result.orderProductId()).isEqualTo(orderProductId);
        assertThat(result.amount()).isEqualTo(4_000);
        assertThat(result.paymentStatus()).isEqualTo(PaymentStatus.PAID.name());
        assertThat(result.refundStatus()).isEqualTo("COMPLETED");
        assertThat(result.refundedAt()).isEqualTo(completedAt);
    }

    private Payment 결제_생성_후_승인(int amount) {
        Payment payment = Payment.create(UUID.randomUUID(), UUID.randomUUID(), "pg-key", "TOSS_PAYMENTS", "CARD", false, amount);
        payment.markRequested(OffsetDateTime.now());
        payment.approve(amount, "카드", "{}", OffsetDateTime.now());
        return payment;
    }
}
```

- [ ] **Step 3: 테스트 실행해서 실패 확인**

Run: `../gradlew :payment-service:test --tests "com.prompthub.paymentservice.application.service.GetRefundServiceTest"`
Expected: FAIL — 컴파일 에러(`GetRefundCommand`, `RefundQueryResult`, `GetRefundService` 클래스 없음)

- [ ] **Step 4: DTO/UseCase 작성**

`GetRefundCommand.java` (신규 파일):

```java
package com.prompthub.paymentservice.application.dto.command;

import java.util.UUID;

public record GetRefundCommand(UUID paymentId, UUID orderProductId) {}
```

`RefundQueryResult.java` (신규 파일):

```java
package com.prompthub.paymentservice.application.dto.result;

import java.time.OffsetDateTime;
import java.util.UUID;

public record RefundQueryResult(
    UUID paymentId,
    UUID orderId,
    UUID userId,
    UUID orderProductId,
    int amount,
    String paymentStatus,
    String refundStatus,
    OffsetDateTime refundedAt
) {}
```

`GetRefundUseCase.java` (신규 파일):

```java
package com.prompthub.paymentservice.application.usecase;

import com.prompthub.paymentservice.application.dto.command.GetRefundCommand;
import com.prompthub.paymentservice.application.dto.result.RefundQueryResult;

/**
 * order-service가 Kafka 환불 이벤트(PAYMENT_REFUNDED/PAYMENT_REFUND_FAILED)를
 * 못 받았을 때 gRPC로 폴백 조회하는 단건 환불 조회.
 */
public interface GetRefundUseCase {
    RefundQueryResult getRefund(GetRefundCommand command);
}
```

- [ ] **Step 5: `GetRefundService` 구현**

`GetRefundService.java` (신규 파일):

```java
package com.prompthub.paymentservice.application.service;

import com.prompthub.exception.BusinessException;
import com.prompthub.paymentservice.application.dto.command.GetRefundCommand;
import com.prompthub.paymentservice.application.dto.result.RefundQueryResult;
import com.prompthub.paymentservice.application.exception.PaymentErrorCode;
import com.prompthub.paymentservice.application.usecase.GetRefundUseCase;
import com.prompthub.paymentservice.domain.model.Payment;
import com.prompthub.paymentservice.domain.model.Refund;
import com.prompthub.paymentservice.domain.repository.PaymentRepository;
import com.prompthub.paymentservice.domain.repository.RefundRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GetRefundService implements GetRefundUseCase {

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;

    @Override
    public RefundQueryResult getRefund(GetRefundCommand command) {
        Payment payment = paymentRepository.findById(command.paymentId())
            .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND));

        Refund refund = refundRepository
            .findByPaymentIdAndOrderProductId(command.paymentId(), command.orderProductId())
            .orElseThrow(() -> new BusinessException(PaymentErrorCode.REFUND_NOT_FOUND));

        return new RefundQueryResult(
            payment.getId(),
            payment.getOrderId(),
            payment.getUserId(),
            refund.getOrderProductId(),
            refund.getRefundAmount(),
            payment.getStatus().name(),
            refund.getStatus().name(),
            refund.getCompletedAt()
        );
    }
}
```

- [ ] **Step 6: 테스트 실행해서 통과 확인**

Run: `../gradlew :payment-service:test --tests "com.prompthub.paymentservice.application.service.GetRefundServiceTest"`
Expected: PASS (3개 테스트 모두 통과)

- [ ] **Step 7: Commit**

```bash
git add payment-service/src/main/java/com/prompthub/paymentservice/application/exception/PaymentErrorCode.java \
        payment-service/src/main/java/com/prompthub/paymentservice/application/dto/command/GetRefundCommand.java \
        payment-service/src/main/java/com/prompthub/paymentservice/application/dto/result/RefundQueryResult.java \
        payment-service/src/main/java/com/prompthub/paymentservice/application/usecase/GetRefundUseCase.java \
        payment-service/src/main/java/com/prompthub/paymentservice/application/service/GetRefundService.java \
        payment-service/src/test/java/com/prompthub/paymentservice/application/service/GetRefundServiceTest.java
git commit -m "$(cat <<'EOF'
feat: 환불 조회 UseCase/Service 구현 (GetRefundService)

paymentId+orderProductId로 Payment/Refund를 조회해 RefundQueryResult로 매핑.
gRPC 어댑터(다음 커밋)가 이 usecase를 그대로 호출한다.
EOF
)"
```

---

### Task 4: gRPC 서버 입력 어댑터 (`PaymentQueryGrpcService`)

**Files:**
- Create: `payment-service/src/main/java/com/prompthub/paymentservice/infrastructure/grpc/PaymentQueryGrpcService.java`
- Test: `payment-service/src/test/java/com/prompthub/paymentservice/infrastructure/grpc/PaymentQueryGrpcServiceTest.java`

**Interfaces:**
- Consumes: `GetRefundUseCase.getRefund(GetRefundCommand): RefundQueryResult`(Task 3), `com.prompthub.payment.grpc.PaymentQueryServiceGrpc.PaymentQueryServiceImplBase`/`GetRefundRequest`/`GetRefundResponse`(Task 1 생성 스텁).
- Produces: `@GrpcService` 빈 — Spring Boot 4.1 gRPC 서버 자동 등록 대상. 다음 태스크 없음(마지막 코드 태스크).

- [ ] **Step 1: 실패하는 테스트 작성**

`PaymentQueryGrpcServiceTest.java` (신규 파일):

```java
package com.prompthub.paymentservice.infrastructure.grpc;

import com.prompthub.exception.BusinessException;
import com.prompthub.paymentservice.application.dto.command.GetRefundCommand;
import com.prompthub.paymentservice.application.dto.result.RefundQueryResult;
import com.prompthub.paymentservice.application.exception.PaymentErrorCode;
import com.prompthub.paymentservice.application.usecase.GetRefundUseCase;
import com.prompthub.payment.grpc.GetRefundRequest;
import com.prompthub.payment.grpc.GetRefundResponse;
import com.prompthub.payment.grpc.PaymentQueryServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class PaymentQueryGrpcServiceTest {

    private Server server;
    private ManagedChannel channel;

    @AfterEach
    void tearDown() {
        if (channel != null) {
            channel.shutdownNow();
        }
        if (server != null) {
            server.shutdownNow();
        }
    }

    private PaymentQueryServiceGrpc.PaymentQueryServiceBlockingStub stubWith(GetRefundUseCase useCase) throws Exception {
        String serverName = UUID.randomUUID().toString();
        server = InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(new PaymentQueryGrpcService(useCase))
            .build()
            .start();
        channel = InProcessChannelBuilder.forName(serverName)
            .directExecutor()
            .build();
        return PaymentQueryServiceGrpc.newBlockingStub(channel);
    }

    @Test
    void 정상_조회_시_GetRefundResponse_반환() throws Exception {
        UUID paymentId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID orderProductId = UUID.randomUUID();
        OffsetDateTime refundedAt = OffsetDateTime.now();

        GetRefundUseCase useCase = Mockito.mock(GetRefundUseCase.class);
        when(useCase.getRefund(new GetRefundCommand(paymentId, orderProductId))).thenReturn(new RefundQueryResult(
            paymentId, orderId, userId, orderProductId, 4_000, "PARTIAL_REFUNDED", "COMPLETED", refundedAt));

        PaymentQueryServiceGrpc.PaymentQueryServiceBlockingStub stub = stubWith(useCase);

        GetRefundResponse response = stub.getRefund(GetRefundRequest.newBuilder()
            .setPaymentId(paymentId.toString())
            .setOrderProductId(orderProductId.toString())
            .build());

        assertThat(response.getPaymentId()).isEqualTo(paymentId.toString());
        assertThat(response.getOrderId()).isEqualTo(orderId.toString());
        assertThat(response.getUserId()).isEqualTo(userId.toString());
        assertThat(response.getOrderProductId()).isEqualTo(orderProductId.toString());
        assertThat(response.getAmount()).isEqualTo(4_000);
        assertThat(response.getPaymentStatus()).isEqualTo("PARTIAL_REFUNDED");
        assertThat(response.getRefundStatus()).isEqualTo("COMPLETED");
        assertThat(response.getRefundedAt()).isEqualTo(refundedAt.toString());
    }

    @Test
    void PAYMENT_NOT_FOUND_예외_시_NOT_FOUND_status() throws Exception {
        GetRefundUseCase useCase = Mockito.mock(GetRefundUseCase.class);
        when(useCase.getRefund(any())).thenThrow(new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND));

        PaymentQueryServiceGrpc.PaymentQueryServiceBlockingStub stub = stubWith(useCase);

        assertThatThrownBy(() -> stub.getRefund(GetRefundRequest.newBuilder()
                .setPaymentId(UUID.randomUUID().toString())
                .setOrderProductId(UUID.randomUUID().toString())
                .build()))
            .isInstanceOf(StatusRuntimeException.class)
            .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
            .isEqualTo(Status.Code.NOT_FOUND);
    }

    @Test
    void REFUND_NOT_FOUND_예외_시_NOT_FOUND_status() throws Exception {
        GetRefundUseCase useCase = Mockito.mock(GetRefundUseCase.class);
        when(useCase.getRefund(any())).thenThrow(new BusinessException(PaymentErrorCode.REFUND_NOT_FOUND));

        PaymentQueryServiceGrpc.PaymentQueryServiceBlockingStub stub = stubWith(useCase);

        assertThatThrownBy(() -> stub.getRefund(GetRefundRequest.newBuilder()
                .setPaymentId(UUID.randomUUID().toString())
                .setOrderProductId(UUID.randomUUID().toString())
                .build()))
            .isInstanceOf(StatusRuntimeException.class)
            .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
            .isEqualTo(Status.Code.NOT_FOUND);
    }
}
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `../gradlew :payment-service:test --tests "com.prompthub.paymentservice.infrastructure.grpc.PaymentQueryGrpcServiceTest"`
Expected: FAIL — 컴파일 에러(`PaymentQueryGrpcService` 클래스 없음)

- [ ] **Step 3: `PaymentQueryGrpcService` 구현**

`PaymentQueryGrpcService.java` (신규 파일):

```java
package com.prompthub.paymentservice.infrastructure.grpc;

import com.prompthub.exception.BusinessException;
import com.prompthub.payment.grpc.GetRefundRequest;
import com.prompthub.payment.grpc.GetRefundResponse;
import com.prompthub.payment.grpc.PaymentQueryServiceGrpc;
import com.prompthub.paymentservice.application.dto.command.GetRefundCommand;
import com.prompthub.paymentservice.application.dto.result.RefundQueryResult;
import com.prompthub.paymentservice.application.usecase.GetRefundUseCase;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.grpc.server.service.GrpcService;

@Slf4j
@GrpcService
public class PaymentQueryGrpcService extends PaymentQueryServiceGrpc.PaymentQueryServiceImplBase {

    private final GetRefundUseCase getRefundUseCase;

    public PaymentQueryGrpcService(GetRefundUseCase getRefundUseCase) {
        this.getRefundUseCase = getRefundUseCase;
    }

    @Override
    public void getRefund(GetRefundRequest request, StreamObserver<GetRefundResponse> responseObserver) {
        try {
            GetRefundCommand command = new GetRefundCommand(
                UUID.fromString(request.getPaymentId()),
                UUID.fromString(request.getOrderProductId()));

            RefundQueryResult result = getRefundUseCase.getRefund(command);

            GetRefundResponse.Builder response = GetRefundResponse.newBuilder()
                .setPaymentId(result.paymentId().toString())
                .setOrderId(result.orderId().toString())
                .setUserId(result.userId().toString())
                .setOrderProductId(result.orderProductId().toString())
                .setAmount(result.amount())
                .setPaymentStatus(result.paymentStatus())
                .setRefundStatus(result.refundStatus());
            if (result.refundedAt() != null) {
                response.setRefundedAt(result.refundedAt().toString());
            }
            responseObserver.onNext(response.build());
            responseObserver.onCompleted();
        } catch (BusinessException e) {
            log.warn("환불 조회 gRPC 실패 — paymentId={}, orderProductId={}, code={}",
                request.getPaymentId(), request.getOrderProductId(), e.getErrorCode().getCode());
            Status status = e.getErrorCode().getStatus().is4xxClientError() ? Status.NOT_FOUND : Status.INTERNAL;
            responseObserver.onError(new StatusRuntimeException(status.withDescription(e.getMessage())));
        }
    }
}
```

- [ ] **Step 4: 테스트 실행해서 통과 확인**

Run: `../gradlew :payment-service:test --tests "com.prompthub.paymentservice.infrastructure.grpc.PaymentQueryGrpcServiceTest"`
Expected: PASS (3개 테스트 모두 통과)

- [ ] **Step 5: Commit**

```bash
git add payment-service/src/main/java/com/prompthub/paymentservice/infrastructure/grpc/PaymentQueryGrpcService.java \
        payment-service/src/test/java/com/prompthub/paymentservice/infrastructure/grpc/PaymentQueryGrpcServiceTest.java
git commit -m "$(cat <<'EOF'
feat: 환불 조회 gRPC 서버 구현체 추가 (PaymentQueryGrpcService)

Kafka 컨슈머와 동일하게 입력 어댑터로서 application.usecase에 직접 의존.
BusinessException을 gRPC Status로 변환(4xx→NOT_FOUND, 그 외→INTERNAL).
EOF
)"
```

---

### Task 5: 설정 반영 + 문서 갱신 + 전체 빌드 검증

**Files:**
- Modify: `payment-service/src/main/resources/application-local.yml`
- Modify: `docs/architecture/overview.md` (모노레포 루트 기준, payment-service에서는 `../docs/architecture/overview.md`)
- Modify: `payment-service/.claude/docs/events.md`

**Interfaces:** 없음(설정/문서 변경, 앞선 태스크에 의존하는 코드 없음).

- [ ] **Step 1: `application-local.yml`에 gRPC 서버 포트 추가**

`application-local.yml`의 기존 블록:

```yaml
  grpc:
    client:
      channel:
        order:
          target: static://${ORDER_GRPC_HOST:localhost}:${ORDER_GRPC_PORT:9083}
          default:
            deadline: ${ORDER_GRPC_DEADLINE_MS:2000}ms
```

아래로 교체(server 섹션 추가, `client`와 형제 키):

```yaml
  grpc:
    server:
      port: ${PAYMENT_GRPC_PORT:9084}
    client:
      channel:
        order:
          target: static://${ORDER_GRPC_HOST:localhost}:${ORDER_GRPC_PORT:9083}
          default:
            deadline: ${ORDER_GRPC_DEADLINE_MS:2000}ms
```

- [ ] **Step 2: `docs/architecture/overview.md` 모듈 포트 표 갱신**

기존 행(파일 상단 모듈 개요 표):

```
| `payment-service` | 8084 | - (order 9083 **클라이언트**) | 결제 (Toss Payments 연동), `order-events` 구독 |
```

아래로 교체:

```
| `payment-service` | 8084 | 9084 (서버, order-service향 환불 조회) / order 9083 **클라이언트** | 결제 (Toss Payments 연동), `order-events` 구독 |
```

- [ ] **Step 3: `docs/architecture/overview.md` 내부 동기 통신(gRPC) 표에 행 추가**

기존 표의 마지막 행:

```
| payment → order | 9083 | 주문 결제정보 폴백 조회(스냅샷 미확보 시) | `payment-service/.../infrastructure/external/grpc/OrderGrpcClientConfig.java` (**order 측 서버 예정**) |
```

바로 아래에 추가:

```
| order → payment | 9084 | 환불 이벤트 폴백 조회(Kafka 유실 시) | `payment-service/.../infrastructure/grpc/PaymentQueryGrpcService.java` |
```

- [ ] **Step 4: `payment-service/.claude/docs/events.md`에 폴백 조회 채널 언급 추가**

환불 이벤트(`PAYMENT_REFUNDED`/`PAYMENT_REFUND_FAILED`)를 설명하는 섹션 끝에 아래 문단 추가:

```markdown
> **Kafka 유실 시 폴백**: order-service가 위 이벤트를 못 받았을 경우(재시도 소진 → DLT), `PaymentQueryService.GetRefund` gRPC(`grpc/payment/payment_query.proto`, 포트 9084)로 폴백 조회할 수 있다. 조회 키는 `paymentId`+`orderProductId`.
```

- [ ] **Step 5: 전체 빌드 및 테스트 실행**

Run: `../gradlew :payment-service:build`
Expected: `BUILD SUCCESSFUL` — 신규 테스트 3종(`RefundJpaRepositoryTest`, `GetRefundServiceTest`, `PaymentQueryGrpcServiceTest`) 포함 전체 통과.

- [ ] **Step 6: Commit**

```bash
git add payment-service/src/main/resources/application-local.yml \
        docs/architecture/overview.md \
        payment-service/.claude/docs/events.md
git commit -m "$(cat <<'EOF'
docs: 환불 조회 gRPC 서버 설정 및 아키텍처 문서 반영

- application-local.yml에 spring.grpc.server.port(9084) 추가
- overview.md 포트 표·gRPC 통신 표에 order→payment 9084 반영
- events.md에 Kafka 유실 시 gRPC 폴백 조회 안내 추가
EOF
)"
```

---

## Self-Review 메모

- **Spec 커버리지**: `.claude/plans/16-refund-query-grpc.md`의 확정 사항 1~8 전부 Task 1~5에 반영됨(조회 키, 응답 payload, FAILED 갭 제외, 패키지 위치, proto 위치, 네이밍, 네이티브 스타터, 보안 미설정).
- **타입 일관성**: `GetRefundCommand(paymentId, orderProductId)` → `GetRefundUseCase.getRefund` → `RefundQueryResult` 8필드 → `PaymentQueryGrpcService`의 proto 필드 매핑까지 필드명이 Task 3/4에서 동일하게 유지됨을 확인.
- **플레이스홀더 없음**: 모든 코드 블록이 완성된 전체 파일 또는 정확한 diff 위치를 담고 있음.
