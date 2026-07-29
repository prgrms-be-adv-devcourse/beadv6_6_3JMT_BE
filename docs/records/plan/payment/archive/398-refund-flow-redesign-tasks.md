# 환불 흐름 개선(refundRequestId 기반 재환불 허용) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 환불 dedup 키를 `(payment_id, order_product_id)`에서 `refundRequestId`로 전환해 동일 상품 재환불을 허용하고, 과환불 실패를 예외/DLT가 아닌 정상 이벤트 흐름으로 바꾸며, `payment-events` 페이로드와 GetRefund gRPC 폴백을 정리한다.

**Architecture:** 클린 아키텍처 레이어 구조(domain/application/infrastructure) 그대로 유지. `Refund` 도메인 엔티티와 `refund` 테이블 스키마를 변경하고, 그 위에서 `ProcessRefundService`의 흐름(생성 시점, 실패 처리 방식)을 바꾼다. `GetRefundUseCase`/gRPC 폴백 조회는 완전히 제거한다.

**Tech Stack:** Spring Boot 4.1, Java 21, Spring Data JPA, Flyway, Spring Kafka, gRPC(`spring-grpc`), Testcontainers(PostgreSQL + Kafka), JUnit5 + AssertJ + Mockito.

## Global Constraints

- 언어 정책: 커밋 메시지/코드 주석/테스트 메서드명은 한국어, 클래스/필드/메서드 식별자와 커밋 타입 접두사는 영어.
- Checkstyle 위반 코드 작성 금지(`ignoreFailures=true`지만 위반하지 않는다).
- 통합/영속성 테스트는 Testcontainers(PostgreSQL), Kafka도 Testcontainers(`confluentinc/cp-kafka:7.6.1`) 사용. H2/EmbeddedKafka 금지.
- 단언은 AssertJ(`assertThat`). 통합 테스트는 루트 패키지(`com.prompthub.paymentservice`)에 위치.
- `@Entity`에 반영하는 제약(NOT NULL/UNIQUE)은 Flyway `V{n}` SQL에도 동일하게 반영한다(둘 중 하나만 하면 안 됨).
- 이미 배포된 마이그레이션 파일(V1~V3)은 수정하지 않는다. 다음 버전은 V4.
- 커밋 메시지 형식: `type: 한국어 설명`, AI 협업 시 본문 마지막에 `Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>` 트레일러 추가. `#이슈번호`는 커밋 본문에 쓰지 않는다.
- 각 Task는 하나의 커밋 단위다 — Task 내부를 여러 커밋으로 쪼개지 않는다.
- 설계 근거: `.claude/plans/398-refund-flow-redesign.md` (이 태스크 목록의 상위 설계 문서).

---

### Task 1: GetRefund gRPC 폴백 조회 완전 제거

**배경**: `GetRefundUseCase`/`GetRefundService`는 order-service가 Kafka 이벤트를 못 받았을 때 쓰던 동기 폴백 조회였다. order-service가 Kafka 자체 재조회로 커버 가능하다는 판단에 따라 제거하기로 확정했다(설계 문서 확정사항 6). 조사 결과 `order-service`/`settlement-service` 어디에도 `GetRefundRequest`/`GetRefundResponse`/`PaymentQueryServiceGrpc` 실사용 코드가 없어(죽은 엔드포인트) 안전하게 제거 가능하다.

**Files:**
- Modify: `../grpc/payment/payment_query.proto` (모노레포 루트, payment-service 밖 — 공유 proto 계약)
- Modify: `src/main/java/com/prompthub/paymentservice/infrastructure/grpc/PaymentQueryGrpcService.java`
- Modify: `src/test/java/com/prompthub/paymentservice/infrastructure/grpc/PaymentQueryGrpcServiceTest.java`
- Modify: `src/main/java/com/prompthub/paymentservice/application/exception/PaymentErrorCode.java`
- Delete: `src/main/java/com/prompthub/paymentservice/application/usecase/GetRefundUseCase.java`
- Delete: `src/main/java/com/prompthub/paymentservice/application/service/GetRefundService.java`
- Delete: `src/main/java/com/prompthub/paymentservice/application/dto/command/GetRefundCommand.java`
- Delete: `src/main/java/com/prompthub/paymentservice/application/dto/result/RefundQueryResult.java`
- Delete: `src/test/java/com/prompthub/paymentservice/application/service/GetRefundServiceTest.java`

**Interfaces:**
- Produces: `PaymentQueryGrpcService(GetPaymentUseCase getPaymentUseCase)` — 단일 인자 생성자로 변경(이후 태스크에서 이 클래스를 참조하는 곳 없음).

- [ ] **Step 1: 공유 proto에서 GetRefund 제거**

`../grpc/payment/payment_query.proto` 전체를 아래 내용으로 교체:

```proto
syntax = "proto3";

package prompthub.payment;

option java_multiple_files = true;
option java_package = "com.prompthub.payment.grpc";
option java_outer_classname = "PaymentQueryProto";

service PaymentQueryService {
  rpc GetPayment(GetPaymentRequest) returns (GetPaymentResponse);
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

- [ ] **Step 2: `PaymentQueryGrpcService`에서 getRefund 제거**

`src/main/java/com/prompthub/paymentservice/infrastructure/grpc/PaymentQueryGrpcService.java` 전체를 아래로 교체:

```java
package com.prompthub.paymentservice.infrastructure.grpc;

import com.prompthub.exception.BusinessException;
import com.prompthub.payment.grpc.GetPaymentRequest;
import com.prompthub.payment.grpc.GetPaymentResponse;
import com.prompthub.payment.grpc.PaymentQueryServiceGrpc;
import com.prompthub.paymentservice.application.dto.command.GetPaymentCommand;
import com.prompthub.paymentservice.application.dto.result.PaymentQueryResult;
import com.prompthub.paymentservice.application.usecase.GetPaymentUseCase;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.grpc.server.service.GrpcService;

@Slf4j
@GrpcService
public class PaymentQueryGrpcService extends PaymentQueryServiceGrpc.PaymentQueryServiceImplBase {

    private final GetPaymentUseCase getPaymentUseCase;

