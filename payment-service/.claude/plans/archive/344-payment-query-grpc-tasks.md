# 결제 조회 gRPC 엔드포인트 구현 태스크

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** payment-service의 기존 gRPC 서버(`PaymentQueryService`, 9084)에 `GetPayment` rpc를 추가해, order-service가 `PAYMENT_APPROVED`/`PAYMENT_FAILED` Kafka 이벤트를 못 받았을 때 orderId로 결제 현재 상태를 폴백 조회할 수 있게 한다.

**Architecture:** 기존 `GetRefund`(#16)와 완전히 동일한 계층 패턴 — proto → `infrastructure.grpc`(입력 어댑터, 예외적으로 `application.usecase` 직접 의존 허용) → `application.usecase`/`application.service` → `domain.repository`. 조회 키는 `orderId` 하나, 동일 orderId에 재결제로 여러 Payment가 있으면 최신(`created_at` desc) 1건만 반환.

**Tech Stack:** Spring Boot 4.1 native gRPC(`spring-boot-starter-grpc-server`), Spring Data JPA, JUnit5 + Mockito(단위) / Testcontainers PostgreSQL(`AbstractJpaTest`, 영속성), AssertJ.

## Global Constraints

- 수정 가능 범위는 `../../..`와 `../../docs`, 루트 공유 `../grpc/`(계약 소유자 규칙에 따름)로 한정 — 다른 서비스 소스는 건드리지 않는다.
- 테스트 메서드명은 한국어, 클래스/필드/메서드 식별자는 영어. 단언은 AssertJ(`assertThat`).
- 영속성 테스트는 Testcontainers(PostgreSQL) 기반 `AbstractJpaTest` 확장 — H2로 대체하지 않는다.
- 신규 에러 코드 없음 — 기존 `PaymentErrorCode.PAYMENT_NOT_FOUND`(NOT_FOUND, "PAY005") 재사용.
- 커밋·푸시는 사용자가 명시적으로 요청할 때만 실행한다(각 태스크의 "Step: Commit"은 사용자 승인 후 실행).
- 설계 근거: `.claude/plans/344-payment-query-grpc.md`.

---

### Task 1: proto 계약에 `GetPayment` rpc 추가

**Files:**
- Modify: `../grpc/payment/payment_query.proto`
- Modify: `../grpc/README.md`

**Interfaces:**
- Produces: `com.prompthub.payment.grpc.GetPaymentRequest`(필드 `order_id: String`), `com.prompthub.payment.grpc.GetPaymentResponse`(필드 `payment_id/order_id/user_id/status: String`, `amount: int`, `approved_at/failed_at: String`), `com.prompthub.payment.grpc.PaymentQueryServiceGrpc`에 `getPayment` 스텁 메서드 — Task 2~4가 이 생성 클래스를 사용한다.

- [ ] **Step 1: proto 파일에 rpc·message 추가**

`../grpc/payment/payment_query.proto` 전체를 아래로 교체:

```proto
syntax = "proto3";

package prompthub.payment;

option java_multiple_files = true;
option java_package = "com.prompthub.payment.grpc";
option java_outer_classname = "PaymentQueryProto";

service PaymentQueryService {
  rpc GetRefund(GetRefundRequest) returns (GetRefundResponse);
  rpc GetPayment(GetPaymentRequest) returns (GetPaymentResponse);
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

message GetPaymentRequest {
  string order_id = 1;
}

message GetPaymentResponse {
  string payment_id = 1;
  string order_id = 2;
  string user_id = 3;
  string status = 4;       // Payment.status (PAID, FAILED, PARTIAL_REFUNDED 등)
  int32 amount = 5;        // approvedAmount 기준. PAID 아니면 0(미사용 필드)
  string approved_at = 6;  // ISO 8601, PAID일 때만 값 존재
  string failed_at = 7;    // ISO 8601, FAILED일 때만 값 존재
}
```

- [ ] **Step 2: `../grpc/README.md` 레이아웃 표 갱신**

`## 현재 레이아웃` 코드블록에서 payment 행을:

```
└── payment/payment_query.proto    ← PaymentQueryService.GetRefund        (소유: payment)
```

다음으로 교체:

```
└── payment/payment_query.proto    ← PaymentQueryService.GetRefund/GetPayment (소유: payment)
```

- [ ] **Step 3: 코드 생성 확인**

Run (payment-service 디렉터리에서): `../gradlew :payment-service:generateProto`
Expected: `BUILD SUCCESSFUL`. 이후 `build/generated/sources/proto/main/java/com/prompthub/payment/grpc/` 아래 `GetPaymentRequest.java`/`GetPaymentResponse.java`/`PaymentQueryServiceGrpc.java`(getPayment 메서드 포함)가 새로 생성됐는지 확인:

```bash
grep -l "GetPayment" build/generated/sources/proto/main/java/com/prompthub/payment/grpc/*.java build/generated/sources/proto/main/grpc/com/prompthub/payment/grpc/*.java
```

Expected: `PaymentQueryServiceGrpc.java`, `GetPaymentRequest.java`, `GetPaymentResponse.java`, `PaymentQueryProto.java` 등이 출력됨.

- [ ] **Step 4: Commit**

```bash
git add ../grpc/payment/payment_query.proto ../grpc/README.md
git commit -m "$(cat <<'EOF'
feat: 결제 조회 GetPayment gRPC 계약 추가

- PaymentQueryService에 GetPayment rpc 추가 (조회 키: order_id)
- GetPaymentResponse 필드: payment_id/order_id/user_id/status/amount/approved_at/failed_at
- grpc/README.md 레이아웃 표에 GetPayment 반영

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: `PaymentRepository.findLatestByOrderId` 추가

**Files:**
- Modify: `src/main/java/com/prompthub/paymentservice/domain/repository/PaymentRepository.java`
- Modify: `src/main/java/com/prompthub/paymentservice/infrastructure/persistence/PaymentJpaRepository.java`
- Modify: `src/main/java/com/prompthub/paymentservice/infrastructure/persistence/PaymentRepositoryAdapter.java`
- Test: `src/test/java/com/prompthub/paymentservice/infrastructure/persistence/PaymentJpaRepositoryTest.java`

**Interfaces:**
- Consumes: `Payment.create(...)`, `Payment.markRequested(OffsetDateTime)`, `Payment.approve(int, String, String, OffsetDateTime)`, `Payment.fail(String, String, String, String, OffsetDateTime)` (이미 존재, `domain/model/Payment.java`).
- Produces: `PaymentRepository.findLatestByOrderId(UUID orderId): Optional<Payment>` — Task 3의 `GetPaymentService`가 이 메서드를 사용한다.

- [ ] **Step 1: 실패하는 테스트 작성**

`PaymentJpaRepositoryTest.java` 마지막(클래스 닫는 `}` 앞)에 추가:

```java
    @Test
    void findLatestByOrderId_여러건_중_최신_반환() {
        UUID orderId = UUID.randomUUID();

        Payment failed = Payment.create(
            orderId, UUID.randomUUID(), "pg-key-failed", "TOSS_PAYMENTS", "CARD", false, 10_000);
        failed.markRequested(OffsetDateTime.now());
        failed.fail("INVALID_CARD", "카드 오류", "{}", "{}", OffsetDateTime.now());
        paymentJpaRepository.saveAndFlush(failed);

        Payment paid = Payment.create(
            orderId, UUID.randomUUID(), "pg-key-paid", "TOSS_PAYMENTS", "CARD", false, 10_000);
        paid.markRequested(OffsetDateTime.now());
        paid.approve(10_000, "카드", "{}", OffsetDateTime.now());
        paymentJpaRepository.saveAndFlush(paid);

        Optional<Payment> found = paymentJpaRepository.findTopByOrderIdOrderByCreatedAtDesc(orderId);

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(paid.getId());
    }

    @Test
    void findLatestByOrderId_없으면_empty() {
        Optional<Payment> found = paymentJpaRepository.findTopByOrderIdOrderByCreatedAtDesc(UUID.randomUUID());

        assertThat(found).isEmpty();
    }