    public PaymentQueryGrpcService(GetPaymentUseCase getPaymentUseCase) {
        this.getPaymentUseCase = getPaymentUseCase;
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

- [ ] **Step 3: `PaymentQueryGrpcServiceTest`에서 GetRefund 관련 테스트 제거**

`src/test/java/com/prompthub/paymentservice/infrastructure/grpc/PaymentQueryGrpcServiceTest.java` 전체를 아래로 교체:

```java
package com.prompthub.paymentservice.infrastructure.grpc;

import com.prompthub.exception.BusinessException;
import com.prompthub.paymentservice.application.dto.command.GetPaymentCommand;
import com.prompthub.paymentservice.application.dto.result.PaymentQueryResult;
import com.prompthub.paymentservice.application.exception.PaymentErrorCode;
import com.prompthub.paymentservice.application.usecase.GetPaymentUseCase;
import com.prompthub.payment.grpc.GetPaymentRequest;
import com.prompthub.payment.grpc.GetPaymentResponse;
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
        GetPaymentUseCase paymentUseCase
    ) throws Exception {
        String serverName = UUID.randomUUID().toString();
        server = InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(new PaymentQueryGrpcService(paymentUseCase))
            .build()
            .start();
        channel = InProcessChannelBuilder.forName(serverName)
            .directExecutor()
            .build();
        return PaymentQueryServiceGrpc.newBlockingStub(channel);
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

        PaymentQueryServiceGrpc.PaymentQueryServiceBlockingStub stub = stubWith(paymentUseCase);

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

        PaymentQueryServiceGrpc.PaymentQueryServiceBlockingStub stub = stubWith(paymentUseCase);

        assertThatThrownBy(() -> stub.getPayment(GetPaymentRequest.newBuilder()
                .setOrderId(UUID.randomUUID().toString())
                .build()))
            .isInstanceOf(StatusRuntimeException.class)
            .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
            .isEqualTo(Status.Code.NOT_FOUND);
    }
}
```

- [ ] **Step 4: 죽는 클래스 삭제**

다음 파일을 삭제한다:
- `src/main/java/com/prompthub/paymentservice/application/usecase/GetRefundUseCase.java`
- `src/main/java/com/prompthub/paymentservice/application/service/GetRefundService.java`
- `src/main/java/com/prompthub/paymentservice/application/dto/command/GetRefundCommand.java`
- `src/main/java/com/prompthub/paymentservice/application/dto/result/RefundQueryResult.java`
- `src/test/java/com/prompthub/paymentservice/application/service/GetRefundServiceTest.java`

Run: `find src -iname "GetRefund*"` → 출력 없어야 함(단, `GetRefundRequest`/`GetRefundResponse`는 gradle protobuf 플러그인이 생성하던 클래스라 소스에 없다 — proto에서 지웠으니 재빌드 시 자동으로 생성 안 됨).

- [ ] **Step 5: `PaymentErrorCode`에서 `REFUND_NOT_FOUND` 제거**

`src/main/java/com/prompthub/paymentservice/application/exception/PaymentErrorCode.java`에서 `REFUND_NOT_FOUND` 상수 삭제(유일한 사용처가 방금 삭제한 `GetRefundService`였음):

```java
package com.prompthub.paymentservice.application.exception;

import com.prompthub.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum PaymentErrorCode implements ErrorCode {

    INVALID_INPUT(HttpStatus.BAD_REQUEST, "V001", "입력값이 올바르지 않습니다."),
    DUPLICATE_PAYMENT(HttpStatus.CONFLICT, "PAY002", "이미 결제된 주문입니다."),
    AMOUNT_MISMATCH(HttpStatus.BAD_REQUEST, "PAY012", "결제 금액이 주문 금액과 일치하지 않습니다."),
    PG_INVALID_REQUEST(HttpStatus.BAD_GATEWAY, "PAY003", "잘못된 API 요청으로 인한 PG사 오류입니다."),
    PG_SERVER_ERROR(HttpStatus.BAD_GATEWAY, "PAY_PG_5XX", "PG사 서버 오류가 발생했습니다."),
    PAYMENT_FAILED(HttpStatus.UNPROCESSABLE_ENTITY, "PAY_FAILED", "PG사 결제가 실패했습니다."),
    REFUND_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "PAY004", "환불 가능한 상태가 아닙니다."),
    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "PAY005", "결제 건을 찾을 수 없습니다."),
    UNAUTHORIZED_REFUND(HttpStatus.FORBIDDEN, "PAY006", "본인 결제 건만 환불할 수 있습니다."),
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "PAY008", "주문 정보를 찾을 수 없습니다."),
    ORDER_INFO_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "PAY009", "주문 정보를 확보할 수 없습니다."),
    NOT_ORDER_OWNER(HttpStatus.FORBIDDEN, "PAY010", "본인 주문만 결제할 수 있습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
```

- [ ] **Step 6: 빌드 + 테스트 실행**

Run: `../gradlew :payment-service:build`
Expected: BUILD SUCCESSFUL (protobuf 재생성 포함, GetRefund 관련 컴파일 에러 없음)

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
refactor: GetRefund gRPC 폴백 조회 제거

- order-service/settlement-service 어디서도 호출하지 않는 죽은 엔드포인트 확인 후 제거
- GetRefundUseCase/GetRefundService/GetRefundCommand/RefundQueryResult 삭제
- 공유 proto(payment_query.proto)에서 GetRefund rpc/메시지 제거
- 유일한 사용처였던 PaymentErrorCode.REFUND_NOT_FOUND 삭제

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: refundRequestId 기반 dedup + 재환불 허용 + 과환불 정상 실패 처리

**배경**: dedup 키를 `(payment_id, order_product_id)`에서 `refundRequestId`로 전환한다. `Refund` 생성 시점을 과환불 검증 이전으로 앞당겨 과환불 거부도 감사 기록(FAILED row)으로 남기고, 예외/DLT가 아닌 정상 이벤트 발행으로 처리한다. 죽은 필드였던 `order_product_id`/`user_id` 컬럼도 함께 제거한다. 아웃바운드 `payment-events` 페이로드를 `orderId`+금액+시각만 남기도록 축소한다.

**Files:**
- Create: `src/main/resources/db/migration/V4__refund_request_id_dedup.sql`
- Modify: `src/main/java/com/prompthub/paymentservice/domain/model/Refund.java`
- Modify: `src/main/java/com/prompthub/paymentservice/domain/repository/RefundRepository.java`
- Modify: `src/main/java/com/prompthub/paymentservice/infrastructure/persistence/RefundJpaRepository.java`
- Modify: `src/main/java/com/prompthub/paymentservice/infrastructure/persistence/RefundRepositoryAdapter.java`
- Modify: `src/main/java/com/prompthub/paymentservice/application/dto/command/ProcessRefundCommand.java`
- Modify: `src/main/java/com/prompthub/paymentservice/application/usecase/ProcessRefundUseCase.java`
- Modify: `src/main/java/com/prompthub/paymentservice/infrastructure/messaging/dto/OrderRefundRequestedMessage.java`
- Modify: `src/main/java/com/prompthub/paymentservice/infrastructure/messaging/consumer/OrderEventConsumer.java`
- Modify: `src/main/java/com/prompthub/paymentservice/application/service/ProcessRefundService.java`
- Modify: `src/main/java/com/prompthub/paymentservice/infrastructure/messaging/dto/PaymentRefundedMessage.java`
- Modify: `src/main/java/com/prompthub/paymentservice/infrastructure/messaging/dto/PaymentRefundFailedMessage.java`
- Modify: `src/main/java/com/prompthub/paymentservice/infrastructure/messaging/KafkaPaymentEventPublisher.java`
- Modify: `src/test/java/com/prompthub/paymentservice/infrastructure/persistence/RefundJpaRepositoryTest.java`
- Modify: `src/test/java/com/prompthub/paymentservice/application/service/ProcessRefundServiceTest.java`
- Modify: `src/test/java/com/prompthub/paymentservice/infrastructure/messaging/KafkaPaymentEventPublisherTest.java`
- Modify: `src/test/java/com/prompthub/paymentservice/PartialRefundIntegrationTest.java`

**Interfaces:**
- Produces: `Refund.create(UUID paymentId, UUID refundRequestId, int refundAmount, String reason)`, `refund.fail(String reason)`(기존 무인자 `fail()`에서 변경), `RefundRepository.existsByRefundRequestId(UUID)`, `ProcessRefundCommand(UUID orderId, UUID refundRequestId, int refundAmount, OffsetDateTime requestedAt)`.
- Consumes: Task 1에서 정리된 `PaymentErrorCode`(`PAYMENT_NOT_FOUND` 등 기존 항목 그대로 사용), `PaymentGateway.refund(String pgTxId, UUID refundId, int amount)`(변경 없음), `Payment.applyRefund(OffsetDateTime, boolean)`(변경 없음).

- [ ] **Step 1: Flyway V4 마이그레이션 작성**

`src/main/resources/db/migration/V4__refund_request_id_dedup.sql` 신규 생성:

```sql
-- 환불 dedup 키를 (payment_id, order_product_id)에서 refundRequestId로 전환.
-- 동일 order_product에 대한 재환불(복수 부분환불)을 허용하고, 죽은 컬럼(order_product_id, user_id)을 정리한다(#398).

ALTER TABLE refund ADD COLUMN refund_request_id uuid;
UPDATE refund SET refund_request_id = gen_random_uuid() WHERE refund_request_id IS NULL;
ALTER TABLE refund ALTER COLUMN refund_request_id SET NOT NULL;
CREATE UNIQUE INDEX uk_refund_request_id ON refund (refund_request_id);

DROP INDEX IF EXISTS uk_refund_payment_order_product;
ALTER TABLE refund DROP COLUMN order_product_id;
ALTER TABLE refund DROP COLUMN user_id;
```

- [ ] **Step 2: `Refund` 엔티티 변경**

`src/main/java/com/prompthub/paymentservice/domain/model/Refund.java` 전체를 아래로 교체:

```java
package com.prompthub.paymentservice.domain.model;

import com.prompthub.paymentservice.domain.exception.InvalidRefundStateException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import static jakarta.persistence.EnumType.STRING;
import static lombok.AccessLevel.PROTECTED;

@Slf4j
@Getter
@Entity
@Table(name = "refund", uniqueConstraints =
    @UniqueConstraint(name = "uk_refund_request_id", columnNames = {"refund_request_id"}))
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = PROTECTED)
public class Refund {

    @Id
    @Column(name = "id", columnDefinition = "uuid")
    private UUID id;

    @Column(name = "payment_id", columnDefinition = "uuid", nullable = false)
    private UUID paymentId;

    @Column(name = "refund_request_id", columnDefinition = "uuid", nullable = false)
    private UUID refundRequestId;

    @Column(name = "refund_amount", nullable = false)
    private int refundAmount;

    @Column(name = "reason", columnDefinition = "text")
    private String reason;

    @Enumerated(STRING)
    @Column(name = "status", columnDefinition = "varchar(20)", nullable = false)
    private RefundStatus status;

    @Column(name = "requested_at", nullable = false)
    private OffsetDateTime requestedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @CreatedDate
    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    private Refund(
        UUID id, UUID paymentId, UUID refundRequestId,
        int refundAmount, String reason,
        RefundStatus status, OffsetDateTime requestedAt, OffsetDateTime completedAt
    ) {
        this.id = id;
        this.paymentId = paymentId;
        this.refundRequestId = refundRequestId;
        this.refundAmount = refundAmount;
        this.reason = reason;
        this.status = status;
        this.requestedAt = requestedAt;
        this.completedAt = completedAt;
    }

    public static Refund create(
        UUID paymentId, UUID refundRequestId,
        int refundAmount, String reason
    ) {
        return new Refund(
            UUID.randomUUID(), paymentId, refundRequestId,
            refundAmount, reason,
            RefundStatus.REQUESTED,
            OffsetDateTime.now(),
            null
        );
    }

    public void complete(OffsetDateTime completedAt) {
        if (this.status != RefundStatus.REQUESTED) {
            throw new InvalidRefundStateException("REQUESTED 상태에서만 COMPLETED로 전환할 수 있습니다.");
        }
        log.debug("Refund 상태 전이 — id={}, {} → COMPLETED", id, status);
        this.status = RefundStatus.COMPLETED;
        this.completedAt = completedAt;
    }

    public void fail(String reason) {
        if (this.status != RefundStatus.REQUESTED) {
            throw new InvalidRefundStateException("REQUESTED 상태에서만 FAILED로 전환할 수 있습니다.");
        }
        log.debug("Refund 상태 전이 — id={}, {} → FAILED", id, status);
        this.status = RefundStatus.FAILED;
        this.reason = reason;
    }
}
```

- [ ] **Step 3: `RefundRepository`/`RefundJpaRepository`/`RefundRepositoryAdapter` 변경**

`src/main/java/com/prompthub/paymentservice/domain/repository/RefundRepository.java`:

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
    boolean existsByRefundRequestId(UUID refundRequestId);
}
```

`src/main/java/com/prompthub/paymentservice/infrastructure/persistence/RefundJpaRepository.java`:

```java
package com.prompthub.paymentservice.infrastructure.persistence;

import com.prompthub.paymentservice.domain.model.Refund;
import com.prompthub.paymentservice.domain.model.RefundStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundJpaRepository extends JpaRepository<Refund, UUID> {
    List<Refund> findByPaymentIdAndStatus(UUID paymentId, RefundStatus status);
    boolean existsByRefundRequestId(UUID refundRequestId);
}
```

`src/main/java/com/prompthub/paymentservice/infrastructure/persistence/RefundRepositoryAdapter.java`:

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
    public boolean existsByRefundRequestId(UUID refundRequestId) {
        return jpaRepository.existsByRefundRequestId(refundRequestId);
    }
}
```

- [ ] **Step 4: `RefundJpaRepositoryTest` 갱신**

`src/test/java/com/prompthub/paymentservice/infrastructure/persistence/RefundJpaRepositoryTest.java` 전체를 아래로 교체:

```java
package com.prompthub.paymentservice.infrastructure.persistence;

import com.prompthub.paymentservice.domain.model.Refund;
import com.prompthub.paymentservice.domain.model.RefundStatus;
import com.prompthub.paymentservice.support.AbstractJpaTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefundJpaRepositoryTest extends AbstractJpaTest {

    @Autowired
    RefundJpaRepository refundJpaRepository;

    @Test
    void refund_save_findById_round_trip() {
        UUID paymentId = UUID.randomUUID();
        UUID refundRequestId = UUID.randomUUID();

        Refund refund = Refund.create(paymentId, refundRequestId, 5_000, "단순 변심");

        Refund saved = refundJpaRepository.saveAndFlush(refund);

        Refund found = refundJpaRepository.findById(saved.getId())
            .orElseThrow(() -> new AssertionError("Refund not found"));

        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getPaymentId()).isEqualTo(paymentId);
        assertThat(found.getRefundRequestId()).isEqualTo(refundRequestId);
        assertThat(found.getRefundAmount()).isEqualTo(5_000);
        assertThat(found.getStatus()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(found.getRequestedAt()).isNotNull();
        assertThat(found.getCompletedAt()).isNull();
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }

    @Test
    void 같은_refundRequestId_중복_저장_시_유니크_제약_위반() {
        UUID paymentId = UUID.randomUUID();
        UUID refundRequestId = UUID.randomUUID();

        Refund first = Refund.create(paymentId, refundRequestId, 5_000, null);
        refundJpaRepository.saveAndFlush(first);

        Refund duplicate = Refund.create(paymentId, refundRequestId, 3_000, null);

        assertThatThrownBy(() -> refundJpaRepository.saveAndFlush(duplicate))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void 같은_결제_같은_상품도_다른_refundRequestId면_재환불_허용() {
        UUID paymentId = UUID.randomUUID();

        Refund first = Refund.create(paymentId, UUID.randomUUID(), 5_000, null);
        Refund second = Refund.create(paymentId, UUID.randomUUID(), 3_000, null);

        refundJpaRepository.saveAndFlush(first);
        refundJpaRepository.saveAndFlush(second);

        assertThat(refundJpaRepository.findById(first.getId())).isPresent();
        assertThat(refundJpaRepository.findById(second.getId())).isPresent();
    }

    @Test
    void findByPaymentIdAndStatus_COMPLETED_건만_조회() {
        UUID paymentId = UUID.randomUUID();
        Refund completed = Refund.create(paymentId, UUID.randomUUID(), 3_000, null);
        completed.complete(java.time.OffsetDateTime.now());
        Refund requested = Refund.create(paymentId, UUID.randomUUID(), 2_000, null);
        refundJpaRepository.saveAndFlush(completed);
        refundJpaRepository.saveAndFlush(requested);

        java.util.List<Refund> found = refundJpaRepository.findByPaymentIdAndStatus(paymentId, RefundStatus.COMPLETED);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).getRefundAmount()).isEqualTo(3_000);
    }

    @Test
    void existsByRefundRequestId_존재하면_true() {
        UUID refundRequestId = UUID.randomUUID();
        Refund refund = Refund.create(UUID.randomUUID(), refundRequestId, 4_000, null);
        refundJpaRepository.saveAndFlush(refund);

        assertThat(refundJpaRepository.existsByRefundRequestId(refundRequestId)).isTrue();
    }

    @Test
    void existsByRefundRequestId_없으면_false() {
        assertThat(refundJpaRepository.existsByRefundRequestId(UUID.randomUUID())).isFalse();
    }
}
```

Run: `../gradlew :payment-service:test --tests "com.prompthub.paymentservice.infrastructure.persistence.RefundJpaRepositoryTest"`
Expected: PASS (Hibernate `ddl-auto: create`가 변경된 `Refund` 엔티티 기준으로 스키마를 새로 만들기 때문에 이 시점에 이미 통과해야 함)

- [ ] **Step 5: `ProcessRefundCommand`/`ProcessRefundUseCase` 변경**

`src/main/java/com/prompthub/paymentservice/application/dto/command/ProcessRefundCommand.java`:

```java
package com.prompthub.paymentservice.application.dto.command;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ProcessRefundCommand(
    UUID orderId,
    UUID refundRequestId,
    int refundAmount,
    OffsetDateTime requestedAt
) {}
```

`src/main/java/com/prompthub/paymentservice/application/usecase/ProcessRefundUseCase.java`:

```java
package com.prompthub.paymentservice.application.usecase;

import com.prompthub.paymentservice.application.dto.command.ProcessRefundCommand;

/**
 * order-service가 발행하는 ORDER_REFUND_REQUESTED 이벤트로 트리거되는 환불 처리.
 * refundRequestId 단위로 dedup하며, PG 호출까지 단일 트랜잭션 안에서 동기로 수행한다.
 */
public interface ProcessRefundUseCase {
    void process(ProcessRefundCommand command);
}
```

- [ ] **Step 6: `OrderRefundRequestedMessage`/`OrderEventConsumer` 변경**

`src/main/java/com/prompthub/paymentservice/infrastructure/messaging/dto/OrderRefundRequestedMessage.java`:

```java
package com.prompthub.paymentservice.infrastructure.messaging.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * order-events의 ORDER_REFUND_REQUESTED payload(EventMessage&lt;T&gt; 봉투 내부).
 * requestedAt은 order-service에서 존 없는 LocalDateTime으로 직렬화되므로, 소비 시 KST를 부여한다.
 * orderProductId는 order-service가 계속 함께 보내지만 payment-service 내부에서 쓰지 않는다(#398) —
 * 역직렬화 호환을 위해 필드는 남겨둔다. buyerId는 애초에 쓰인 적 없어 필드 자체를 제거했다(들어와도 파싱하지 않고 무시된다).
 */
public record OrderRefundRequestedMessage(
    UUID orderId,
    UUID orderProductId,
    UUID refundRequestId,
    int refundAmount,
    LocalDateTime requestedAt
) {}
```

`src/main/java/com/prompthub/paymentservice/infrastructure/messaging/consumer/OrderEventConsumer.java`:

```java
package com.prompthub.paymentservice.infrastructure.messaging.consumer;

import com.prompthub.paymentservice.application.dto.command.ProcessRefundCommand;
import com.prompthub.paymentservice.application.usecase.ProcessRefundUseCase;
import com.prompthub.paymentservice.infrastructure.messaging.dto.OrderRefundRequestedMessage;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * order-events 구독. 공통 이벤트 규칙(EventMessage&lt;T&gt; 봉투)의 최상위 eventType으로 필터링한다.
 * ORDER_REFUND_REQUESTED만 처리하고, 그 외 eventType(ORDER_CREATED/ORDER_PAID 등)은 무시한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventConsumer {

    private static final String TOPIC_ORDER_EVENTS = "order-events";
    private static final String GROUP_ID = "payment-service-order-events";
    private static final String EVENT_TYPE_ORDER_REFUND_REQUESTED = "ORDER_REFUND_REQUESTED";

    // requestedAt(LocalDateTime, 존 없음)에 부여할 존 — payment의 KST 표기 관례와 일치
    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    private final ObjectMapper objectMapper;
    private final ProcessRefundUseCase processRefundUseCase;

    @KafkaListener(
        topics = TOPIC_ORDER_EVENTS,
        groupId = GROUP_ID,
        containerFactory = "orderEventKafkaListenerContainerFactory"
    )
    public void consume(String message, Acknowledgment acknowledgment) {
        try {
            JsonNode root = objectMapper.readTree(message);
            String eventType = root.path("eventType").stringValue(null);
            JsonNode payload = root.path("payload");

            if (EVENT_TYPE_ORDER_REFUND_REQUESTED.equals(eventType)) {
                handleOrderRefundRequested(payload);
            } else {
                log.debug("처리 대상이 아닌 order-events 메시지 무시 — eventType={}", eventType);
            }
            acknowledgment.acknowledge();
        } catch (Exception e) {
            log.error("order-events 메시지 처리 실패: {}", e.getMessage(), e);
            throw e; // DefaultErrorHandler → FixedBackOff 재시도 → order-events.DLT
        }
    }

    private void handleOrderRefundRequested(JsonNode payload) {
        OrderRefundRequestedMessage requested = objectMapper.treeToValue(payload, OrderRefundRequestedMessage.class);
        validateRefund(requested);

        processRefundUseCase.process(new ProcessRefundCommand(
            requested.orderId(),
            requested.refundRequestId(),
            requested.refundAmount(),
            requested.requestedAt().atOffset(KST)
        ));
        log.info("환불 처리 완료 — orderId={}, refundRequestId={}", requested.orderId(), requested.refundRequestId());
    }

    private void validateRefund(OrderRefundRequestedMessage message) {
        if (message.orderId() == null || message.refundRequestId() == null || message.requestedAt() == null) {
            throw new IllegalArgumentException("ORDER_REFUND_REQUESTED 필수 필드 누락: " + message);
        }
    }
}
```

- [ ] **Step 7: `ProcessRefundServiceTest`에 새 동작 기대하는 실패 테스트 먼저 작성**

`src/test/java/com/prompthub/paymentservice/application/service/ProcessRefundServiceTest.java` 전체를 아래로 교체(기존 시그니처 호출부 갱신 + dedup/과환불-정상실패 테스트 추가):

```java
package com.prompthub.paymentservice.application.service;

import com.prompthub.paymentservice.application.dto.command.ProcessRefundCommand;
import com.prompthub.paymentservice.application.gateway.external.PaymentGateway;
import com.prompthub.paymentservice.application.gateway.external.PaymentGatewayException;
import com.prompthub.paymentservice.application.gateway.external.RefundResult;
import com.prompthub.paymentservice.application.exception.PaymentErrorCode;
import com.prompthub.paymentservice.domain.event.PaymentRefundFailedEvent;
import com.prompthub.paymentservice.domain.event.PaymentRefundedEvent;
import com.prompthub.paymentservice.domain.model.Payment;
import com.prompthub.paymentservice.domain.model.PaymentStatus;
import com.prompthub.paymentservice.domain.model.Refund;
import com.prompthub.paymentservice.domain.model.RefundStatus;
import com.prompthub.paymentservice.domain.repository.PaymentRepository;
import com.prompthub.paymentservice.domain.repository.RefundRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProcessRefundServiceTest {

    @Mock
    PaymentRepository paymentRepository;
    @Mock
    RefundRepository refundRepository;
    @Mock
    PaymentGateway paymentGateway;
    @Mock
    ApplicationEventPublisher applicationEventPublisher;

    ProcessRefundService service;

    @BeforeEach
    void setUp() {
        service = new ProcessRefundService(paymentRepository, refundRepository, paymentGateway, applicationEventPublisher);
    }

    @Test
    void 이미_처리된_refundRequestId면_정상_종료하고_아무것도_안_한다() {
        UUID refundRequestId = UUID.randomUUID();
        when(refundRepository.existsByRefundRequestId(refundRequestId)).thenReturn(true);

        ProcessRefundCommand command = new ProcessRefundCommand(
            UUID.randomUUID(), refundRequestId, 3_000, OffsetDateTime.now());

        service.process(command);

        verify(paymentRepository, never()).findByOrderIdAndStatusInForUpdate(any(), any());
        verify(refundRepository, never()).save(any());
        verify(applicationEventPublisher, never()).publishEvent(any());
    }

    @Test
    void 결제_건_없으면_예외() {
        UUID orderId = UUID.randomUUID();
        when(refundRepository.existsByRefundRequestId(any())).thenReturn(false);
        when(paymentRepository.findByOrderIdAndStatusInForUpdate(any(), any())).thenReturn(Optional.empty());

        ProcessRefundCommand command = new ProcessRefundCommand(
            orderId, UUID.randomUUID(), 3_000, OffsetDateTime.now());

        assertThatThrownBy(() -> service.process(command))
            .isInstanceOf(com.prompthub.exception.BusinessException.class)
            .extracting(e -> ((com.prompthub.exception.BusinessException) e).getErrorCode())
            .isEqualTo(PaymentErrorCode.PAYMENT_NOT_FOUND);

        verify(applicationEventPublisher, never()).publishEvent(any());
    }

    @Test
    void 누적_환불액_초과_시_예외_없이_FAILED_row_생성_및_실패_이벤트_발행() {
        Payment payment = 결제_생성_후_승인(10_000);
        when(refundRepository.existsByRefundRequestId(any())).thenReturn(false);
        when(paymentRepository.findByOrderIdAndStatusInForUpdate(any(), any())).thenReturn(Optional.of(payment));
        when(refundRepository.findByPaymentIdAndStatus(payment.getId(), RefundStatus.COMPLETED))
            .thenReturn(List.of(기존_완료_환불(payment.getId(), 8_000)));
        when(refundRepository.save(any(Refund.class))).thenAnswer(inv -> inv.getArgument(0));

        ProcessRefundCommand command = new ProcessRefundCommand(
            payment.getOrderId(), UUID.randomUUID(), 3_000, OffsetDateTime.now());

        service.process(command);

        verify(paymentGateway, never()).refund(anyString(), any(), anyInt());

        ArgumentCaptor<Refund> refundCaptor = ArgumentCaptor.forClass(Refund.class);
        verify(refundRepository).save(refundCaptor.capture());
        assertThat(refundCaptor.getValue().getStatus()).isEqualTo(RefundStatus.FAILED);
        assertThat(refundCaptor.getValue().getReason()).isNotBlank();

        ArgumentCaptor<PaymentRefundFailedEvent> eventCaptor = ArgumentCaptor.forClass(PaymentRefundFailedEvent.class);
        verify(applicationEventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().payment().getId()).isEqualTo(payment.getId());
    }

    @Test
    void 부분_환불_성공_시_PARTIAL_REFUNDED_전이() {
        Payment payment = 결제_생성_후_승인(10_000);
        UUID refundRequestId = UUID.randomUUID();
        OffsetDateTime refundedAt = OffsetDateTime.now();
        when(refundRepository.existsByRefundRequestId(refundRequestId)).thenReturn(false);
        when(paymentRepository.findByOrderIdAndStatusInForUpdate(any(), any())).thenReturn(Optional.of(payment));
        when(refundRepository.findByPaymentIdAndStatus(payment.getId(), RefundStatus.COMPLETED))
            .thenReturn(List.of());
        when(refundRepository.save(any(Refund.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentGateway.refund(anyString(), any(), anyInt())).thenReturn(new RefundResult(refundedAt));

        ProcessRefundCommand command = new ProcessRefundCommand(
            payment.getOrderId(), refundRequestId, 4_000, OffsetDateTime.now());
        service.process(command);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PARTIAL_REFUNDED);

        ArgumentCaptor<Refund> refundCaptor = ArgumentCaptor.forClass(Refund.class);
        verify(refundRepository).save(refundCaptor.capture());
        assertThat(refundCaptor.getValue().getRefundAmount()).isEqualTo(4_000);
        assertThat(refundCaptor.getValue().getRefundRequestId()).isEqualTo(refundRequestId);
        assertThat(refundCaptor.getValue().getStatus()).isEqualTo(RefundStatus.COMPLETED);

        ArgumentCaptor<PaymentRefundedEvent> eventCaptor = ArgumentCaptor.forClass(PaymentRefundedEvent.class);
        verify(applicationEventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().payment().getId()).isEqualTo(payment.getId());
    }

    @Test
    void 누적_환불액_totalAmount_도달_시_ALL_REFUNDED_전이() {
        Payment payment = 결제_생성_후_승인(10_000);
        when(refundRepository.existsByRefundRequestId(any())).thenReturn(false);
        when(paymentRepository.findByOrderIdAndStatusInForUpdate(any(), any())).thenReturn(Optional.of(payment));
        when(refundRepository.findByPaymentIdAndStatus(payment.getId(), RefundStatus.COMPLETED))
            .thenReturn(List.of(기존_완료_환불(payment.getId(), 6_000)));
        when(refundRepository.save(any(Refund.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentGateway.refund(anyString(), any(), anyInt())).thenReturn(new RefundResult(OffsetDateTime.now()));

        ProcessRefundCommand command = new ProcessRefundCommand(
            payment.getOrderId(), UUID.randomUUID(), 4_000, OffsetDateTime.now());
        service.process(command);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.ALL_REFUNDED);
    }

    @Test
    void PG_실패_시_Refund_FAILED_Payment_상태_불변_및_실패_이벤트_발행() {
        Payment payment = 결제_생성_후_승인(10_000);
        when(refundRepository.existsByRefundRequestId(any())).thenReturn(false);
        when(paymentRepository.findByOrderIdAndStatusInForUpdate(any(), any())).thenReturn(Optional.of(payment));
        when(refundRepository.findByPaymentIdAndStatus(payment.getId(), RefundStatus.COMPLETED))
            .thenReturn(List.of());
        when(refundRepository.save(any(Refund.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentGateway.refund(anyString(), any(), anyInt()))
            .thenThrow(new PaymentGatewayException(PaymentErrorCode.PG_INVALID_REQUEST, "CANCEL_FAILED", "환불 실패", null, null));

        ProcessRefundCommand command = new ProcessRefundCommand(
            payment.getOrderId(), UUID.randomUUID(), 4_000, OffsetDateTime.now());
        service.process(command);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);

        ArgumentCaptor<Refund> refundCaptor = ArgumentCaptor.forClass(Refund.class);
        verify(refundRepository).save(refundCaptor.capture());
        assertThat(refundCaptor.getValue().getStatus()).isEqualTo(RefundStatus.FAILED);
        assertThat(refundCaptor.getValue().getReason()).isEqualTo("환불 실패");

        ArgumentCaptor<PaymentRefundFailedEvent> eventCaptor = ArgumentCaptor.forClass(PaymentRefundFailedEvent.class);
        verify(applicationEventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().failureReason()).isEqualTo("환불 실패");
    }

    private Payment 결제_생성_후_승인(int amount) {
        UUID userId = UUID.randomUUID();
        Payment payment = Payment.create(UUID.randomUUID(), userId, "pg-key", "TOSS_PAYMENTS", "CARD", false, amount);
        payment.markRequested(OffsetDateTime.now());
        payment.approve(amount, "카드", "{}", OffsetDateTime.now());
        return payment;
    }

    private Refund 기존_완료_환불(UUID paymentId, int amount) {
        Refund refund = Refund.create(paymentId, UUID.randomUUID(), amount, null);
        refund.complete(OffsetDateTime.now());
        return refund;
    }
}
```

Run: `../gradlew :payment-service:test --tests "com.prompthub.paymentservice.application.service.ProcessRefundServiceTest"`
Expected: FAIL(컴파일 에러 또는 어서션 실패) — 아직 `ProcessRefundService` 본체를 안 고쳤으므로 `existsByRefundRequestId` 호출이 없고, `Refund.create`/`fail` 시그니처도 안 맞음.

- [ ] **Step 8: `ProcessRefundService` 구현 — dedup, 생성 시점 앞당김, 과환불 정상 실패 처리**

`src/main/java/com/prompthub/paymentservice/application/service/ProcessRefundService.java` 전체를 아래로 교체:

```java
package com.prompthub.paymentservice.application.service;

import com.prompthub.exception.BusinessException;
import com.prompthub.paymentservice.application.dto.command.ProcessRefundCommand;
import com.prompthub.paymentservice.application.exception.PaymentErrorCode;
import com.prompthub.paymentservice.application.gateway.external.PaymentGateway;
import com.prompthub.paymentservice.application.gateway.external.PaymentGatewayException;
import com.prompthub.paymentservice.application.gateway.external.RefundResult;
import com.prompthub.paymentservice.application.usecase.ProcessRefundUseCase;
import com.prompthub.paymentservice.domain.event.PaymentRefundFailedEvent;
import com.prompthub.paymentservice.domain.event.PaymentRefundedEvent;
import com.prompthub.paymentservice.domain.model.Payment;
import com.prompthub.paymentservice.domain.model.PaymentStatus;
import com.prompthub.paymentservice.domain.model.Refund;
import com.prompthub.paymentservice.domain.model.RefundStatus;
import com.prompthub.paymentservice.domain.repository.PaymentRepository;
import com.prompthub.paymentservice.domain.repository.RefundRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ProcessRefundService implements ProcessRefundUseCase {

    private static final List<PaymentStatus> REFUNDABLE_STATUSES =
        List.of(PaymentStatus.PAID, PaymentStatus.PARTIAL_REFUNDED);

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final PaymentGateway paymentGateway;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Override
    public void process(ProcessRefundCommand command) {
        if (refundRepository.existsByRefundRequestId(command.refundRequestId())) {
            log.info("이미 처리된 환불 요청 — 중복 이벤트로 판단하고 종료. refundRequestId={}", command.refundRequestId());
            return;
        }

        Payment payment = paymentRepository
            .findByOrderIdAndStatusInForUpdate(command.orderId(), REFUNDABLE_STATUSES)
            .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND));

        Refund refund = Refund.create(payment.getId(), command.refundRequestId(), command.refundAmount(), null);

        int alreadyRefunded = refundRepository
            .findByPaymentIdAndStatus(payment.getId(), RefundStatus.COMPLETED)
            .stream()
            .mapToInt(Refund::getRefundAmount)
            .sum();
        int remaining = payment.getTotalAmount() - alreadyRefunded;

        if (command.refundAmount() > remaining) {
            log.warn("환불 가능 잔액 초과 — paymentId={}, remaining={}, requested={}",
                payment.getId(), remaining, command.refundAmount());
            refund.fail("환불 가능 잔액을 초과했습니다.");
            refundRepository.save(refund);
            applicationEventPublisher.publishEvent(
                new PaymentRefundFailedEvent(payment, refund, "환불 가능 잔액을 초과했습니다."));
            return;
        }

        try {
            RefundResult result = paymentGateway.refund(payment.getPgTxId(), refund.getId(), command.refundAmount());
            refund.complete(result.refundedAt());
            payment.applyRefund(result.refundedAt(), command.refundAmount() == remaining);
            paymentRepository.save(payment);
            refundRepository.save(refund);
            applicationEventPublisher.publishEvent(new PaymentRefundedEvent(payment, refund));
        } catch (PaymentGatewayException e) {
            log.error("PG 환불 실패 — paymentId={}, refundRequestId={}, code={}, reason={}",
                payment.getId(), command.refundRequestId(), e.getFailureCode(), e.getFailureReason());
            refund.fail(e.getFailureReason());
            refundRepository.save(refund);
            applicationEventPublisher.publishEvent(
                new PaymentRefundFailedEvent(payment, refund, e.getFailureReason()));
        }
    }
}
```

- [ ] **Step 9: `ProcessRefundServiceTest` 통과 확인**

Run: `../gradlew :payment-service:test --tests "com.prompthub.paymentservice.application.service.ProcessRefundServiceTest"`
Expected: PASS (전체 6개 테스트)

- [ ] **Step 10: 아웃바운드 메시지 DTO 축소**

`src/main/java/com/prompthub/paymentservice/infrastructure/messaging/dto/PaymentRefundedMessage.java`:

```java
package com.prompthub.paymentservice.infrastructure.messaging.dto;

import java.util.UUID;

public record PaymentRefundedMessage(
    UUID orderId,
    int refundAmount,
    String refundedAt
) {}
```

`src/main/java/com/prompthub/paymentservice/infrastructure/messaging/dto/PaymentRefundFailedMessage.java`:

```java
package com.prompthub.paymentservice.infrastructure.messaging.dto;

import java.util.UUID;

public record PaymentRefundFailedMessage(
    UUID orderId,
    int refundAmount,
    String failedAt
) {}
```

- [ ] **Step 11: `KafkaPaymentEventPublisher`에 새 페이로드 기대하는 실패 테스트 먼저 작성**

`src/test/java/com/prompthub/paymentservice/infrastructure/messaging/KafkaPaymentEventPublisherTest.java`에 아래 두 테스트를 파일 마지막(닫는 `}` 앞)에 추가:

```java
    @Test
    @SuppressWarnings("unchecked")
    void 환불_성공_시_축소된_페이로드로_발행한다() {
        stubSendSuccess();
        Payment payment = Payment.create(
            UUID.randomUUID(), UUID.randomUUID(), "pgTx-3", "TOSS_PAYMENTS", "CARD", true, 10_000);
        payment.markRequested(OffsetDateTime.now());
        payment.approve(10_000, "CARD", "{}", OffsetDateTime.now());
        com.prompthub.paymentservice.domain.model.Refund refund =
            com.prompthub.paymentservice.domain.model.Refund.create(payment.getId(), UUID.randomUUID(), 4_000, null);
        OffsetDateTime refundedAt = OffsetDateTime.now();
        refund.complete(refundedAt);
        payment.applyRefund(refundedAt, false);

        publisher.onPaymentRefunded(new com.prompthub.paymentservice.domain.event.PaymentRefundedEvent(payment, refund));

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        org.mockito.Mockito.verify(kafkaTemplate)
            .send(org.mockito.ArgumentMatchers.eq(PaymentTopic.PAYMENT_EVENTS),
                org.mockito.ArgumentMatchers.eq(payment.getOrderId().toString()), captor.capture());

        EventMessage<com.prompthub.paymentservice.infrastructure.messaging.dto.PaymentRefundedMessage> message =
            (EventMessage<com.prompthub.paymentservice.infrastructure.messaging.dto.PaymentRefundedMessage>) captor.getValue();
        assertThat(message.eventType()).isEqualTo("PAYMENT_REFUNDED");
        assertThat(message.payload().orderId()).isEqualTo(payment.getOrderId());
        assertThat(message.payload().refundAmount()).isEqualTo(4_000);
        assertThat(message.payload().refundedAt()).isNotNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void 환불_실패_시_축소된_페이로드로_발행한다() {
        stubSendSuccess();
        Payment payment = Payment.create(
            UUID.randomUUID(), UUID.randomUUID(), "pgTx-4", "TOSS_PAYMENTS", "CARD", true, 10_000);
        payment.markRequested(OffsetDateTime.now());
        payment.approve(10_000, "CARD", "{}", OffsetDateTime.now());
        com.prompthub.paymentservice.domain.model.Refund refund =
            com.prompthub.paymentservice.domain.model.Refund.create(payment.getId(), UUID.randomUUID(), 4_000, null);
        refund.fail("PG 오류");

        publisher.onPaymentRefundFailed(
            new com.prompthub.paymentservice.domain.event.PaymentRefundFailedEvent(payment, refund, "PG 오류"));

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        org.mockito.Mockito.verify(kafkaTemplate)
            .send(org.mockito.ArgumentMatchers.eq(PaymentTopic.PAYMENT_EVENTS),
                org.mockito.ArgumentMatchers.eq(payment.getOrderId().toString()), captor.capture());

        EventMessage<com.prompthub.paymentservice.infrastructure.messaging.dto.PaymentRefundFailedMessage> message =
            (EventMessage<com.prompthub.paymentservice.infrastructure.messaging.dto.PaymentRefundFailedMessage>) captor.getValue();
        assertThat(message.eventType()).isEqualTo("PAYMENT_REFUND_FAILED");
        assertThat(message.payload().orderId()).isEqualTo(payment.getOrderId());
        assertThat(message.payload().refundAmount()).isEqualTo(4_000);
        assertThat(message.payload().failedAt()).isNotNull();
    }
```

Run: `../gradlew :payment-service:test --tests "com.prompthub.paymentservice.infrastructure.messaging.KafkaPaymentEventPublisherTest"`
Expected: FAIL(컴파일 에러) — `KafkaPaymentEventPublisher`가 아직 `refund.getOrderProductId()`/`payment.getUserId()` 등 삭제된 getter를 참조하고 있어 이 시점엔 모듈 자체가 컴파일 안 됨.

- [ ] **Step 12: `KafkaPaymentEventPublisher` 환불 이벤트 발행부 축소**

`src/main/java/com/prompthub/paymentservice/infrastructure/messaging/KafkaPaymentEventPublisher.java`에서 `onPaymentRefunded`/`onPaymentRefundFailed` 두 메서드만 아래로 교체(나머지 `onPaymentApproved`/`onPaymentFailed`/`toKstString`/`toKst`/필드/import는 그대로 유지):

```java
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentRefunded(PaymentRefundedEvent event) {
        Payment payment = event.payment();
        Refund refund = event.refund();
        PaymentRefundedMessage payload = new PaymentRefundedMessage(
            payment.getOrderId(),
            refund.getRefundAmount(),
            toKstString(payment.getRefundedAt())
        );
        EventMessage<PaymentRefundedMessage> message = new EventMessage<>(
            UUID.randomUUID(),
            PaymentEventType.PAYMENT_REFUNDED.code(),
            toKst(payment.getRefundedAt()),
            AGGREGATE_TYPE_ORDER,
            payment.getOrderId(),
            payload
        );
        kafkaTemplate.send(PaymentTopic.PAYMENT_EVENTS, payment.getOrderId().toString(), message)
            .whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("환불 완료 Kafka 메시지 발행 실패 — paymentId={}, refundId={}, cause={}",
                        payment.getId(), refund.getId(), ex.getMessage());
                } else {
                    log.info("환불 완료 Kafka 메시지 발행 성공 — paymentId={}, refundId={}, partition={}, offset={}",
                        payment.getId(), refund.getId(),
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
                }
            });
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentRefundFailed(PaymentRefundFailedEvent event) {
        Payment payment = event.payment();
        Refund refund = event.refund();
        OffsetDateTime failedAt = OffsetDateTime.now();
        PaymentRefundFailedMessage payload = new PaymentRefundFailedMessage(
            payment.getOrderId(),
            refund.getRefundAmount(),
            toKstString(failedAt)
        );
        EventMessage<PaymentRefundFailedMessage> message = new EventMessage<>(
            UUID.randomUUID(),
            PaymentEventType.PAYMENT_REFUND_FAILED.code(),
            toKst(failedAt),
            AGGREGATE_TYPE_ORDER,
            payment.getOrderId(),
            payload
        );
        kafkaTemplate.send(PaymentTopic.PAYMENT_EVENTS, payment.getOrderId().toString(), message)
            .whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("환불 실패 Kafka 메시지 발행 실패 — paymentId={}, refundId={}, cause={}",
                        payment.getId(), refund.getId(), ex.getMessage());
                } else {
                    log.info("환불 실패 Kafka 메시지 발행 성공 — paymentId={}, refundId={}, partition={}, offset={}",
                        payment.getId(), refund.getId(),
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
                }
            });
    }
```

- [ ] **Step 13: `KafkaPaymentEventPublisherTest` 통과 확인**

Run: `../gradlew :payment-service:test --tests "com.prompthub.paymentservice.infrastructure.messaging.KafkaPaymentEventPublisherTest"`
Expected: PASS (전체 4개 테스트)

- [ ] **Step 14: `PartialRefundIntegrationTest` 갱신 — refundRequestId 반영 + 재환불/dedup 시나리오 추가**

`src/test/java/com/prompthub/paymentservice/PartialRefundIntegrationTest.java` 전체를 아래로 교체:

```java
package com.prompthub.paymentservice;

import com.prompthub.paymentservice.application.gateway.external.PaymentGateway;
import com.prompthub.paymentservice.application.gateway.external.PaymentGatewayException;
import com.prompthub.paymentservice.application.gateway.external.RefundResult;
import com.prompthub.paymentservice.application.exception.PaymentErrorCode;
import com.prompthub.paymentservice.domain.model.Payment;
import com.prompthub.paymentservice.domain.model.PaymentStatus;
import com.prompthub.paymentservice.infrastructure.messaging.config.PaymentTopic;
import com.prompthub.paymentservice.infrastructure.persistence.PaymentJpaRepository;
import com.prompthub.paymentservice.infrastructure.persistence.RefundJpaRepository;
import com.prompthub.paymentservice.support.AbstractIntegrationTest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class PartialRefundIntegrationTest extends AbstractIntegrationTest {

    private static final String TOPIC = "order-events";

    @Autowired
    PaymentJpaRepository paymentJpaRepository;

    @Autowired
    RefundJpaRepository refundJpaRepository;

    @MockitoBean
    PaymentGateway paymentGateway;

    @BeforeEach
    void clean() {
        refundJpaRepository.deleteAll();
        paymentJpaRepository.deleteAll();
    }

    @Test
    void 두번의_부분환불_누적으로_ALL_REFUNDED_도달_및_Kafka_발행() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Payment payment = Payment.create(orderId, userId, "pg-key-cumulative", "TOSS_PAYMENTS", "CARD", false, 10_000);
        payment.markRequested(OffsetDateTime.now());
        payment.approve(10_000, "카드", "{}", OffsetDateTime.now());
        paymentJpaRepository.saveAndFlush(payment);

        when(paymentGateway.refund(anyString(), any(), anyInt()))
            .thenReturn(new RefundResult(OffsetDateTime.now()));

        KafkaConsumer<String, String> consumer = 컨슈머_생성("partial-refund-test-group");
        TopicPartition partition = new TopicPartition(PaymentTopic.PAYMENT_EVENTS, 0);
        consumer.assign(java.util.List.of(partition));
        consumer.poll(Duration.ZERO);
        consumer.seekToBeginning(java.util.List.of(partition));

        send(orderId.toString(), json(orderId, UUID.randomUUID(), userId, UUID.randomUUID(), 6_000));

        await().atMost(Duration.ofSeconds(15))
            .pollInterval(Duration.ofMillis(300))
            .untilAsserted(() -> {
                Payment updated = paymentJpaRepository.findById(payment.getId()).orElseThrow();
                assertThat(updated.getStatus()).isEqualTo(PaymentStatus.PARTIAL_REFUNDED);
            });

        send(orderId.toString(), json(orderId, UUID.randomUUID(), userId, UUID.randomUUID(), 4_000));

        await().atMost(Duration.ofSeconds(15))
            .pollInterval(Duration.ofMillis(300))
            .untilAsserted(() -> {
                Payment updated = paymentJpaRepository.findById(payment.getId()).orElseThrow();
                assertThat(updated.getStatus()).isEqualTo(PaymentStatus.ALL_REFUNDED);
            });

        try {
            long deadline = System.currentTimeMillis() + 10_000;
            int foundCount = 0;
            while (foundCount < 2 && System.currentTimeMillis() < deadline) {
                var polled = consumer.poll(Duration.ofMillis(500));
                for (var r : polled) {
                    if (orderId.toString().equals(r.key()) && r.value().contains("PAYMENT_REFUNDED")) {
                        foundCount++;
                    }
                }
            }
            assertThat(foundCount).withFailMessage("PAYMENT_REFUNDED 메시지 2건 수신 실패").isEqualTo(2);
        } finally {
            consumer.close();
        }
    }

    @Test
    void 동일_상품_재환불_두_refundRequestId_모두_성공() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID orderProductId = UUID.randomUUID();
        Payment payment = Payment.create(orderId, userId, "pg-key-re-refund", "TOSS_PAYMENTS", "CARD", false, 10_000);
        payment.markRequested(OffsetDateTime.now());
        payment.approve(10_000, "카드", "{}", OffsetDateTime.now());
        paymentJpaRepository.saveAndFlush(payment);

        when(paymentGateway.refund(anyString(), any(), anyInt()))
            .thenReturn(new RefundResult(OffsetDateTime.now()));

        send(orderId.toString(), json(orderId, orderProductId, userId, UUID.randomUUID(), 3_000));

        await().atMost(Duration.ofSeconds(15))
            .pollInterval(Duration.ofMillis(300))
            .untilAsserted(() -> assertThat(refundJpaRepository.count()).isEqualTo(1));

        send(orderId.toString(), json(orderId, orderProductId, userId, UUID.randomUUID(), 2_000));

        await().atMost(Duration.ofSeconds(15))
            .pollInterval(Duration.ofMillis(300))
            .untilAsserted(() -> assertThat(refundJpaRepository.count()).isEqualTo(2));

        Payment updated = paymentJpaRepository.findById(payment.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(PaymentStatus.PARTIAL_REFUNDED);
    }

    @Test
    void 동일_refundRequestId_재전송_시_한번만_처리() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID refundRequestId = UUID.randomUUID();
        Payment payment = Payment.create(orderId, userId, "pg-key-dedup", "TOSS_PAYMENTS", "CARD", false, 10_000);
        payment.markRequested(OffsetDateTime.now());
        payment.approve(10_000, "카드", "{}", OffsetDateTime.now());
        paymentJpaRepository.saveAndFlush(payment);

        when(paymentGateway.refund(anyString(), any(), anyInt()))
            .thenReturn(new RefundResult(OffsetDateTime.now()));

        String message = json(orderId, UUID.randomUUID(), userId, refundRequestId, 3_000);
        send(orderId.toString(), message);
        send(orderId.toString(), message);

        await().atMost(Duration.ofSeconds(15))
            .pollInterval(Duration.ofMillis(300))
            .untilAsserted(() -> {
                Payment updated = paymentJpaRepository.findById(payment.getId()).orElseThrow();
                assertThat(updated.getStatus()).isEqualTo(PaymentStatus.PARTIAL_REFUNDED);
            });

        // 두 메시지가 모두 소비될 시간을 확보한 뒤 refund row가 1건만 있는지 확인
        await().pollDelay(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(10))
            .untilAsserted(() -> assertThat(refundJpaRepository.count()).isEqualTo(1));
    }

    @Test
    void 과환불_시도_시_예외_없이_FAILED_기록_및_실패_이벤트_발행() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Payment payment = Payment.create(orderId, userId, "pg-key-over-refund", "TOSS_PAYMENTS", "CARD", false, 10_000);
        payment.markRequested(OffsetDateTime.now());
        payment.approve(10_000, "카드", "{}", OffsetDateTime.now());
        paymentJpaRepository.saveAndFlush(payment);

        KafkaConsumer<String, String> consumer = 컨슈머_생성("partial-refund-over-test-group");
        TopicPartition partition = new TopicPartition(PaymentTopic.PAYMENT_EVENTS, 0);
        consumer.assign(java.util.List.of(partition));
        consumer.poll(Duration.ZERO);
        consumer.seekToBeginning(java.util.List.of(partition));

        send(orderId.toString(), json(orderId, UUID.randomUUID(), userId, UUID.randomUUID(), 12_000));

        try {
            long deadline = System.currentTimeMillis() + 10_000;
            boolean found = false;
            while (!found && System.currentTimeMillis() < deadline) {
                var polled = consumer.poll(Duration.ofMillis(500));
                for (var r : polled) {
                    if (orderId.toString().equals(r.key()) && r.value().contains("PAYMENT_REFUND_FAILED")) {
                        found = true;
                        break;
                    }
                }
            }
            assertThat(found).withFailMessage("PAYMENT_REFUND_FAILED 메시지 수신 실패").isTrue();
        } finally {
            consumer.close();
        }

        Payment unchanged = paymentJpaRepository.findById(payment.getId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(refundJpaRepository.count()).isEqualTo(1);
    }

    @Test
    void PG_환불_실패_시_Payment_상태_불변_및_실패_이벤트_발행() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Payment payment = Payment.create(orderId, userId, "pg-key-fail", "TOSS_PAYMENTS", "CARD", false, 10_000);
        payment.markRequested(OffsetDateTime.now());
        payment.approve(10_000, "카드", "{}", OffsetDateTime.now());
        paymentJpaRepository.saveAndFlush(payment);

        when(paymentGateway.refund(anyString(), any(), anyInt()))
            .thenThrow(new PaymentGatewayException(PaymentErrorCode.PG_INVALID_REQUEST, "CANCEL_FAILED", "환불 실패", null, null));

        KafkaConsumer<String, String> consumer = 컨슈머_생성("partial-refund-fail-test-group");
        TopicPartition partition = new TopicPartition(PaymentTopic.PAYMENT_EVENTS, 0);
        consumer.assign(java.util.List.of(partition));
        consumer.poll(Duration.ZERO);
        consumer.seekToBeginning(java.util.List.of(partition));

        send(orderId.toString(), json(orderId, UUID.randomUUID(), userId, UUID.randomUUID(), 4_000));

        try {
            long deadline = System.currentTimeMillis() + 10_000;
            boolean found = false;
            while (!found && System.currentTimeMillis() < deadline) {
                var polled = consumer.poll(Duration.ofMillis(500));
                for (var r : polled) {
                    if (orderId.toString().equals(r.key()) && r.value().contains("PAYMENT_REFUND_FAILED")) {
                        found = true;
                        break;
                    }
                }
            }
            assertThat(found).withFailMessage("PAYMENT_REFUND_FAILED 메시지 수신 실패").isTrue();
        } finally {
            consumer.close();
        }

        Payment unchanged = paymentJpaRepository.findById(payment.getId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(PaymentStatus.PAID);
    }

    private String json(UUID orderId, UUID orderProductId, UUID buyerId, UUID refundRequestId, int refundAmount) {
        return String.format(
            "{\"eventId\":\"%s\",\"eventType\":\"ORDER_REFUND_REQUESTED\",\"occurredAt\":\"2026-07-13T10:00:00\","
                + "\"aggregateType\":\"ORDER\",\"aggregateId\":\"%s\",\"payload\":{"
                + "\"orderId\":\"%s\",\"orderProductId\":\"%s\",\"buyerId\":\"%s\",\"refundRequestId\":\"%s\","
                + "\"refundAmount\":%d,\"requestedAt\":\"2026-07-13T10:00:00\"}}",
            UUID.randomUUID(), orderId, orderId, orderProductId, buyerId, refundRequestId, refundAmount);
    }

    private void send(String key, String value) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            producer.send(new ProducerRecord<>(TOPIC, key, value));
            producer.flush();
        }
    }

    private KafkaConsumer<String, String> 컨슈머_생성(String groupId) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new KafkaConsumer<>(props);
    }
}
```

Run: `../gradlew :payment-service:test --tests "com.prompthub.paymentservice.PartialRefundIntegrationTest"`
Expected: PASS (전체 5개 테스트, Testcontainers PostgreSQL+Kafka 기동 포함이라 수 분 소요될 수 있음)

- [ ] **Step 15: 전체 빌드 확인**

Run: `../gradlew :payment-service:build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 16: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat: 환불 dedup 키를 refundRequestId로 전환하고 재환불을 허용한다

- Refund 엔티티에 refundRequestId(UNIQUE) 추가, 죽은 필드 order_product_id/user_id 제거(V4)
- Refund 생성 시점을 dedup 통과 직후·과환불 검증 이전으로 앞당겨 과환불 거부도 감사 기록으로 남김
- 과환불 실패를 예외/DLT가 아닌 정상 흐름(Refund.FAILED + PAYMENT_REFUND_FAILED 이벤트)으로 처리
- payment-events 환불 관련 payload를 orderId/금액/시각만 남기도록 축소

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: 문서 갱신 (events.md, db-schema.md, api-design.md)

**배경**: Task 1·2에서 바뀐 실제 동작과 문서가 어긋나지 않도록 `.claude/docs/` 3개 문서를 갱신한다. 코드 변경 없이 문서만 수정하는 태스크다.

**Files:**
- Modify: `.claude/docs/events.md`
- Modify: `.claude/docs/db-schema.md`
- Modify: `.claude/docs/api-design.md`

- [ ] **Step 1: `events.md` — 구독 payload, 발행 payload, 유실 폴백 문구 갱신**

`.claude/docs/events.md`에서 아래 조각들을 교체한다.

1) "구독 토픽" 표 아래 설명 중 "OrderProduct 단위 부분환불 처리(PG 호출 포함 동기)"는 그대로 두되(처리 방식 자체는 안 바뀜), 에러 처리 열은 그대로 유지(재시도 3회 + DLT는 파싱/인프라 오류 한정으로 의미가 좁아졌을 뿐 재시도 정책 자체는 안 바뀜 — 문구 변경 불필요).

2) `## Payload 스키마` 아래 `### PAYMENT_REFUNDED` 섹션 전체를 아래로 교체:

```markdown
### PAYMENT_REFUNDED

```json
{
  "eventId": "9c1f2a7e-4b8d-4e2a-9c11-2d3e4f5a0002",
  "eventType": "PAYMENT_REFUNDED",
  "occurredAt": "2026-06-15T20:00:00",
  "aggregateType": "ORDER",
  "aggregateId": "660e8400-e29b-41d4-a716-446655440001",
  "payload": {
    "orderId":       "660e8400-e29b-41d4-a716-446655440001",
    "refundAmount":  4000,
    "refundedAt":    "2026-06-15T20:00:00+09:00"
  }
}
```

| 필드 (`payload`) | 타입 | 필수 | 설명 |
|---|---|---|---|
| `orderId` | UUID | ✅ | 주문 ID |
| `refundAmount` | Int | ✅ | 이번 환불 건의 금액 |
| `refundedAt` | ISO 8601 (KST) | ✅ | PG 환불 완료 일시 |

`paymentId`/`userId`/`orderProductId`/`paymentStatus`는 order-service가 `orderId` 기준으로 이미 상관관계를 추적할 수 있어 payload에서 제거했다(#398).
```

3) `### PAYMENT_REFUND_FAILED` 섹션 전체를 아래로 교체:

```markdown
### PAYMENT_REFUND_FAILED

PG 환불 실패 또는 과환불 검증 실패 시 발행. `Refund.status=FAILED`로만 기록되고 Payment 상태는 그대로 유지된다. 과환불처럼 확정적 비즈니스 규칙 위반도 예외/DLT가 아니라 이 이벤트로 정상 종료한다(#398).

```json
{
  "eventId": "9c1f2a7e-4b8d-4e2a-9c11-2d3e4f5a0004",
  "eventType": "PAYMENT_REFUND_FAILED",
  "occurredAt": "2026-06-15T20:05:00",
  "aggregateType": "ORDER",
  "aggregateId": "660e8400-e29b-41d4-a716-446655440001",
  "payload": {
    "orderId":       "660e8400-e29b-41d4-a716-446655440001",
    "refundAmount":  4000,
    "failedAt":      "2026-06-15T20:05:00+09:00"
  }
}
```

| 필드 (`payload`) | 타입 | 필수 | 설명 |
|---|---|---|---|
| `orderId` | UUID | ✅ | 주문 ID |
| `refundAmount` | Int | ✅ | 시도했던 환불 금액 |
| `failedAt` | ISO 8601 (KST) | ✅ | 실패 처리 일시 |

`paymentId`/`userId`/`orderProductId`/`paymentStatus`/`failureReason`은 payload에서 제거했다(#398). 실패 사유는 payment-service 내부 `Refund.reason` 컬럼에는 남는다(로그로도 확인 가능하나 외부에는 발행하지 않는다).

구독자(order-service) 반응: 자기 쪽 반품 상태를 실패로 되돌리거나 재시도 여부 판단.

> **Kafka 유실 시 폴백**: 없음. `GetRefund` gRPC 폴백 조회는 제거되었다(#398) — order-service가 Kafka 자체 재조회로 대응한다.
```