```

- [ ] **Step 2: 테스트 실행해 실패 확인**

Run: `../gradlew :payment-service:test --tests "com.prompthub.paymentservice.infrastructure.persistence.PaymentJpaRepositoryTest"`
Expected: 컴파일 실패 — `cannot find symbol: method findTopByOrderIdOrderByCreatedAtDesc`

- [ ] **Step 3: `PaymentJpaRepository`에 파생 쿼리 메서드 추가**

`PaymentJpaRepository.java`의 `findByOrderIdAndStatusInForUpdate` 메서드 뒤에 추가:

```java
    Optional<Payment> findTopByOrderIdOrderByCreatedAtDesc(UUID orderId);
```

- [ ] **Step 4: 테스트 실행해 통과 확인**

Run: `../gradlew :payment-service:test --tests "com.prompthub.paymentservice.infrastructure.persistence.PaymentJpaRepositoryTest"`
Expected: `BUILD SUCCESSFUL`, 5개 테스트 모두 PASS(기존 3개 + 신규 2개)

- [ ] **Step 5: `domain.repository.PaymentRepository`에 메서드 선언 추가**

`PaymentRepository.java`를 아래로 교체:

```java
package com.prompthub.paymentservice.domain.repository;

import com.prompthub.paymentservice.domain.model.Payment;
import com.prompthub.paymentservice.domain.model.PaymentStatus;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository {
    Payment save(Payment payment);
    Payment saveAndFlush(Payment payment);
    Optional<Payment> findById(UUID id);
    Optional<Payment> findByIdForUpdate(UUID id);
    boolean existsByOrderIdAndStatusIn(UUID orderId, Collection<PaymentStatus> statuses);
    Optional<Payment> findByOrderIdAndStatusInForUpdate(UUID orderId, Collection<PaymentStatus> statuses);
    Optional<Payment> findLatestByOrderId(UUID orderId);
}
```

- [ ] **Step 6: `PaymentRepositoryAdapter`에 위임 구현 추가**

`PaymentRepositoryAdapter.java` 마지막 메서드(`findByOrderIdAndStatusInForUpdate`) 뒤, 클래스 닫는 `}` 앞에 추가:

```java

    @Override
    public Optional<Payment> findLatestByOrderId(UUID orderId) {
        return jpaRepository.findTopByOrderIdOrderByCreatedAtDesc(orderId);
    }
```

- [ ] **Step 7: 전체 빌드로 컴파일 확인**

Run: `../gradlew :payment-service:build -x test`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/prompthub/paymentservice/domain/repository/PaymentRepository.java \
        src/main/java/com/prompthub/paymentservice/infrastructure/persistence/PaymentJpaRepository.java \
        src/main/java/com/prompthub/paymentservice/infrastructure/persistence/PaymentRepositoryAdapter.java \
        src/test/java/com/prompthub/paymentservice/infrastructure/persistence/PaymentJpaRepositoryTest.java
git commit -m "$(cat <<'EOF'
feat: 주문ID로 최신 결제 조회하는 findLatestByOrderId 추가

- PaymentRepository/PaymentJpaRepository/PaymentRepositoryAdapter에 findLatestByOrderId 추가
- 재결제로 동일 orderId에 여러 Payment가 있을 때 created_at 기준 최신 1건 반환(findTopByOrderIdOrderByCreatedAtDesc)
- PaymentJpaRepositoryTest에 최신건 반환/미존재 케이스 추가

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: application 계층 — `GetPaymentUseCase`/`GetPaymentService`

**Files:**
- Create: `src/main/java/com/prompthub/paymentservice/application/dto/command/GetPaymentCommand.java`
- Create: `src/main/java/com/prompthub/paymentservice/application/dto/result/PaymentQueryResult.java`
- Create: `src/main/java/com/prompthub/paymentservice/application/usecase/GetPaymentUseCase.java`
- Create: `src/main/java/com/prompthub/paymentservice/application/service/GetPaymentService.java`
- Test: `src/test/java/com/prompthub/paymentservice/application/service/GetPaymentServiceTest.java`

**Interfaces:**
- Consumes: `PaymentRepository.findLatestByOrderId(UUID): Optional<Payment>`(Task 2), `Payment.getId()/getOrderId()/getUserId()/getStatus()/getApprovedAmount()/getApprovedAt()/getFailedAt()`(기존, `domain/model/Payment.java`), `PaymentErrorCode.PAYMENT_NOT_FOUND`(기존, `application/exception/PaymentErrorCode.java`).
- Produces: `GetPaymentCommand(UUID orderId)`, `PaymentQueryResult(UUID paymentId, UUID orderId, UUID userId, String status, Integer amount, OffsetDateTime approvedAt, OffsetDateTime failedAt)`, `GetPaymentUseCase.getPayment(GetPaymentCommand): PaymentQueryResult` — Task 4의 `PaymentQueryGrpcService`가 이 usecase를 사용한다.

- [ ] **Step 1: 커맨드/결과 record 작성**

`GetPaymentCommand.java` 신규 작성:

```java
package com.prompthub.paymentservice.application.dto.command;