4) `## 구독 Payload 스키마`의 `### ORDER_REFUND_REQUESTED` 섹션 전체를 아래로 교체:

```markdown
### ORDER_REFUND_REQUESTED (`eventType: "ORDER_REFUND_REQUESTED"`, 토픽 `order-events`)

`EventMessage<OrderRefundRequestedPayload>` 봉투. order-service가 OrderProduct 단위 환불을 확정하면 발행한다.

```json
{
  "eventId": "f3bdb7f2-ec60-4c77-aab7-57d8b4d84e9b",
  "eventType": "ORDER_REFUND_REQUESTED",
  "occurredAt": "2026-07-13T10:00:00",
  "aggregateType": "ORDER",
  "aggregateId": "660e8400-e29b-41d4-a716-446655440001",
  "payload": {
    "orderId": "660e8400-e29b-41d4-a716-446655440001",
    "orderProductId": "880e8400-e29b-41d4-a716-446655440003",
    "buyerId": "770e8400-e29b-41d4-a716-446655440002",
    "refundRequestId": "990e8400-e29b-41d4-a716-446655440099",
    "refundAmount": 4000,
    "requestedAt": "2026-07-13T10:00:00"
  }
}
```

| 필드 (`payload`) | 타입 | 필수 | 설명 |
|---|---|---|---|
| `orderId` | UUID | ✅ | 주문 ID |
| `orderProductId` | UUID | ✅ | 환불 대상 OrderProduct ID. order-service가 계속 보내지만 payment-service는 파싱만 하고 저장하지 않는다(#398) |
| `buyerId` | UUID | ✅ | 환불 요청 사용자 ID. payment-service는 이 필드 자체를 파싱하지 않는다(#398) — 들어와도 무시된다 |
| `refundRequestId` | UUID | ✅ | order-service가 발급하는 환불 요청 식별자. payment-service의 dedup 키(#398) — 동일 값 재전송 시 1회만 처리 |
| `refundAmount` | Int | ✅ | 환불 금액 — payment-service는 이 값을 그대로 신뢰(누적 초과 여부만 검증) |
| `requestedAt` | LocalDateTime | ✅ | 존 없음 → 소비 시 KST 부여 |
```