import java.util.UUID;

public record GetPaymentCommand(UUID orderId) {}
```

`PaymentQueryResult.java` 신규 작성:

```java
package com.prompthub.paymentservice.application.dto.result;

import java.time.OffsetDateTime;
import java.util.UUID;

public record PaymentQueryResult(
    UUID paymentId,
    UUID orderId,
    UUID userId,
    String status,
    Integer amount,
    OffsetDateTime approvedAt,
    OffsetDateTime failedAt
) {}
```

- [ ] **Step 2: 실패하는 테스트 작성**

`GetPaymentServiceTest.java` 신규 작성:

```java
package com.prompthub.paymentservice.application.service;

import com.prompthub.exception.BusinessException;
import com.prompthub.paymentservice.application.dto.command.GetPaymentCommand;
import com.prompthub.paymentservice.application.dto.result.PaymentQueryResult;
import com.prompthub.paymentservice.application.exception.PaymentErrorCode;
import com.prompthub.paymentservice.domain.model.Payment;
import com.prompthub.paymentservice.domain.model.PaymentStatus;
import com.prompthub.paymentservice.domain.repository.PaymentRepository;
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
class GetPaymentServiceTest {

    @Mock
    PaymentRepository paymentRepository;

    GetPaymentService service;

    @BeforeEach
    void setUp() {
        service = new GetPaymentService(paymentRepository);
    }

    @Test
    void 결제_건_없으면_PAYMENT_NOT_FOUND_예외() {
        UUID orderId = UUID.randomUUID();
        when(paymentRepository.findLatestByOrderId(orderId)).thenReturn(Optional.empty());

        GetPaymentCommand command = new GetPaymentCommand(orderId);

        assertThatThrownBy(() -> service.getPayment(command))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getErrorCode())
            .isEqualTo(PaymentErrorCode.PAYMENT_NOT_FOUND);
    }

    @Test
    void PAID_상태_조회_시_필드_매핑_확인() {
        Payment payment = 결제_생성_후_승인(10_000);
        when(paymentRepository.findLatestByOrderId(payment.getOrderId())).thenReturn(Optional.of(payment));

        PaymentQueryResult result = service.getPayment(new GetPaymentCommand(payment.getOrderId()));

        assertThat(result.paymentId()).isEqualTo(payment.getId());
        assertThat(result.orderId()).isEqualTo(payment.getOrderId());
        assertThat(result.userId()).isEqualTo(payment.getUserId());
        assertThat(result.status()).isEqualTo(PaymentStatus.PAID.name());
        assertThat(result.amount()).isEqualTo(10_000);
        assertThat(result.approvedAt()).isNotNull();
        assertThat(result.failedAt()).isNull();
    }

    @Test
    void FAILED_상태_조회_시_필드_매핑_확인() {
        Payment payment = 결제_생성_후_실패();
        when(paymentRepository.findLatestByOrderId(payment.getOrderId())).thenReturn(Optional.of(payment));

        PaymentQueryResult result = service.getPayment(new GetPaymentCommand(payment.getOrderId()));

        assertThat(result.status()).isEqualTo(PaymentStatus.FAILED.name());
        assertThat(result.amount()).isNull();
        assertThat(result.approvedAt()).isNull();
        assertThat(result.failedAt()).isNotNull();
    }

    private Payment 결제_생성_후_승인(int amount) {
        Payment payment = Payment.create(UUID.randomUUID(), UUID.randomUUID(), "pg-key", "TOSS_PAYMENTS", "CARD", false, amount);
        payment.markRequested(OffsetDateTime.now());
        payment.approve(amount, "카드", "{}", OffsetDateTime.now());
        return payment;
    }

    private Payment 결제_생성_후_실패() {
        Payment payment = Payment.create(UUID.randomUUID(), UUID.randomUUID(), "pg-key2", "TOSS_PAYMENTS", "CARD", false, 10_000);
        payment.markRequested(OffsetDateTime.now());
        payment.fail("INVALID_CARD", "카드 오류", "{}", "{}", OffsetDateTime.now());
        return payment;
    }
}
```

- [ ] **Step 3: 테스트 실행해 실패 확인**

Run: `../gradlew :payment-service:test --tests "com.prompthub.paymentservice.application.service.GetPaymentServiceTest"`
Expected: 컴파일 실패 — `GetPaymentUseCase`/`GetPaymentService` 심볼 없음

- [ ] **Step 4: `GetPaymentUseCase` 인터페이스 작성**

```java
package com.prompthub.paymentservice.application.usecase;

import com.prompthub.paymentservice.application.dto.command.GetPaymentCommand;
import com.prompthub.paymentservice.application.dto.result.PaymentQueryResult;

/**
 * order-service가 Kafka 결제 이벤트(PAYMENT_APPROVED/PAYMENT_FAILED)를
 * 못 받았을 때 gRPC로 폴백 조회하는 단건 결제 조회.
 */
public interface GetPaymentUseCase {
    PaymentQueryResult getPayment(GetPaymentCommand command);
}
```

- [ ] **Step 5: `GetPaymentService` 구현체 작성**

```java
package com.prompthub.paymentservice.application.service;

import com.prompthub.exception.BusinessException;
import com.prompthub.paymentservice.application.dto.command.GetPaymentCommand;
import com.prompthub.paymentservice.application.dto.result.PaymentQueryResult;
import com.prompthub.paymentservice.application.exception.PaymentErrorCode;
import com.prompthub.paymentservice.application.usecase.GetPaymentUseCase;
import com.prompthub.paymentservice.domain.model.Payment;
import com.prompthub.paymentservice.domain.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GetPaymentService implements GetPaymentUseCase {

    private final PaymentRepository paymentRepository;

    @Override
    public PaymentQueryResult getPayment(GetPaymentCommand command) {
        Payment payment = paymentRepository.findLatestByOrderId(command.orderId())
            .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND));

        return new PaymentQueryResult(
            payment.getId(),
            payment.getOrderId(),
            payment.getUserId(),
            payment.getStatus().name(),
            payment.getApprovedAmount(),
            payment.getApprovedAt(),
            payment.getFailedAt()
        );
    }
}
```

- [ ] **Step 6: 테스트 실행해 통과 확인**

Run: `../gradlew :payment-service:test --tests "com.prompthub.paymentservice.application.service.GetPaymentServiceTest"`
Expected: `BUILD SUCCESSFUL`, 3개 테스트 PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/prompthub/paymentservice/application/dto/command/GetPaymentCommand.java \
        src/main/java/com/prompthub/paymentservice/application/dto/result/PaymentQueryResult.java \
        src/main/java/com/prompthub/paymentservice/application/usecase/GetPaymentUseCase.java \
        src/main/java/com/prompthub/paymentservice/application/service/GetPaymentService.java \
        src/test/java/com/prompthub/paymentservice/application/service/GetPaymentServiceTest.java
git commit -m "$(cat <<'EOF'
feat: orderId 기반 결제 조회 GetPaymentUseCase/Service 추가

- GetPaymentCommand/PaymentQueryResult record 추가
- GetPaymentUseCase/GetPaymentService: findLatestByOrderId로 조회, 없으면 PAYMENT_NOT_FOUND
- PAID/FAILED 상태별 필드 매핑 단위 테스트(GetPaymentServiceTest) 추가

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: `PaymentQueryGrpcService.getPayment` 추가

**Files:**
- Modify: `src/main/java/com/prompthub/paymentservice/infrastructure/grpc/PaymentQueryGrpcService.java`
- Modify: `src/test/java/com/prompthub/paymentservice/infrastructure/grpc/PaymentQueryGrpcServiceTest.java`

**Interfaces:**
- Consumes: `GetPaymentUseCase.getPayment(GetPaymentCommand): PaymentQueryResult`(Task 3), 생성된 `com.prompthub.payment.grpc.GetPaymentRequest/GetPaymentResponse/PaymentQueryServiceGrpc`(Task 1).
- Produces: gRPC 엔드포인트 `PaymentQueryService/GetPayment` — 이 태스크가 마지막이라 후속 태스크 없음(Task 5는 문서만 갱신).

**주의:** 생성자 시그니처가 `PaymentQueryGrpcService(GetRefundUseCase, GetPaymentUseCase)`로 바뀌므로, 기존 테스트의 `stubWith` 헬퍼와 3개 기존 테스트 메서드 호출부도 함께 수정한다.

- [ ] **Step 1: 실패하는 테스트 작성 — 기존 테스트 파일을 통째로 교체**

`PaymentQueryGrpcServiceTest.java` 전체를 아래로 교체(기존 3개 테스트의 `stubWith` 호출부를 2-인자로 바꾸고, `GetPayment` 테스트 2개를 추가):

```java
package com.prompthub.paymentservice.infrastructure.grpc;