5) "발행 경로" 표에서 "스케줄러 (환불 retry)" 행을 삭제한다(코드에 해당 스케줄러가 존재하지 않음 — 실제 코드와 어긋나는 과거 설계 흔적이었음).

- [ ] **Step 2: `db-schema.md` — refund 테이블 컬럼/인덱스 갱신**

`.claude/docs/db-schema.md`의 `## refund 테이블` 섹션 전체를 아래로 교체:

```markdown
## refund 테이블

| 컬럼 | 타입 | NOT NULL | 기본값 | 설명 |
|---|---|---|---|---|
| `id` | UUID | ✅ | — | PK |
| `payment_id` | UUID | ✅ | — | FK → payment(id) |
| `refund_request_id` | UUID | ✅ | — | order-service가 발급하는 환불 요청 식별자. **UNIQUE**(`uk_refund_request_id`) — dedup 키(#398) |
| `refund_amount` | INT | ✅ | — | 이번 환불 시도의 금액 |
| `reason` | TEXT | — | NULL | 환불 사유 또는 실패 사유(`fail()` 호출 시 실패 사유로 갱신됨) |
| `status` | refund_status | ✅ | `REQUESTED` | 환불 상태 |
| `requested_at` | TIMESTAMPTZ | ✅ | `NOW()` | 환불 요청 접수 일시 |
| `completed_at` | TIMESTAMPTZ | — | NULL | PG사 환불 처리 완료 일시 |
| `created_at` | TIMESTAMPTZ | ✅ | `NOW()` | 생성 일시 |
| `updated_at` | TIMESTAMPTZ | ✅ | `NOW()` | 수정 일시 |

**인덱스** (Flyway):

| 인덱스 | 대상 | 목적 |
|---|---|---|
| `uk_refund_request_id` | UNIQUE (`refund_request_id`) | 동일 환불 요청 재전송 dedup. 동일 상품에 대한 재환불(복수 부분환불)은 허용된다 |

`order_product_id`/`user_id` 컬럼은 제거되었다(#398) — 둘 다 payment-service 내부에서 읽히지 않는 죽은 컬럼이었고, 상품 단위 추적 책임은 `refund_request_id`를 발급하는 order-service 쪽에 있다.
```