import com.prompthub.exception.BusinessException;
import com.prompthub.paymentservice.application.dto.command.GetPaymentCommand;
import com.prompthub.paymentservice.application.dto.command.GetRefundCommand;
import com.prompthub.paymentservice.application.dto.result.PaymentQueryResult;
import com.prompthub.paymentservice.application.dto.result.RefundQueryResult;
import com.prompthub.paymentservice.application.exception.PaymentErrorCode;
import com.prompthub.paymentservice.application.usecase.GetPaymentUseCase;
import com.prompthub.paymentservice.application.usecase.GetRefundUseCase;
import com.prompthub.payment.grpc.GetPaymentRequest;
import com.prompthub.payment.grpc.GetPaymentResponse;
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

    private PaymentQueryServiceGrpc.PaymentQueryServiceBlockingStub stubWith(
        GetRefundUseCase refundUseCase, GetPaymentUseCase paymentUseCase
    ) throws Exception {
        String serverName = UUID.randomUUID().toString();
        server = InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(new PaymentQueryGrpcService(refundUseCase, paymentUseCase))
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

        GetRefundUseCase refundUseCase = Mockito.mock(GetRefundUseCase.class);
        when(refundUseCase.getRefund(new GetRefundCommand(paymentId, orderProductId))).thenReturn(new RefundQueryResult(
            paymentId, orderId, userId, orderProductId, 4_000, "PARTIAL_REFUNDED", "COMPLETED", refundedAt));
        GetPaymentUseCase paymentUseCase = Mockito.mock(GetPaymentUseCase.class);

        PaymentQueryServiceGrpc.PaymentQueryServiceBlockingStub stub = stubWith(refundUseCase, paymentUseCase);

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
    void GetRefund_PAYMENT_NOT_FOUND_예외_시_NOT_FOUND_status() throws Exception {
        GetRefundUseCase refundUseCase = Mockito.mock(GetRefundUseCase.class);
        when(refundUseCase.getRefund(any())).thenThrow(new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND));
        GetPaymentUseCase paymentUseCase = Mockito.mock(GetPaymentUseCase.class);

        PaymentQueryServiceGrpc.PaymentQueryServiceBlockingStub stub = stubWith(refundUseCase, paymentUseCase);

        assertThatThrownBy(() -> stub.getRefund(GetRefundRequest.newBuilder()
                .setPaymentId(UUID.randomUUID().toString())
                .setOrderProductId(UUID.randomUUID().toString())
                .build()))
            .isInstanceOf(StatusRuntimeException.class)
            .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
            .isEqualTo(Status.Code.NOT_FOUND);
    }

    @Test
    void GetRefund_REFUND_NOT_FOUND_예외_시_NOT_FOUND_status() throws Exception {
        GetRefundUseCase refundUseCase = Mockito.mock(GetRefundUseCase.class);
        when(refundUseCase.getRefund(any())).thenThrow(new BusinessException(PaymentErrorCode.REFUND_NOT_FOUND));
        GetPaymentUseCase paymentUseCase = Mockito.mock(GetPaymentUseCase.class);

        PaymentQueryServiceGrpc.PaymentQueryServiceBlockingStub stub = stubWith(refundUseCase, paymentUseCase);

        assertThatThrownBy(() -> stub.getRefund(GetRefundRequest.newBuilder()
                .setPaymentId(UUID.randomUUID().toString())
                .setOrderProductId(UUID.randomUUID().toString())
                .build()))
            .isInstanceOf(StatusRuntimeException.class)
            .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
            .isEqualTo(Status.Code.NOT_FOUND);
    }

    @Test
    void 정상_조회_시_GetPaymentResponse_반환() throws Exception {
        UUID paymentId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        OffsetDateTime approvedAt = OffsetDateTime.now();

        GetPaymentUseCase paymentUseCase = Mockito.mock(GetPaymentUseCase.class);
        when(paymentUseCase.getPayment(new GetPaymentCommand(orderId))).thenReturn(new PaymentQueryResult(
            paymentId, orderId, userId, "PAID", 10_000, approvedAt, null));
        GetRefundUseCase refundUseCase = Mockito.mock(GetRefundUseCase.class);

        PaymentQueryServiceGrpc.PaymentQueryServiceBlockingStub stub = stubWith(refundUseCase, paymentUseCase);

        GetPaymentResponse response = stub.getPayment(GetPaymentRequest.newBuilder()
            .setOrderId(orderId.toString())
            .build());

        assertThat(response.getPaymentId()).isEqualTo(paymentId.toString());
        assertThat(response.getOrderId()).isEqualTo(orderId.toString());
        assertThat(response.getUserId()).isEqualTo(userId.toString());
        assertThat(response.getStatus()).isEqualTo("PAID");
        assertThat(response.getAmount()).isEqualTo(10_000);
        assertThat(response.getApprovedAt()).isEqualTo(approvedAt.toString());
        assertThat(response.getFailedAt()).isEmpty();
    }

    @Test
    void GetPayment_PAYMENT_NOT_FOUND_예외_시_NOT_FOUND_status() throws Exception {
        GetPaymentUseCase paymentUseCase = Mockito.mock(GetPaymentUseCase.class);
        when(paymentUseCase.getPayment(any())).thenThrow(new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND));
        GetRefundUseCase refundUseCase = Mockito.mock(GetRefundUseCase.class);

        PaymentQueryServiceGrpc.PaymentQueryServiceBlockingStub stub = stubWith(refundUseCase, paymentUseCase);

        assertThatThrownBy(() -> stub.getPayment(GetPaymentRequest.newBuilder()
                .setOrderId(UUID.randomUUID().toString())
                .build()))
            .isInstanceOf(StatusRuntimeException.class)
            .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
            .isEqualTo(Status.Code.NOT_FOUND);
    }
}
```

- [ ] **Step 2: 테스트 실행해 실패 확인**

Run: `../gradlew :payment-service:test --tests "com.prompthub.paymentservice.infrastructure.grpc.PaymentQueryGrpcServiceTest"`
Expected: 컴파일 실패 — `PaymentQueryGrpcService(GetRefundUseCase, GetPaymentUseCase)` 생성자 없음, `stub.getPayment` 심볼 없음

- [ ] **Step 3: `PaymentQueryGrpcService`에 `getPayment` 추가**

`PaymentQueryGrpcService.java` 전체를 아래로 교체:

```java
package com.prompthub.paymentservice.infrastructure.grpc;

import com.prompthub.exception.BusinessException;
import com.prompthub.payment.grpc.GetPaymentRequest;
import com.prompthub.payment.grpc.GetPaymentResponse;
import com.prompthub.payment.grpc.GetRefundRequest;
import com.prompthub.payment.grpc.GetRefundResponse;
import com.prompthub.payment.grpc.PaymentQueryServiceGrpc;
import com.prompthub.paymentservice.application.dto.command.GetPaymentCommand;
import com.prompthub.paymentservice.application.dto.command.GetRefundCommand;
import com.prompthub.paymentservice.application.dto.result.PaymentQueryResult;
import com.prompthub.paymentservice.application.dto.result.RefundQueryResult;
import com.prompthub.paymentservice.application.usecase.GetPaymentUseCase;
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
    private final GetPaymentUseCase getPaymentUseCase;

    public PaymentQueryGrpcService(GetRefundUseCase getRefundUseCase, GetPaymentUseCase getPaymentUseCase) {
        this.getRefundUseCase = getRefundUseCase;
        this.getPaymentUseCase = getPaymentUseCase;
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

    @Override
    public void getPayment(GetPaymentRequest request, StreamObserver<GetPaymentResponse> responseObserver) {
        try {
            GetPaymentCommand command = new GetPaymentCommand(UUID.fromString(request.getOrderId()));

            PaymentQueryResult result = getPaymentUseCase.getPayment(command);

            GetPaymentResponse.Builder response = GetPaymentResponse.newBuilder()
                .setPaymentId(result.paymentId().toString())
                .setOrderId(result.orderId().toString())
                .setUserId(result.userId().toString())
                .setStatus(result.status());
            if (result.amount() != null) {
                response.setAmount(result.amount());
            }
            if (result.approvedAt() != null) {
                response.setApprovedAt(result.approvedAt().toString());
            }
            if (result.failedAt() != null) {
                response.setFailedAt(result.failedAt().toString());
            }
            responseObserver.onNext(response.build());
            responseObserver.onCompleted();
        } catch (BusinessException e) {
            log.warn("결제 조회 gRPC 실패 — orderId={}, code={}",
                request.getOrderId(), e.getErrorCode().getCode());
            Status status = e.getErrorCode().getStatus().is4xxClientError() ? Status.NOT_FOUND : Status.INTERNAL;
            responseObserver.onError(new StatusRuntimeException(status.withDescription(e.getMessage())));
        }
    }
}
```

- [ ] **Step 4: 테스트 실행해 통과 확인**

Run: `../gradlew :payment-service:test --tests "com.prompthub.paymentservice.infrastructure.grpc.PaymentQueryGrpcServiceTest"`
Expected: `BUILD SUCCESSFUL`, 5개 테스트 모두 PASS

- [ ] **Step 5: 전체 테스트 스위트 실행**

Run: `../gradlew :payment-service:test`
Expected: `BUILD SUCCESSFUL` — 이 작업으로 인한 회귀 없음 확인

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/prompthub/paymentservice/infrastructure/grpc/PaymentQueryGrpcService.java \
        src/test/java/com/prompthub/paymentservice/infrastructure/grpc/PaymentQueryGrpcServiceTest.java
git commit -m "$(cat <<'EOF'
feat: 결제 조회 GetPayment gRPC 서버 구현 추가

- PaymentQueryGrpcService에 getPayment 구현 (GetRefund와 동일하게 BusinessException→gRPC Status 매핑)
- 생성자에 GetPaymentUseCase 추가 주입(GetRefundUseCase와 함께)
- PaymentQueryGrpcServiceTest: stubWith 헬퍼 2-인자로 확장, GetPayment 정상/PAYMENT_NOT_FOUND 케이스 추가

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: 문서 갱신

**Files:**
- Modify: `../docs/architecture/overview.md`
- Modify: `src/main/../../.claude/docs/events.md` (실제 경로: `../../docs/events.md`)

**Interfaces:**
- Consumes: 없음(문서 전용, 이전 태스크 산출물을 서술만 함).
- Produces: 없음(마지막 태스크).

- [ ] **Step 1: `../docs/architecture/overview.md` gRPC 통신 표 갱신**

`### 2) 내부 동기 통신 (gRPC)` 표에서 아래 행:

```
| payment → order | 9083 | 주문 결제정보 폴백 조회(스냅샷 미확보 시) | `payment-service/.../infrastructure/external/grpc/OrderGrpcClientConfig.java` (**order 측 서버 예정**) |
| order → payment | 9084 | 환불 이벤트 폴백 조회(Kafka 유실 시) | `payment-service/.../infrastructure/grpc/PaymentQueryGrpcService.java` |
```

두 번째 행을 아래로 교체:

```
| order → payment | 9084 | 환불/결제 승인·실패 이벤트 폴백 조회(Kafka 유실 시) | `payment-service/.../infrastructure/grpc/PaymentQueryGrpcService.java` |
```

- [ ] **Step 2: `../../docs/events.md` 갱신**

`### PAYMENT_FAILED` 섹션의 `구독자(order-service) 반응: PENDING → FAILED (재결제 시 FAILED → PAID 복귀 허용 필요).` 줄 바로 뒤에 한 줄 추가:

```
> **Kafka 유실 시 폴백**: order-service가 `PAYMENT_APPROVED`/`PAYMENT_FAILED`를 못 받았을 경우, `PaymentQueryService.GetPayment` gRPC(`grpc/payment/payment_query.proto`, 포트 9084)로 폴백 조회할 수 있다. 조회 키는 `orderId`(동일 orderId에 재결제로 여러 건이 있으면 최신 1건 반환).
```

- [ ] **Step 3: 빌드로 최종 확인**

Run: `../gradlew :payment-service:build`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add ../docs/architecture/overview.md .claude/docs/events.md
git commit -m "$(cat <<'EOF'
docs: GetPayment gRPC 폴백 조회 경로 문서 반영

- overview.md: order→payment gRPC 통신 표에 결제 승인/실패 이벤트 폴백 조회 용도 추가
- events.md: PAYMENT_FAILED 섹션에 GetPayment 폴백 조회 안내(조회 키·최신건 반환 규칙) 추가

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```