- [ ] **Step 3: `api-design.md` — 환불 처리 흐름/폴백 문구 갱신**

`.claude/docs/api-design.md`의 `### 환불 — 이벤트 기반 (REST 없음)` 섹션 전체를 아래로 교체:

```markdown
### 환불 — 이벤트 기반 (REST 없음)

환불은 REST 엔드포인트가 아니라 order-service가 발행하는 Kafka 이벤트로 트리거된다.
OrderProduct 단위로만 존재하며, 주문 전체를 환불하려면 order-service가 상품 수만큼 이벤트를 여러 번 발행한다.

**이벤트 계약**: `order-events` 토픽의 `ORDER_REFUND_REQUESTED` — 상세 스키마는 `events.md` 참조.

**처리 흐름**
1. `OrderEventConsumer`가 `ORDER_REFUND_REQUESTED` 수신
2. `refundRequestId`로 이미 처리된 요청인지 조회 — 이미 처리됐으면 정상 종료(중복 이벤트, dedup)
3. 신규 요청이면 `Refund` 생성(`refundRequestId` 저장) 후 `orderId`로 `PAID`/`PARTIAL_REFUNDED` 상태 Payment 조회(락)
4. 누적 환불액이 `total_amount`를 넘으면 `Refund.FAILED` 기록 + `PAYMENT_REFUND_FAILED` 발행(예외 아님, 정상 흐름 — DLT로 가지 않는다)
5. PG 환불 동기 호출(단일 트랜잭션 안에서 수행 — 중간 상태 커밋 없음)
6. 성공: 누적액이 `total_amount`에 도달했으면 `ALL_REFUNDED`, 아니면 `PARTIAL_REFUNDED`로 전이 + `payment-events`에 `PAYMENT_REFUNDED` 발행
7. 실패(PG 오류): `Refund.FAILED`만 기록, Payment 상태는 그대로 + `payment-events`에 `PAYMENT_REFUND_FAILED` 발행. 재시도 장치 없음(필요 시 order-service가 새 `refundRequestId`로 이벤트 재발행)

**동일 상품 재환불**: dedup 키가 `refundRequestId`이므로 같은 OrderProduct에 대해 여러 차례(예: 부분 하자 추가 발견) 환불을 요청할 수 있다. 같은 `refundRequestId`가 재전송되는 경우(Kafka redelivery)만 중복 처리를 막는다.

**금액 검증**: order-service가 보낸 `refundAmount`를 그대로 신뢰한다(payment-service는 상품별 가격 정보를 갖고 있지 않음). 누적 환불액이 결제 총액을 넘지 않는지만 확인한다.

**Kafka 유실 시 폴백**: 없음. `GetRefund` gRPC 폴백 조회는 제거되었다(#398) — order-service가 Kafka 자체 재조회로 대응한다.
```

- [ ] **Step 4: Commit**

```bash
git add .claude/docs/events.md .claude/docs/db-schema.md .claude/docs/api-design.md
git commit -m "$(cat <<'EOF'
docs: 환불 흐름 변경(refundRequestId dedup) 문서 반영

- events.md: PAYMENT_REFUNDED/PAYMENT_REFUND_FAILED 축소된 payload, ORDER_REFUND_REQUESTED에 refundRequestId 추가, GetRefund 폴백 제거 반영
- db-schema.md: refund 테이블 컬럼/인덱스를 refund_request_id 기준으로 갱신
- api-design.md: 환불 처리 흐름에 dedup 단계와 재환불 허용 설명 추가

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```
