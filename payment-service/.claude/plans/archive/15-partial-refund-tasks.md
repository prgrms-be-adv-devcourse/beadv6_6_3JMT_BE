# 부분 환불(OrderProduct 단위) 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> 설계 문서: `.claude/plans/15-partial-refund.md` (배경/트레이드오프는 그 문서 참조)

**Goal:** 기존 REST 기반 전체환불(202+REFUNDING+폴링)을 완전히 제거하고, order-service가 `order-events` 토픽에 발행하는 `ORDER_REFUND_REQUESTED` 이벤트로 트리거되는 OrderProduct 단위 부분환불로 교체한다.

**Architecture:** Kafka 컨슈머(`OrderEventConsumer`)가 이벤트를 받아 신규 `ProcessRefundUseCase`를 단일 트랜잭션(PG 호출 포함, 동기)으로 실행한다. `PaymentStatus.REFUNDING`을 제거하고 `PARTIAL_REFUNDED`/`ALL_REFUNDED`로 상태를 표현한다. 성공/실패 모두 `payment-events` 토픽에 이벤트를 발행한다(`PAYMENT_REFUNDED` / `PAYMENT_REFUND_FAILED`).

**Tech Stack:** Spring Boot 4.1, Java 21, Spring Data JPA, Spring Kafka, Testcontainers(PostgreSQL + `confluentinc/cp-kafka:7.6.1`), AssertJ, Mockito, JUnit 5.

## Global Constraints

- 모든 명령은 `payment-service/` 디렉터리에서 실행: `../gradlew :payment-service:test`
- 테스트 메서드명은 한국어, 클래스/필드/메서드 식별자는 영어.
- 단언은 AssertJ(`assertThat`)만 사용.
- 통합 테스트는 Testcontainers(PostgreSQL + Kafka)만 사용 — H2, EmbeddedKafka 금지.
- 통합 테스트(`*IntegrationTest`)는 루트 패키지 `com.prompthub.paymentservice`에 위치.
- 커밋 메시지: `type: 한국어 설명` (`feat`/`refactor`/`test`/`docs` 등), AI 트레일러 `Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>` 본문 마지막에 포함.
- `common-module`의 `BusinessException`/`ErrorCode`/응답 래퍼 재정의 금지.
- 매 태스크 종료 시 `../gradlew :payment-service:test`로 전체 스위트가 통과해야 다음 태스크로 진행한다(빌드 그린 유지).

---

### Task 1: 기존 REST 전체환불 기능 삭제

기존 `POST /api/v2/payments/{id}/refund`(202+REFUNDING+폴링+스케줄러 재시도) 관련 코드를 전부 제거한다. `PaymentStatus`/`Payment` 도메인 모델은 이번 태스크에서 건드리지 않는다(Task 2에서 처리) — 삭제 후에도 `startRefunding()`/`completeRefund()`/`restoreToRefundFailed()`는 잠시 미사용 상태로 남지만 컴파일은 그대로 통과한다.

**Files:**
- Delete: `src/main/java/com/prompthub/paymentservice/application/usecase/RefundPaymentUseCase.java`
- Delete: `src/main/java/com/prompthub/paymentservice/application/service/RefundPaymentService.java`
- Delete: `src/main/java/com/prompthub/paymentservice/application/dto/command/RefundPaymentCommand.java`
- Delete: `src/main/java/com/prompthub/paymentservice/domain/event/PaymentRefundRequestedEvent.java`
- Delete: `src/main/java/com/prompthub/paymentservice/infrastructure/messaging/RefundEventHandler.java`
- Delete: `src/main/java/com/prompthub/paymentservice/infrastructure/scheduling/PaymentRefundRetryScheduler.java`
- Delete: `src/main/java/com/prompthub/paymentservice/infrastructure/scheduling/SchedulingConfig.java`
- Delete: `src/test/java/com/prompthub/paymentservice/application/service/RefundPaymentServiceTest.java`
- Delete: `src/test/java/com/prompthub/paymentservice/RefundPaymentIntegrationTest.java`
- Modify: `src/main/java/com/prompthub/paymentservice/presentation/PaymentController.java`
- Modify: `src/main/java/com/prompthub/paymentservice/domain/repository/PaymentRepository.java`
- Modify: `src/main/java/com/prompthub/paymentservice/infrastructure/persistence/PaymentJpaRepository.java`
- Modify: `src/main/java/com/prompthub/paymentservice/infrastructure/persistence/PaymentRepositoryAdapter.java`
- Modify: `src/main/java/com/prompthub/paymentservice/domain/repository/RefundRepository.java`
- Modify: `src/main/java/com/prompthub/paymentservice/infrastructure/persistence/RefundJpaRepository.java`
- Modify: `src/main/java/com/prompthub/paymentservice/infrastructure/persistence/RefundRepositoryAdapter.java`
- Modify: `src/main/java/com/prompthub/paymentservice/infrastructure/messaging/KafkaPaymentEventPublisher.java`

**Interfaces:**
- Produces: `PaymentController`에 환불 관련 엔드포인트/의존성 없음. `PaymentRepository`/`RefundRepository`에서 `findByStatusAndUpdatedAtBefore`/`findByPaymentId` 제거됨(다음 태스크에서 대체 메서드 추가).

- [ ] **Step 1: 삭제 대상 파일 제거**

```bash
git rm src/main/java/com/prompthub/paymentservice/application/usecase/RefundPaymentUseCase.java
git rm src/main/java/com/prompthub/paymentservice/application/service/RefundPaymentService.java
git rm src/main/java/com/prompthub/paymentservice/application/dto/command/RefundPaymentCommand.java
git rm src/main/java/com/prompthub/paymentservice/domain/event/PaymentRefundRequestedEvent.java
git rm src/main/java/com/prompthub/paymentservice/infrastructure/messaging/RefundEventHandler.java
git rm src/main/java/com/prompthub/paymentservice/infrastructure/scheduling/PaymentRefundRetryScheduler.java
git rm src/main/java/com/prompthub/paymentservice/infrastructure/scheduling/SchedulingConfig.java
git rm src/test/java/com/prompthub/paymentservice/application/service/RefundPaymentServiceTest.java
git rm src/test/java/com/prompthub/paymentservice/RefundPaymentIntegrationTest.java
```

- [ ] **Step 2: `PaymentController`에서 환불 엔드포인트 제거**

`refund(...)` 메서드 전체와 그 위 `@Operation`/`@ApiResponses` 블록(165~221행)을 제거하고, 관련해서만 쓰이던 import(`RefundPaymentCommand`, `RefundPaymentUseCase`)와 생성자 필드 `refundPaymentUseCase`를 제거한다. `confirm` 엔드포인트와 공통 import(`ApiResult`, `PathVariable` 등 confirm에서도 쓰는 것)는 유지한다.

파일 결과는 다음과 같아야 한다(주요 부분만 표기, 나머지 `confirm` 메서드는 그대로 유지):

```java
package com.prompthub.paymentservice.presentation;

import com.prompthub.exception.response.ErrorResponse;
import com.prompthub.paymentservice.application.dto.command.ConfirmPaymentCommand;
import com.prompthub.paymentservice.application.dto.result.PaymentResult;
import com.prompthub.paymentservice.application.usecase.ConfirmPaymentUseCase;
import com.prompthub.paymentservice.presentation.config.SwaggerConfig;
import com.prompthub.paymentservice.presentation.dto.request.ConfirmPaymentRequest;
import com.prompthub.paymentservice.presentation.dto.response.ConfirmPaymentResponse;
import com.prompthub.presentation.dto.ApiResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.prompthub.exception.BusinessException;
import com.prompthub.paymentservice.application.exception.PaymentErrorCode;
import jakarta.validation.Valid;
import java.util.Arrays;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Payment", description = "결제 승인 API")
@RestController
@RequestMapping("/api/v2/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final ConfirmPaymentUseCase confirmPaymentUseCase;

    @Operation(summary = "결제 승인",
        description = "토스페이먼츠 SDK에서 전달받은 paymentKey로 최종 결제를 승인합니다.")
    @ApiResponses({
        // ... 기존 confirm 응답 예시 그대로 유지 ...
    })
    @PostMapping("/confirm")
    public ResponseEntity<ApiResult<ConfirmPaymentResponse>> confirm(
        @Parameter(description = "사용자 UUID (Gateway 주입)", required = true,
            example = "550e8400-e29b-41d4-a716-446655440000")
        @RequestHeader("X-User-Id") UUID userId,
        @Parameter(description = "사용자 역할 목록 (Gateway 주입, 쉼표 구분)", required = true,
            example = "BUYER,SELLER")
        @RequestHeader("X-User-Role") String userRoles,
        @Valid @RequestBody ConfirmPaymentRequest request
    ) {
        if (Arrays.stream(userRoles.split(",")).noneMatch("BUYER"::equals)) {
            throw new BusinessException(PaymentErrorCode.INSUFFICIENT_ROLE);
        }
        ConfirmPaymentCommand command = new ConfirmPaymentCommand(
            request.paymentKey(), request.orderId(), userId
        );
        PaymentResult result = confirmPaymentUseCase.confirm(command);
        return ResponseEntity.ok(ApiResult.success(new ConfirmPaymentResponse(result.paymentId())));
    }
}
```

(파일 내 `@Tag` description을 "결제 승인 및 환불 API" → "결제 승인 API"로 변경했음에 주의. confirm의 `@ApiResponses` 본문은 원본 그대로 유지하고 refund 관련 블록만 삭제한다.)

- [ ] **Step 3: `PaymentRepository`에서 `findByStatusAndUpdatedAtBefore` 제거**

`domain/repository/PaymentRepository.java`:

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
}
```

`infrastructure/persistence/PaymentJpaRepository.java`에서 `findByStatusAndUpdatedAtBefore` 메서드 선언과 관련 import(`OffsetDateTime`, `List`) 제거:

```java
package com.prompthub.paymentservice.infrastructure.persistence;

import com.prompthub.paymentservice.domain.model.Payment;
import com.prompthub.paymentservice.domain.model.PaymentStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentJpaRepository extends JpaRepository<Payment, UUID> {
    boolean existsByOrderIdAndStatusIn(UUID orderId, Collection<PaymentStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.id = :id")
    Optional<Payment> findByIdForUpdate(@Param("id") UUID id);
}
```

`infrastructure/persistence/PaymentRepositoryAdapter.java`에서 대응 메서드/import(`OffsetDateTime`, `List`) 제거:

```java
package com.prompthub.paymentservice.infrastructure.persistence;

import com.prompthub.paymentservice.domain.repository.PaymentRepository;
import com.prompthub.paymentservice.domain.model.Payment;
import com.prompthub.paymentservice.domain.model.PaymentStatus;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PaymentRepositoryAdapter implements PaymentRepository {

    private final PaymentJpaRepository jpaRepository;

    @Override
    public Payment save(Payment payment) {
        return jpaRepository.save(payment);
    }

    @Override
    public Payment saveAndFlush(Payment payment) {
        return jpaRepository.saveAndFlush(payment);
    }

    @Override
    public Optional<Payment> findById(UUID id) {
        return jpaRepository.findById(id);
    }

    @Override
    public Optional<Payment> findByIdForUpdate(UUID id) {
        return jpaRepository.findByIdForUpdate(id);
    }

    @Override
    public boolean existsByOrderIdAndStatusIn(UUID orderId, Collection<PaymentStatus> statuses) {
        return jpaRepository.existsByOrderIdAndStatusIn(orderId, statuses);
    }
}
```

- [ ] **Step 4: `RefundRepository`에서 `findByPaymentId` 제거**

`domain/repository/RefundRepository.java`:

```java
package com.prompthub.paymentservice.domain.repository;

import com.prompthub.paymentservice.domain.model.Refund;
import java.util.Optional;
import java.util.UUID;

public interface RefundRepository {
    Refund save(Refund refund);
    Optional<Refund> findById(UUID id);
}
```

`infrastructure/persistence/RefundJpaRepository.java`:

```java
package com.prompthub.paymentservice.infrastructure.persistence;

import com.prompthub.paymentservice.domain.model.Refund;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundJpaRepository extends JpaRepository<Refund, UUID> {
}
```

`infrastructure/persistence/RefundRepositoryAdapter.java`:

```java
package com.prompthub.paymentservice.infrastructure.persistence;

import com.prompthub.paymentservice.domain.repository.RefundRepository;
import com.prompthub.paymentservice.domain.model.Refund;
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
}
```

- [ ] **Step 5: `KafkaPaymentEventPublisher`에서 `publishRefunded` 제거**

`infrastructure/messaging/KafkaPaymentEventPublisher.java`에서 `publishRefunded(Payment, Refund)` 메서드 전체와 미사용이 되는 import(`PaymentRefundedMessage`, `Refund`)를 제거한다. `onPaymentApproved`/`onPaymentFailed`는 그대로 둔다(다음 태스크들에서 `onPaymentRefunded`/`onPaymentRefundFailed`를 다시 추가할 예정).

- [ ] **Step 6: 빌드 및 전체 테스트 확인**

```bash
../gradlew :payment-service:build
```
Expected: `BUILD SUCCESSFUL` (RefundJpaRepositoryTest, PaymentTest, OrderEventConsumerIntegrationTest 등 기존 테스트 모두 통과. `Payment.startRefunding/completeRefund/restoreToRefundFailed`는 미사용 경고만 발생, 컴파일 에러 없음)

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
refactor: 기존 REST 전체환불 기능 제거

- POST /payments/{id}/refund 엔드포인트 및 관련 UseCase/Service/Command 삭제
- RefundEventHandler, PaymentRefundRetryScheduler, SchedulingConfig 삭제
- 부분환불(이벤트 기반) 도입을 위한 선행 정리 작업

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: PaymentStatus 개편 + Payment.applyRefund()

`PaymentStatus`에서 `REFUNDING` 제거, `REFUNDED`를 `ALL_REFUNDED`로 개명, `PARTIAL_REFUNDED` 추가. `Payment`의 `startRefunding()`/`completeRefund()`/`restoreToRefundFailed()` 3개를 `applyRefund(OffsetDateTime, boolean)` 1개로 대체. `ConfirmPaymentService`의 `BLOCKING_STATUSES`도 함께 수정한다(그러지 않으면 컴파일 에러).

**Files:**
- Modify: `src/main/java/com/prompthub/paymentservice/domain/model/PaymentStatus.java`
- Modify: `src/main/java/com/prompthub/paymentservice/domain/model/Payment.java`
- Modify: `src/main/java/com/prompthub/paymentservice/application/service/ConfirmPaymentService.java`
- Modify: `src/test/java/com/prompthub/paymentservice/domain/model/PaymentTest.java`

**Interfaces:**
- Produces: `Payment.applyRefund(OffsetDateTime refundedAt, boolean isFullyRefunded): void` — Task 10(`ProcessRefundService`)이 사용.
- Produces: `PaymentStatus` 값 집합 `{READY, REQUESTED, PAID, FAILED, PARTIAL_REFUNDED, ALL_REFUNDED, UNKNOWN}`.

- [ ] **Step 1: 실패하는 테스트 작성 — `PaymentTest`에 `applyRefund` 케이스 추가**

`src/test/java/com/prompthub/paymentservice/domain/model/PaymentTest.java`의 기존 메서드는 그대로 두고, 파일 끝(마지막 `결제_생성()` 헬퍼 앞)에 아래 테스트들을 추가한다:

```java
    @Test
    void applyRefund_부분환불_시_PARTIAL_REFUNDED_상태() {
        Payment payment = 결제_생성_후_승인(10_000);
        OffsetDateTime refundedAt = OffsetDateTime.now();

        payment.applyRefund(refundedAt, false);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PARTIAL_REFUNDED);
        assertThat(payment.getRefundedAt()).isEqualTo(refundedAt);
    }

    @Test
    void applyRefund_전액소진_시_ALL_REFUNDED_상태() {
        Payment payment = 결제_생성_후_승인(10_000);
        OffsetDateTime refundedAt = OffsetDateTime.now();

        payment.applyRefund(refundedAt, true);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.ALL_REFUNDED);
    }

    @Test
    void applyRefund_PARTIAL_REFUNDED_상태에서_추가_환불_가능() {
        Payment payment = 결제_생성_후_승인(10_000);
        payment.applyRefund(OffsetDateTime.now(), false);

        OffsetDateTime secondRefundedAt = OffsetDateTime.now();
        payment.applyRefund(secondRefundedAt, true);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.ALL_REFUNDED);
    }

    @Test
    void PAID_아니고_PARTIAL_REFUNDED도_아닌_상태에서_applyRefund_실패() {
        Payment payment = 결제_생성(); // READY 상태

        assertThatThrownBy(() -> payment.applyRefund(OffsetDateTime.now(), true))
            .isInstanceOf(IllegalStateException.class);
    }

    private Payment 결제_생성_후_승인(int amount) {
        Payment payment = Payment.create(
            UUID.randomUUID(), UUID.randomUUID(),
            "pg-key-002", "TOSS_PAYMENTS", "CARD", false, amount
        );
        payment.markRequested(OffsetDateTime.now());
        payment.approve(amount, "카드", "{}", OffsetDateTime.now());
        return payment;
    }
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

```bash
../gradlew :payment-service:test --tests "com.prompthub.paymentservice.domain.model.PaymentTest"
```
Expected: FAIL — `Payment.applyRefund`가 아직 존재하지 않아 컴파일 에러

- [ ] **Step 3: `PaymentStatus` 수정**

```java
package com.prompthub.paymentservice.domain.model;

public enum PaymentStatus {
    READY,
    REQUESTED,
    PAID,
    FAILED,
    PARTIAL_REFUNDED,
    ALL_REFUNDED,
    UNKNOWN
}
```

- [ ] **Step 4: `Payment.java` 전이 메서드 교체**

`domain/model/Payment.java`의 `startRefunding()`부터 `restoreToRefundFailed()`까지(167~185행) 아래로 교체:

```java
    public void applyRefund(OffsetDateTime refundedAt, boolean isFullyRefunded) {
        if (this.status != PaymentStatus.PAID && this.status != PaymentStatus.PARTIAL_REFUNDED) {
            throw new IllegalStateException("PAID/PARTIAL_REFUNDED 상태에서만 환불을 적용할 수 있습니다.");
        }
        PaymentStatus previous = this.status;
        this.status = isFullyRefunded ? PaymentStatus.ALL_REFUNDED : PaymentStatus.PARTIAL_REFUNDED;
        this.refundedAt = refundedAt;
        log.debug("Payment 상태 전이 — id={}, {} → {}", id, previous, this.status);
    }
```

(기존 로그 문구 스타일을 유지하되 이전 상태를 변수로 캡처해서 로그에 남긴다.)

- [ ] **Step 5: 테스트 실행 — 통과 확인**

```bash
../gradlew :payment-service:test --tests "com.prompthub.paymentservice.domain.model.PaymentTest"
```
Expected: PASS

- [ ] **Step 6: `ConfirmPaymentService.BLOCKING_STATUSES` 수정**

`application/service/ConfirmPaymentService.java` 41~43행:

```java
    // 진행·완료 상태가 이미 있으면 재결제 차단. REQUESTED·FAILED·READY는 비차단(재결제 허용).
    private static final Set<PaymentStatus> BLOCKING_STATUSES = Set.of(
        PaymentStatus.PAID, PaymentStatus.PARTIAL_REFUNDED, PaymentStatus.ALL_REFUNDED, PaymentStatus.UNKNOWN
    );
```

- [ ] **Step 7: 전체 빌드 확인**

```bash
../gradlew :payment-service:build
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/prompthub/paymentservice/domain/model/PaymentStatus.java \
        src/main/java/com/prompthub/paymentservice/domain/model/Payment.java \
        src/main/java/com/prompthub/paymentservice/application/service/ConfirmPaymentService.java \
        src/test/java/com/prompthub/paymentservice/domain/model/PaymentTest.java
git commit -m "$(cat <<'EOF'
refactor: PaymentStatus 개편 — REFUNDING 제거, PARTIAL_REFUNDED/ALL_REFUNDED 도입

- REFUNDED → ALL_REFUNDED 개명, PARTIAL_REFUNDED 신규 추가
- startRefunding/completeRefund/restoreToRefundFailed 3개 메서드를 applyRefund() 1개로 통합
- HTTP 호출자가 없는 이벤트 소비 구조에서 REFUNDING(진행중 마커)이 더 이상 필요하지 않음

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Refund.orderProductId NOT NULL + 유니크 인덱스

`Refund.orderProductId`를 필수 필드로 만들고, 같은 결제·같은 상품 중복 환불을 막는 유니크 인덱스를 추가한다.

**Files:**
- Modify: `src/main/java/com/prompthub/paymentservice/domain/model/Refund.java`
- Modify: `src/main/resources/schema.sql`
- Modify: `src/test/java/com/prompthub/paymentservice/infrastructure/persistence/RefundJpaRepositoryTest.java`

**Interfaces:**
- Produces: `Refund.create(paymentId, userId, refundAmount, reason, orderProductId)` — `orderProductId`는 이제 항상 non-null이어야 함(도메인 계층에서 강제하지 않고 DB NOT NULL + 호출부 책임으로 위임 — Task 10에서 항상 채워서 호출).

- [ ] **Step 1: 실패하는 테스트로 기존 nullable 테스트 교체**

`src/test/java/com/prompthub/paymentservice/infrastructure/persistence/RefundJpaRepositoryTest.java`의 `refund_without_order_product_id()` 테스트를 제거하고, 아래로 교체:

```java
    @Test
    void 같은_결제_같은_상품_중복_환불_시_유니크_제약_위반() {
        UUID paymentId = UUID.randomUUID();
        UUID orderProductId = UUID.randomUUID();

        Refund first = Refund.create(paymentId, UUID.randomUUID(), 5_000, null, orderProductId);
        refundJpaRepository.saveAndFlush(first);

        Refund duplicate = Refund.create(paymentId, UUID.randomUUID(), 3_000, null, orderProductId);

        assertThatThrownBy(() -> refundJpaRepository.saveAndFlush(duplicate))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
```

`refund_save_findById_round_trip()`는 그대로 둔다(이미 `orderProductId`를 채워서 호출 중).

파일 상단 import에 `assertThatThrownBy` 추가:

```java
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

```bash
../gradlew :payment-service:test --tests "com.prompthub.paymentservice.infrastructure.persistence.RefundJpaRepositoryTest"
```
Expected: FAIL — 아직 유니크 인덱스가 없어 두 번째 `saveAndFlush`가 예외 없이 성공하므로 `assertThatThrownBy`가 실패

- [ ] **Step 3: `Refund.java` 컬럼 정의 수정**

`domain/model/Refund.java` 37행:

```java
    @Column(name = "order_product_id", columnDefinition = "uuid", nullable = false)
    private UUID orderProductId;
```

- [ ] **Step 4: `schema.sql`에 유니크 인덱스 추가**

`src/main/resources/schema.sql` 끝에 추가:

```sql

-- 부분 환불(OrderProduct 단위) 도입 (#15) — order_product_id 필수화 + 상품당 1회 환불만 허용
-- 기존 로컬 DB에 order_product_id가 NULL인 행이 남아있으면 아래 두 문장이 실패한다.
-- 로컬 개발 DB라면 `TRUNCATE TABLE refund;`로 비우고 재기동한다(Testcontainers 기반 테스트는 매번 ddl-auto=create라 영향 없음).
DELETE FROM refund WHERE order_product_id IS NULL;
ALTER TABLE refund ALTER COLUMN order_product_id SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_refund_payment_order_product ON refund (payment_id, order_product_id);
```

- [ ] **Step 5: 테스트 실행 — 통과 확인**

```bash
../gradlew :payment-service:test --tests "com.prompthub.paymentservice.infrastructure.persistence.RefundJpaRepositoryTest"
```
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/prompthub/paymentservice/domain/model/Refund.java \
        src/main/resources/schema.sql \
        src/test/java/com/prompthub/paymentservice/infrastructure/persistence/RefundJpaRepositoryTest.java
git commit -m "$(cat <<'EOF'
feat: Refund.orderProductId 필수화 + 상품당 1회 환불 유니크 제약

- 부분 환불이 OrderProduct 단위로만 존재하므로 orderProductId를 NOT NULL로 변경
- (payment_id, order_product_id) 유니크 인덱스로 같은 상품 중복 환불(이벤트 재전송 포함) 방지

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: PaymentRepository/RefundRepository 조회 메서드 추가

`ProcessRefundService`(Task 10)가 쓸 조회 메서드를 미리 추가한다: 주문 건으로 환불 가능한 Payment를 락 걸어 조회하는 메서드, 완료된 환불 누적액 계산에 쓸 메서드.

**Files:**
- Modify: `src/main/java/com/prompthub/paymentservice/domain/repository/PaymentRepository.java`
- Modify: `src/main/java/com/prompthub/paymentservice/infrastructure/persistence/PaymentJpaRepository.java`
- Modify: `src/main/java/com/prompthub/paymentservice/infrastructure/persistence/PaymentRepositoryAdapter.java`
- Modify: `src/main/java/com/prompthub/paymentservice/domain/repository/RefundRepository.java`
- Modify: `src/main/java/com/prompthub/paymentservice/infrastructure/persistence/RefundJpaRepository.java`
- Modify: `src/main/java/com/prompthub/paymentservice/infrastructure/persistence/RefundRepositoryAdapter.java`
- Test: `src/test/java/com/prompthub/paymentservice/infrastructure/persistence/PaymentJpaRepositoryTest.java`
- Test: `src/test/java/com/prompthub/paymentservice/infrastructure/persistence/RefundJpaRepositoryTest.java`

**Interfaces:**
- Produces: `PaymentRepository.findByOrderIdAndStatusInForUpdate(UUID orderId, Collection<PaymentStatus> statuses): Optional<Payment>`
- Produces: `RefundRepository.findByPaymentIdAndStatus(UUID paymentId, RefundStatus status): List<Refund>`
- Task 10이 이 두 메서드를 사용한다.

- [ ] **Step 1: `PaymentJpaRepositoryTest`에 실패하는 테스트 작성**

`src/test/java/com/prompthub/paymentservice/infrastructure/persistence/PaymentJpaRepositoryTest.java`가 없다면 먼저 확인한다:

```bash
find src/test -iname "PaymentJpaRepositoryTest.java"
```

없으면 아래 내용으로 신규 생성한다(있으면 아래 테스트만 파일 끝에 추가):

```java
package com.prompthub.paymentservice.infrastructure.persistence;

import com.prompthub.paymentservice.domain.model.Payment;
import com.prompthub.paymentservice.domain.model.PaymentStatus;
import com.prompthub.paymentservice.support.AbstractJpaTest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentJpaRepositoryTest extends AbstractJpaTest {

    @Autowired
    PaymentJpaRepository paymentJpaRepository;

    @Test
    void findByOrderIdAndStatusInForUpdate_PAID_상태_조회() {
        UUID orderId = UUID.randomUUID();
        Payment payment = Payment.create(
            orderId, UUID.randomUUID(), "pg-key", "TOSS_PAYMENTS", "CARD", false, 10_000);
        payment.markRequested(OffsetDateTime.now());
        payment.approve(10_000, "카드", "{}", OffsetDateTime.now());
        paymentJpaRepository.saveAndFlush(payment);

        Optional<Payment> found = paymentJpaRepository.findByOrderIdAndStatusInForUpdate(
            orderId, List.of(PaymentStatus.PAID, PaymentStatus.PARTIAL_REFUNDED));

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(payment.getId());
    }

    @Test
    void findByOrderIdAndStatusInForUpdate_대상_상태_아니면_빈값() {
        UUID orderId = UUID.randomUUID();
        Payment payment = Payment.create(
            orderId, UUID.randomUUID(), "pg-key2", "TOSS_PAYMENTS", "CARD", false, 10_000);
        paymentJpaRepository.saveAndFlush(payment); // READY 상태

        Optional<Payment> found = paymentJpaRepository.findByOrderIdAndStatusInForUpdate(
            orderId, List.of(PaymentStatus.PAID, PaymentStatus.PARTIAL_REFUNDED));

        assertThat(found).isEmpty();
    }
}
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

```bash
../gradlew :payment-service:test --tests "com.prompthub.paymentservice.infrastructure.persistence.PaymentJpaRepositoryTest"
```
Expected: FAIL — `findByOrderIdAndStatusInForUpdate` 메서드 없음(컴파일 에러)

- [ ] **Step 3: `PaymentJpaRepository`에 메서드 추가**

`infrastructure/persistence/PaymentJpaRepository.java`:

```java
package com.prompthub.paymentservice.infrastructure.persistence;

import com.prompthub.paymentservice.domain.model.Payment;
import com.prompthub.paymentservice.domain.model.PaymentStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentJpaRepository extends JpaRepository<Payment, UUID> {
    boolean existsByOrderIdAndStatusIn(UUID orderId, Collection<PaymentStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.id = :id")
    Optional<Payment> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.orderId = :orderId AND p.status IN :statuses")
    Optional<Payment> findByOrderIdAndStatusInForUpdate(
        @Param("orderId") UUID orderId, @Param("statuses") Collection<PaymentStatus> statuses);
}
```

- [ ] **Step 4: `PaymentRepository`/`PaymentRepositoryAdapter`에 위임 추가**

`domain/repository/PaymentRepository.java`에 메서드 추가:

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
}
```

`infrastructure/persistence/PaymentRepositoryAdapter.java`에 위임 메서드 추가:

```java
    @Override
    public Optional<Payment> findByOrderIdAndStatusInForUpdate(UUID orderId, Collection<PaymentStatus> statuses) {
        return jpaRepository.findByOrderIdAndStatusInForUpdate(orderId, statuses);
    }
```

- [ ] **Step 5: 테스트 실행 — 통과 확인**

```bash
../gradlew :payment-service:test --tests "com.prompthub.paymentservice.infrastructure.persistence.PaymentJpaRepositoryTest"
```
Expected: PASS

- [ ] **Step 6: `RefundJpaRepositoryTest`에 실패하는 테스트 추가**

`RefundJpaRepositoryTest.java` 끝에 추가:

```java
    @Test
    void findByPaymentIdAndStatus_COMPLETED_건만_조회() {
        UUID paymentId = UUID.randomUUID();
        Refund completed = Refund.create(paymentId, UUID.randomUUID(), 3_000, null, UUID.randomUUID());
        completed.complete(java.time.OffsetDateTime.now());
        Refund requested = Refund.create(paymentId, UUID.randomUUID(), 2_000, null, UUID.randomUUID());
        refundJpaRepository.saveAndFlush(completed);
        refundJpaRepository.saveAndFlush(requested);

        java.util.List<Refund> found = refundJpaRepository.findByPaymentIdAndStatus(paymentId, RefundStatus.COMPLETED);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).getRefundAmount()).isEqualTo(3_000);
    }
```

- [ ] **Step 7: 테스트 실행 — 실패 확인**

```bash
../gradlew :payment-service:test --tests "com.prompthub.paymentservice.infrastructure.persistence.RefundJpaRepositoryTest"
```
Expected: FAIL — `findByPaymentIdAndStatus` 없음(컴파일 에러)

- [ ] **Step 8: `RefundRepository`/`RefundJpaRepository`/`RefundRepositoryAdapter`에 메서드 추가**

`domain/repository/RefundRepository.java`:

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
}
```

`infrastructure/persistence/RefundJpaRepository.java`:

```java
package com.prompthub.paymentservice.infrastructure.persistence;

import com.prompthub.paymentservice.domain.model.Refund;
import com.prompthub.paymentservice.domain.model.RefundStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundJpaRepository extends JpaRepository<Refund, UUID> {
    List<Refund> findByPaymentIdAndStatus(UUID paymentId, RefundStatus status);
}
```

`infrastructure/persistence/RefundRepositoryAdapter.java`:

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
}
```

- [ ] **Step 9: 테스트 실행 — 통과 확인 + 전체 빌드**

```bash
../gradlew :payment-service:test --tests "com.prompthub.paymentservice.infrastructure.persistence.RefundJpaRepositoryTest"
../gradlew :payment-service:build
```
Expected: PASS, `BUILD SUCCESSFUL`

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat: 부분환불용 Payment/Refund 조회 메서드 추가

- PaymentRepository.findByOrderIdAndStatusInForUpdate: orderId로 환불 가능 Payment 락 조회
- RefundRepository.findByPaymentIdAndStatus: 완료된 환불 누적액 계산용

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: PaymentGateway.refund() 시그니처 변경 + TossPaymentGateway 버그 수정

`PaymentGateway.refund()`의 두 번째 파라미터를 `paymentId`에서 `refundId`로 바꾸고, `TossPaymentGateway`가 `cancelAmount`를 실제로 전달하도록, `Idempotency-Key`를 `refundId` 기준으로 생성하도록 고친다.

**Files:**
- Modify: `src/main/java/com/prompthub/paymentservice/application/gateway/external/PaymentGateway.java`
- Modify: `src/main/java/com/prompthub/paymentservice/infrastructure/external/toss/TossPaymentGateway.java`
- Test: `src/test/java/com/prompthub/paymentservice/infrastructure/external/toss/TossPaymentGatewayTest.java` (신규)

**Interfaces:**
- Produces: `PaymentGateway.refund(String pgTxId, UUID refundId, int amount): RefundResult` — Task 10이 `refund.getId()`를 두 번째 인자로 넘긴다.

- [ ] **Step 1: 실패하는 테스트 작성 — JDK 내장 `HttpServer`로 실제 요청 검증**

기존 코드베이스엔 `TossPaymentGateway` 전용 단위 테스트가 없다(지금까지 `confirm()`도 통합 테스트에서 `PaymentGateway` 인터페이스 자체를 목(mock)으로 대체해왔다). 이번엔 `cancelAmount`/`Idempotency-Key` 버그 수정을 실제로 검증해야 하므로, 새 의존성 추가 없이 JDK 내장 `com.sun.net.httpserver.HttpServer`로 로컬 HTTP 서버를 띄워 실제 요청 바디/헤더를 캡처한다.

`src/test/java/com/prompthub/paymentservice/infrastructure/external/toss/TossPaymentGatewayTest.java` 신규 생성:

```java
package com.prompthub.paymentservice.infrastructure.external.toss;

import com.prompthub.paymentservice.application.gateway.external.RefundResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class TossPaymentGatewayTest {

    private HttpServer server;
    private final AtomicReference<HttpExchange> capturedExchange = new AtomicReference<>();
    private final AtomicReference<String> capturedBody = new AtomicReference<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/payments/test-pg-key/cancel", exchange -> {
            capturedExchange.set(exchange);
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String responseBody = """
                {"status":"CANCELED","cancels":[{"canceledAt":"2026-07-13T10:00:00+09:00"}]}
                """;
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void refund_호출_시_cancelAmount와_refundId_기준_Idempotency_Key_전달() throws IOException {
        String baseUrl = "http://localhost:" + server.getAddress().getPort() + "/v1";
        TossPaymentGateway gateway = new TossPaymentGateway("test-secret-key", baseUrl, objectMapper);
        UUID refundId = UUID.randomUUID();

        RefundResult result = gateway.refund("test-pg-key", refundId, 3_000);

        assertThat(result.refundedAt()).isNotNull();

        JsonNode requestJson = objectMapper.readTree(capturedBody.get());
        assertThat(requestJson.get("cancelAmount").asInt()).isEqualTo(3_000);

        String idempotencyKey = capturedExchange.get().getRequestHeaders().getFirst("Idempotency-Key");
        assertThat(idempotencyKey).isEqualTo("refund-" + refundId);
    }
}
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

```bash
../gradlew :payment-service:test --tests "com.prompthub.paymentservice.infrastructure.external.toss.TossPaymentGatewayTest"
```
Expected: FAIL — `PaymentGateway.refund()` 시그니처가 아직 `UUID paymentId`라 컴파일은 되지만(같은 타입 `UUID`), `cancelAmount`가 `null`로 전송되어 `requestJson.get("cancelAmount").asInt()`가 NPE 또는 값 불일치로 실패. `Idempotency-Key`도 `"refund-" + refundId`가 아니라 실제로는 같은 값이 우연히 들어갈 수 있으니(둘 다 UUID 파라미터 위치가 같아서), 핵심 실패 지점은 `cancelAmount` 검증이다.

- [ ] **Step 3: `PaymentGateway` 인터페이스 파라미터명 변경**

`application/gateway/external/PaymentGateway.java`:

```java
package com.prompthub.paymentservice.application.gateway.external;

import java.util.UUID;

public interface PaymentGateway {
    ConfirmResult confirm(String paymentKey, UUID orderId, int amount);
    RefundResult refund(String pgTxId, UUID refundId, int amount);
}
```

- [ ] **Step 4: `TossPaymentGateway.refund()` 수정**

`infrastructure/external/toss/TossPaymentGateway.java`의 `refund` 메서드(108~150행)를 아래로 교체:

```java
    @Override
    public RefundResult refund(String pgTxId, UUID refundId, int amount) {
        TossRefundRequest request = new TossRefundRequest("구매자 환불 요청", amount);

        TossRefundResponse response = restClient.post()
            .uri("/payments/{paymentKey}/cancel", pgTxId)
            .header("Idempotency-Key", "refund-" + refundId)
            .contentType(MediaType.APPLICATION_JSON)
            .body(request)
            .retrieve()
            .onStatus(HttpStatusCode::is4xxClientError, (req, resp) -> {
                TossErrorResponse error = parseError(resp);
                PaymentErrorCode errorCode = TOSS_SERVER_ERROR_CODES.contains(error.code())
                    ? PaymentErrorCode.PG_INVALID_REQUEST
                    : PaymentErrorCode.PAYMENT_FAILED;
                throw new PaymentGatewayException(
                    errorCode, error.code(), error.message(), null, null
                );
            })
            .onStatus(HttpStatusCode::is5xxServerError, (req, resp) -> {
                TossErrorResponse error = parseError(resp);
                throw new PaymentGatewayException(
                    PaymentErrorCode.PG_SERVER_ERROR,
                    error.code(), error.message(),
                    null, null
                );
            })
            .body(TossRefundResponse.class);

        if (response == null) {
            throw new PaymentGatewayException(
                PaymentErrorCode.PG_SERVER_ERROR, "NULL_RESPONSE", "PG사 환불 응답이 없습니다.", null, null
            );
        }
        List<TossRefundResponse.TossCancel> cancels = response.cancels();
        if (cancels == null || cancels.isEmpty()) {
            throw new PaymentGatewayException(
                PaymentErrorCode.PG_SERVER_ERROR, "NO_CANCEL_DATA", "Toss 환불 응답에 취소 내역이 없습니다.", null, null
            );
        }
        TossRefundResponse.TossCancel lastCancel = cancels.get(cancels.size() - 1);
        return new RefundResult(lastCancel.canceledAt());
    }
```

(변경 지점: `TossRefundRequest("구매자 환불 요청", null)` → `TossRefundRequest("구매자 환불 요청", amount)`, `"refund-" + paymentId` → `"refund-" + refundId`, 파라미터명 `paymentId` → `refundId`.)

- [ ] **Step 5: 테스트 실행 — 통과 확인**

```bash
../gradlew :payment-service:test --tests "com.prompthub.paymentservice.infrastructure.external.toss.TossPaymentGatewayTest"
```
Expected: PASS

- [ ] **Step 6: 전체 빌드 확인** (Task 1~4에서 `PaymentGateway.refund` 호출부가 없어졌으므로 컴파일 영향 없음)

```bash
../gradlew :payment-service:build
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/prompthub/paymentservice/application/gateway/external/PaymentGateway.java \
        src/main/java/com/prompthub/paymentservice/infrastructure/external/toss/TossPaymentGateway.java \
        src/test/java/com/prompthub/paymentservice/infrastructure/external/toss/TossPaymentGatewayTest.java
git commit -m "$(cat <<'EOF'
fix: Toss 환불 API cancelAmount 미전달, Idempotency-Key 중복 버그 수정

- cancelAmount를 null 고정에서 실제 요청 금액으로 전달 — 그동안 부분금액 지정이 전액취소로 처리되던 버그
- Idempotency-Key를 paymentId 고정에서 refundId 기준으로 변경 — 같은 결제의 두 번째 환불이
  Toss의 첫 응답 캐시를 돌려받던 버그. 부분환불 도입 전엔 결제당 환불이 1건뿐이라 드러나지 않았음

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: 신규 도메인 이벤트 — `PaymentRefundedEvent` / `PaymentRefundFailedEvent`

**Files:**
- Create: `src/main/java/com/prompthub/paymentservice/domain/event/PaymentRefundedEvent.java`
- Create: `src/main/java/com/prompthub/paymentservice/domain/event/PaymentRefundFailedEvent.java`

**Interfaces:**
- Produces: `PaymentRefundedEvent(Payment payment, Refund refund)`, `PaymentRefundFailedEvent(Payment payment, Refund refund, String failureReason)` — Task 9(`KafkaPaymentEventPublisher`)와 Task 10(`ProcessRefundService`)이 사용.

이 태스크는 두 개의 순수 record라 실패하는 테스트 없이 바로 작성한다(관례상 `PaymentApprovedEvent`/`PaymentFailedEvent`도 별도 테스트가 없다).

- [ ] **Step 1: `PaymentRefundedEvent` 생성**

```java
package com.prompthub.paymentservice.domain.event;

import com.prompthub.paymentservice.domain.model.Payment;
import com.prompthub.paymentservice.domain.model.Refund;

public record PaymentRefundedEvent(Payment payment, Refund refund) {}
```

- [ ] **Step 2: `PaymentRefundFailedEvent` 생성**

```java
package com.prompthub.paymentservice.domain.event;

import com.prompthub.paymentservice.domain.model.Payment;
import com.prompthub.paymentservice.domain.model.Refund;

public record PaymentRefundFailedEvent(Payment payment, Refund refund, String failureReason) {}
```

- [ ] **Step 3: 컴파일 확인**

```bash
../gradlew :payment-service:compileJava
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/prompthub/paymentservice/domain/event/PaymentRefundedEvent.java \
        src/main/java/com/prompthub/paymentservice/domain/event/PaymentRefundFailedEvent.java
git commit -m "$(cat <<'EOF'
feat: 환불 성공/실패 도메인 이벤트 추가

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: Kafka 메시지 DTO — 컨슈머 payload / 발행 payload

**Files:**
- Create: `src/main/java/com/prompthub/paymentservice/infrastructure/messaging/dto/OrderRefundRequestedMessage.java`
- Create: `src/main/java/com/prompthub/paymentservice/infrastructure/messaging/dto/PaymentRefundedMessage.java` (기존 파일 덮어쓰기)
- Create: `src/main/java/com/prompthub/paymentservice/infrastructure/messaging/dto/PaymentRefundFailedMessage.java`

**Interfaces:**
- Produces: `OrderRefundRequestedMessage(UUID orderId, UUID orderProductId, UUID buyerId, int refundAmount, LocalDateTime requestedAt)` — Task 11(`OrderEventConsumer`)이 역직렬화에 사용.
- Produces: `PaymentRefundedMessage(UUID paymentId, UUID orderId, UUID userId, UUID orderProductId, int amount, String refundedAt)` — Task 9가 사용.
- Produces: `PaymentRefundFailedMessage(UUID paymentId, UUID orderId, UUID userId, UUID orderProductId, int refundAmount, String failureReason, String failedAt)` — Task 9가 사용.

- [ ] **Step 1: `OrderRefundRequestedMessage` 생성**

```java
package com.prompthub.paymentservice.infrastructure.messaging.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * order-events의 ORDER_REFUND_REQUESTED payload(EventMessage&lt;T&gt; 봉투 내부).
 * requestedAt은 order-service에서 존 없는 LocalDateTime으로 직렬화되므로, 소비 시 KST를 부여한다.
 */
public record OrderRefundRequestedMessage(
    UUID orderId,
    UUID orderProductId,
    UUID buyerId,
    int refundAmount,
    LocalDateTime requestedAt
) {}
```

- [ ] **Step 2: `PaymentRefundedMessage`에 `orderProductId` 필드 추가**

`infrastructure/messaging/dto/PaymentRefundedMessage.java` 전체 교체:

```java
package com.prompthub.paymentservice.infrastructure.messaging.dto;

import java.util.UUID;

public record PaymentRefundedMessage(
    UUID paymentId,
    UUID orderId,
    UUID userId,
    UUID orderProductId,
    int amount,
    String refundedAt
) {}
```

- [ ] **Step 3: `PaymentRefundFailedMessage` 생성**

```java
package com.prompthub.paymentservice.infrastructure.messaging.dto;

import java.util.UUID;

public record PaymentRefundFailedMessage(
    UUID paymentId,
    UUID orderId,
    UUID userId,
    UUID orderProductId,
    int refundAmount,
    String failureReason,
    String failedAt
) {}
```

- [ ] **Step 4: 컴파일 확인** (다음 태스크 전까지 `PaymentRefundedMessage`를 생성하는 코드가 없으므로 컴파일만 통과하면 충분)

```bash
../gradlew :payment-service:compileJava
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/prompthub/paymentservice/infrastructure/messaging/dto/OrderRefundRequestedMessage.java \
        src/main/java/com/prompthub/paymentservice/infrastructure/messaging/dto/PaymentRefundedMessage.java \
        src/main/java/com/prompthub/paymentservice/infrastructure/messaging/dto/PaymentRefundFailedMessage.java
git commit -m "$(cat <<'EOF'
feat: 부분환불 Kafka payload DTO 추가

- OrderRefundRequestedMessage: order-events ORDER_REFUND_REQUESTED 구독 payload
- PaymentRefundedMessage: orderProductId 필드 추가
- PaymentRefundFailedMessage: 환불 실패 발행 payload 신규

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 8: `PaymentEventType`에 `PAYMENT_REFUND_FAILED` 추가

**Files:**
- Modify: `src/main/java/com/prompthub/paymentservice/infrastructure/messaging/PaymentEventType.java`

- [ ] **Step 1: enum 값 추가**

```java
package com.prompthub.paymentservice.infrastructure.messaging;

import com.prompthub.common.event.EventType;

public enum PaymentEventType implements EventType {

    PAYMENT_APPROVED,
    PAYMENT_REFUNDED,
    PAYMENT_REFUND_FAILED,
    PAYMENT_FAILED;

    @Override
    public String code() {
        return name();
    }
}
```

- [ ] **Step 2: 컴파일 확인**

```bash
../gradlew :payment-service:compileJava
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/prompthub/paymentservice/infrastructure/messaging/PaymentEventType.java
git commit -m "$(cat <<'EOF'
feat: PAYMENT_REFUND_FAILED 이벤트 타입 추가

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 9: `KafkaPaymentEventPublisher` — 환불 성공/실패 발행 리스너

`onPaymentApproved`/`onPaymentFailed`와 동일한 표준 `@TransactionalEventListener(AFTER_COMMIT)` 패턴으로 `onPaymentRefunded`/`onPaymentRefundFailed`를 추가한다.

**Files:**
- Modify: `src/main/java/com/prompthub/paymentservice/infrastructure/messaging/KafkaPaymentEventPublisher.java`

**Interfaces:**
- Consumes: `PaymentRefundedEvent(Payment, Refund)`, `PaymentRefundFailedEvent(Payment, Refund, String)` (Task 6)
- Consumes: `PaymentRefundedMessage`, `PaymentRefundFailedMessage` (Task 7), `PaymentEventType.PAYMENT_REFUNDED`/`PAYMENT_REFUND_FAILED` (Task 8)
- Produces: `onPaymentRefunded(PaymentRefundedEvent)`, `onPaymentRefundFailed(PaymentRefundFailedEvent)` — Task 10이 `ApplicationEventPublisher.publishEvent(...)`로 발행하면 AFTER_COMMIT에 자동 호출됨(직접 호출 아님).

- [ ] **Step 1: `KafkaPaymentEventPublisher.java`에 메서드 추가**

파일 상단 import에 추가:

```java
import com.prompthub.paymentservice.domain.event.PaymentRefundedEvent;
import com.prompthub.paymentservice.domain.event.PaymentRefundFailedEvent;
import com.prompthub.paymentservice.domain.model.Refund;
import com.prompthub.paymentservice.infrastructure.messaging.dto.PaymentRefundedMessage;
import com.prompthub.paymentservice.infrastructure.messaging.dto.PaymentRefundFailedMessage;
```

`onPaymentFailed` 메서드 뒤(96행 이후)에 추가:

```java
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentRefunded(PaymentRefundedEvent event) {
        Payment payment = event.payment();
        Refund refund = event.refund();
        PaymentRefundedMessage payload = new PaymentRefundedMessage(
            payment.getId(),
            payment.getOrderId(),
            payment.getUserId(),
            refund.getOrderProductId(),
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
            payment.getId(),
            payment.getOrderId(),
            payment.getUserId(),
            refund.getOrderProductId(),
            refund.getRefundAmount(),
            event.failureReason(),
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

- [ ] **Step 2: 컴파일 확인**

```bash
../gradlew :payment-service:compileJava
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/prompthub/paymentservice/infrastructure/messaging/KafkaPaymentEventPublisher.java
git commit -m "$(cat <<'EOF'
feat: 환불 성공/실패 Kafka 발행 리스너 추가

- onPaymentRefunded/onPaymentRefundFailed — 기존 onPaymentApproved/onPaymentFailed와 동일한
  표준 @TransactionalEventListener(AFTER_COMMIT) 패턴

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 10: `ProcessRefundUseCase`/`ProcessRefundService` — 부분환불 핵심 로직

**Files:**
- Create: `src/main/java/com/prompthub/paymentservice/application/dto/command/ProcessRefundCommand.java`
- Create: `src/main/java/com/prompthub/paymentservice/application/usecase/ProcessRefundUseCase.java`
- Create: `src/main/java/com/prompthub/paymentservice/application/service/ProcessRefundService.java`
- Test: `src/test/java/com/prompthub/paymentservice/application/service/ProcessRefundServiceTest.java` (신규)

**Interfaces:**
- Consumes: `PaymentRepository.findByOrderIdAndStatusInForUpdate` (Task 4), `RefundRepository.findByPaymentIdAndStatus` (Task 4), `PaymentGateway.refund(pgTxId, refundId, amount)` (Task 5), `Payment.applyRefund(refundedAt, isFullyRefunded)` (Task 2), `Refund.create/complete/fail` (기존), `PaymentRefundedEvent`/`PaymentRefundFailedEvent` (Task 6)
- Produces: `ProcessRefundCommand(UUID orderId, UUID orderProductId, UUID buyerId, int refundAmount, OffsetDateTime requestedAt)`, `ProcessRefundUseCase.process(ProcessRefundCommand): void` — Task 11(`OrderEventConsumer`)이 호출.

- [ ] **Step 1: `ProcessRefundCommand` 생성**

```java
package com.prompthub.paymentservice.application.dto.command;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ProcessRefundCommand(
    UUID orderId,
    UUID orderProductId,
    UUID buyerId,
    int refundAmount,
    OffsetDateTime requestedAt
) {}
```

- [ ] **Step 2: `ProcessRefundUseCase` 인터페이스 생성**

```java
package com.prompthub.paymentservice.application.usecase;

import com.prompthub.paymentservice.application.dto.command.ProcessRefundCommand;

/**
 * order-service가 발행하는 ORDER_REFUND_REQUESTED 이벤트로 트리거되는
 * OrderProduct 단위 부분환불 처리. PG 호출까지 단일 트랜잭션 안에서 동기로 수행한다.
 */
public interface ProcessRefundUseCase {
    void process(ProcessRefundCommand command);
}
```

- [ ] **Step 3: 실패하는 단위 테스트 작성 — `ProcessRefundServiceTest`**

```java
package com.prompthub.paymentservice.application.service;

import com.prompthub.paymentservice.application.dto.command.ProcessRefundCommand;
import com.prompthub.paymentservice.application.gateway.external.PaymentGateway;
import com.prompthub.paymentservice.application.gateway.external.PaymentGatewayException;
import com.prompthub.paymentservice.application.gateway.external.RefundResult;
import com.prompthub.paymentservice.application.exception.PaymentErrorCode;
import com.prompthub.paymentservice.domain.event.PaymentRefundFailedEvent;
import com.prompthub.paymentservice.domain.event.PaymentRefundedEvent;
import com.prompthub.paymentservice.domain.exception.InvalidRefundStateException;
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
    void 결제_건_없으면_예외() {
        UUID orderId = UUID.randomUUID();
        when(paymentRepository.findByOrderIdAndStatusInForUpdate(any(), any())).thenReturn(Optional.empty());

        ProcessRefundCommand command = new ProcessRefundCommand(
            orderId, UUID.randomUUID(), UUID.randomUUID(), 3_000, OffsetDateTime.now());

        assertThatThrownBy(() -> service.process(command))
            .isInstanceOf(com.prompthub.exception.BusinessException.class)
            .extracting(e -> ((com.prompthub.exception.BusinessException) e).getErrorCode())
            .isEqualTo(PaymentErrorCode.PAYMENT_NOT_FOUND);

        verify(applicationEventPublisher, never()).publishEvent(any());
    }

    @Test
    void 누적_환불액_초과_시_예외() {
        Payment payment = 결제_생성_후_승인(10_000);
        when(paymentRepository.findByOrderIdAndStatusInForUpdate(any(), any())).thenReturn(Optional.of(payment));
        when(refundRepository.findByPaymentIdAndStatus(payment.getId(), RefundStatus.COMPLETED))
            .thenReturn(List.of(기존_완료_환불(payment.getId(), 8_000)));

        ProcessRefundCommand command = new ProcessRefundCommand(
            payment.getOrderId(), UUID.randomUUID(), payment.getUserId(), 3_000, OffsetDateTime.now());

        assertThatThrownBy(() -> service.process(command))
            .isInstanceOf(InvalidRefundStateException.class);

        verify(paymentGateway, never()).refund(anyString(), any(), anyInt());
    }

    @Test
    void 부분_환불_성공_시_PARTIAL_REFUNDED_전이() {
        Payment payment = 결제_생성_후_승인(10_000);
        UUID orderProductId = UUID.randomUUID();
        OffsetDateTime refundedAt = OffsetDateTime.now();
        when(paymentRepository.findByOrderIdAndStatusInForUpdate(any(), any())).thenReturn(Optional.of(payment));
        when(refundRepository.findByPaymentIdAndStatus(payment.getId(), RefundStatus.COMPLETED))
            .thenReturn(List.of());
        when(refundRepository.save(any(Refund.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentGateway.refund(anyString(), any(), anyInt())).thenReturn(new RefundResult(refundedAt));

        ProcessRefundCommand command = new ProcessRefundCommand(
            payment.getOrderId(), orderProductId, payment.getUserId(), 4_000, OffsetDateTime.now());
        service.process(command);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PARTIAL_REFUNDED);

        ArgumentCaptor<Refund> refundCaptor = ArgumentCaptor.forClass(Refund.class);
        verify(refundRepository).save(refundCaptor.capture());
        assertThat(refundCaptor.getValue().getRefundAmount()).isEqualTo(4_000);
        assertThat(refundCaptor.getValue().getOrderProductId()).isEqualTo(orderProductId);
        assertThat(refundCaptor.getValue().getStatus()).isEqualTo(RefundStatus.COMPLETED);

        ArgumentCaptor<PaymentRefundedEvent> eventCaptor = ArgumentCaptor.forClass(PaymentRefundedEvent.class);
        verify(applicationEventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().payment().getId()).isEqualTo(payment.getId());
    }

    @Test
    void 누적_환불액_totalAmount_도달_시_ALL_REFUNDED_전이() {
        Payment payment = 결제_생성_후_승인(10_000);
        when(paymentRepository.findByOrderIdAndStatusInForUpdate(any(), any())).thenReturn(Optional.of(payment));
        when(refundRepository.findByPaymentIdAndStatus(payment.getId(), RefundStatus.COMPLETED))
            .thenReturn(List.of(기존_완료_환불(payment.getId(), 6_000)));
        when(refundRepository.save(any(Refund.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentGateway.refund(anyString(), any(), anyInt())).thenReturn(new RefundResult(OffsetDateTime.now()));

        ProcessRefundCommand command = new ProcessRefundCommand(
            payment.getOrderId(), UUID.randomUUID(), payment.getUserId(), 4_000, OffsetDateTime.now());
        service.process(command);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.ALL_REFUNDED);
    }

    @Test
    void PG_실패_시_Refund_FAILED_Payment_상태_불변_및_실패_이벤트_발행() {
        Payment payment = 결제_생성_후_승인(10_000);
        when(paymentRepository.findByOrderIdAndStatusInForUpdate(any(), any())).thenReturn(Optional.of(payment));
        when(refundRepository.findByPaymentIdAndStatus(payment.getId(), RefundStatus.COMPLETED))
            .thenReturn(List.of());
        when(refundRepository.save(any(Refund.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentGateway.refund(anyString(), any(), anyInt()))
            .thenThrow(new PaymentGatewayException(PaymentErrorCode.PG_INVALID_REQUEST, "CANCEL_FAILED", "환불 실패", null, null));

        ProcessRefundCommand command = new ProcessRefundCommand(
            payment.getOrderId(), UUID.randomUUID(), payment.getUserId(), 4_000, OffsetDateTime.now());
        service.process(command);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);

        ArgumentCaptor<Refund> refundCaptor = ArgumentCaptor.forClass(Refund.class);
        verify(refundRepository).save(refundCaptor.capture());
        assertThat(refundCaptor.getValue().getStatus()).isEqualTo(RefundStatus.FAILED);

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
        Refund refund = Refund.create(paymentId, UUID.randomUUID(), amount, null, UUID.randomUUID());
        refund.complete(OffsetDateTime.now());
        return refund;
    }
}
```

- [ ] **Step 4: 테스트 실행 — 실패 확인**

```bash
../gradlew :payment-service:test --tests "com.prompthub.paymentservice.application.service.ProcessRefundServiceTest"
```
Expected: FAIL — `ProcessRefundService` 클래스 없음(컴파일 에러)

- [ ] **Step 5: `ProcessRefundService` 구현**

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
import com.prompthub.paymentservice.domain.exception.InvalidRefundStateException;
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
        Payment payment = paymentRepository
            .findByOrderIdAndStatusInForUpdate(command.orderId(), REFUNDABLE_STATUSES)
            .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND));

        int alreadyRefunded = refundRepository
            .findByPaymentIdAndStatus(payment.getId(), RefundStatus.COMPLETED)
            .stream()
            .mapToInt(Refund::getRefundAmount)
            .sum();
        int remaining = payment.getTotalAmount() - alreadyRefunded;

        if (command.refundAmount() > remaining) {
            throw new InvalidRefundStateException(
                "환불 가능 잔액을 초과했습니다. paymentId=" + payment.getId()
                    + ", remaining=" + remaining + ", requested=" + command.refundAmount());
        }

        Refund refund = Refund.create(
            payment.getId(), command.buyerId(), command.refundAmount(), null, command.orderProductId());
        refundRepository.save(refund);

        try {
            RefundResult result = paymentGateway.refund(payment.getPgTxId(), refund.getId(), command.refundAmount());
            refund.complete(result.refundedAt());
            payment.applyRefund(result.refundedAt(), command.refundAmount() == remaining);
            paymentRepository.save(payment);
            refundRepository.save(refund);
            applicationEventPublisher.publishEvent(new PaymentRefundedEvent(payment, refund));
        } catch (PaymentGatewayException e) {
            log.error("PG 환불 실패 — paymentId={}, orderProductId={}, code={}, reason={}",
                payment.getId(), command.orderProductId(), e.getFailureCode(), e.getFailureReason());
            refund.fail();
            refundRepository.save(refund);
            applicationEventPublisher.publishEvent(
                new PaymentRefundFailedEvent(payment, refund, e.getFailureReason()));
        }
    }
}
```

- [ ] **Step 6: 테스트 실행 — 통과 확인**

```bash
../gradlew :payment-service:test --tests "com.prompthub.paymentservice.application.service.ProcessRefundServiceTest"
```
Expected: PASS

- [ ] **Step 7: 전체 빌드 확인**

```bash
../gradlew :payment-service:build
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/prompthub/paymentservice/application/dto/command/ProcessRefundCommand.java \
        src/main/java/com/prompthub/paymentservice/application/usecase/ProcessRefundUseCase.java \
        src/main/java/com/prompthub/paymentservice/application/service/ProcessRefundService.java \
        src/test/java/com/prompthub/paymentservice/application/service/ProcessRefundServiceTest.java
git commit -m "$(cat <<'EOF'
feat: 부분환불 핵심 유즈케이스 ProcessRefundService 구현

- 단일 트랜잭션 안에서 PG 호출까지 동기 처리(REFUNDING 마커 불필요)
- 누적 환불액 기준으로 PARTIAL_REFUNDED/ALL_REFUNDED 판정
- PG 실패 시 Refund.FAILED만 남기고 Payment 상태는 유지, 실패 이벤트 발행

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 11: `OrderEventConsumer` — `ORDER_REFUND_REQUESTED` 라우팅

**Files:**
- Modify: `src/main/java/com/prompthub/paymentservice/infrastructure/messaging/consumer/OrderEventConsumer.java`
- Test: `src/test/java/com/prompthub/paymentservice/OrderEventConsumerIntegrationTest.java`

**Interfaces:**
- Consumes: `ProcessRefundUseCase.process(ProcessRefundCommand)` (Task 10), `OrderRefundRequestedMessage` (Task 7)

- [ ] **Step 1: 실패하는 통합 테스트 작성**

`OrderEventConsumerIntegrationTest.java`에 아래 추가(파일 상단 import에 `Payment`, `PaymentJpaRepository`, `PaymentStatus`, `RefundJpaRepository`, `OrderSnapshotSource` 관련은 이미 있으니 필요한 것만 보강):

```java
import com.prompthub.paymentservice.application.gateway.external.PaymentGateway;
import com.prompthub.paymentservice.application.gateway.external.RefundResult;
import com.prompthub.paymentservice.domain.model.Payment;
import com.prompthub.paymentservice.domain.model.PaymentStatus;
import com.prompthub.paymentservice.infrastructure.persistence.PaymentJpaRepository;
import com.prompthub.paymentservice.infrastructure.persistence.RefundJpaRepository;
import java.time.OffsetDateTime;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
```

클래스 필드/설정에 추가:

```java
    @Autowired
    PaymentJpaRepository paymentJpaRepository;

    @Autowired
    RefundJpaRepository refundJpaRepository;

    @MockitoBean
    PaymentGateway paymentGateway;

    @BeforeEach
    void cleanPaymentAndRefund() {
        refundJpaRepository.deleteAll();
        paymentJpaRepository.deleteAll();
    }
```

(기존 `@BeforeEach void clean()`이 있으니 별도 메서드명으로 추가하거나, 하나의 `@BeforeEach`로 합쳐도 된다. 합치는 편이 간단하므로 기존 `clean()` 메서드에 두 줄을 추가하는 방식을 권장한다:)

```java
    @BeforeEach
    void clean() {
        orderSnapshotJpaRepository.deleteAll();
        refundJpaRepository.deleteAll();
        paymentJpaRepository.deleteAll();
    }
```

테스트 메서드 추가:

```java
    @Test
    void ORDER_REFUND_REQUESTED_수신_시_부분환불_처리() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID orderProductId = UUID.randomUUID();
        OffsetDateTime refundedAt = OffsetDateTime.now();

        Payment payment = Payment.create(orderId, userId, "pg-key-refund-1", "TOSS_PAYMENTS", "CARD", false, 10_000);
        payment.markRequested(OffsetDateTime.now());
        payment.approve(10_000, "카드", "{}", OffsetDateTime.now());
        paymentJpaRepository.saveAndFlush(payment);

        when(paymentGateway.refund(anyString(), any(), anyInt())).thenReturn(new RefundResult(refundedAt));

        send(orderId.toString(), orderRefundRequestedJson(orderId, orderProductId, userId, 4_000));

        await().atMost(Duration.ofSeconds(15))
            .pollInterval(Duration.ofMillis(300))
            .untilAsserted(() -> {
                Payment updated = paymentJpaRepository.findById(payment.getId()).orElseThrow();
                assertThat(updated.getStatus()).isEqualTo(PaymentStatus.PARTIAL_REFUNDED);
            });

        long refundCount = refundJpaRepository.findAll().stream()
            .filter(r -> r.getPaymentId().equals(payment.getId()))
            .count();
        assertThat(refundCount).isEqualTo(1);
    }

    private String orderRefundRequestedJson(UUID orderId, UUID orderProductId, UUID buyerId, int refundAmount) {
        return String.format(
            "{\"eventId\":\"%s\",\"eventType\":\"ORDER_REFUND_REQUESTED\",\"occurredAt\":\"2026-07-13T10:00:00\","
                + "\"aggregateType\":\"ORDER\",\"aggregateId\":\"%s\",\"payload\":{"
                + "\"orderId\":\"%s\",\"orderProductId\":\"%s\",\"buyerId\":\"%s\","
                + "\"refundAmount\":%d,\"requestedAt\":\"2026-07-13T10:00:00\"}}",
            UUID.randomUUID(), orderId, orderId, orderProductId, buyerId, refundAmount);
    }
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

```bash
../gradlew :payment-service:test --tests "com.prompthub.paymentservice.OrderEventConsumerIntegrationTest"
```
Expected: FAIL — `ORDER_REFUND_REQUESTED`는 아직 무시 대상이라 `PARTIAL_REFUNDED`로 전이되지 않음(await 타임아웃)

- [ ] **Step 3: `OrderEventConsumer` 라우팅 추가**

`infrastructure/messaging/consumer/OrderEventConsumer.java` 전체 교체:

```java
package com.prompthub.paymentservice.infrastructure.messaging.consumer;

import com.prompthub.paymentservice.application.dto.command.ProcessRefundCommand;
import com.prompthub.paymentservice.application.dto.command.RecordOrderSnapshotCommand;
import com.prompthub.paymentservice.application.usecase.ProcessRefundUseCase;
import com.prompthub.paymentservice.application.usecase.RecordOrderSnapshotUseCase;
import com.prompthub.paymentservice.domain.model.OrderSnapshotSource;
import com.prompthub.paymentservice.infrastructure.messaging.dto.OrderCreatedMessage;
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
 * ORDER_CREATED / ORDER_REFUND_REQUESTED만 처리하고, 그 외 eventType(ORDER_PAID 등)은 무시한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventConsumer {

    private static final String TOPIC_ORDER_EVENTS = "order-events";
    private static final String GROUP_ID = "payment-service-order-events";
    private static final String EVENT_TYPE_ORDER_CREATED = "ORDER_CREATED";
    private static final String EVENT_TYPE_ORDER_REFUND_REQUESTED = "ORDER_REFUND_REQUESTED";

    // createdAt/requestedAt(LocalDateTime, 존 없음)에 부여할 존 — payment의 KST 표기 관례와 일치
    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    private final ObjectMapper objectMapper;
    private final RecordOrderSnapshotUseCase recordOrderSnapshotUseCase;
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

            if (EVENT_TYPE_ORDER_CREATED.equals(eventType)) {
                handleOrderCreated(payload);
            } else if (EVENT_TYPE_ORDER_REFUND_REQUESTED.equals(eventType)) {
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

    private void handleOrderCreated(JsonNode payload) {
        OrderCreatedMessage created = objectMapper.treeToValue(payload, OrderCreatedMessage.class);
        validate(created);

        recordOrderSnapshotUseCase.record(new RecordOrderSnapshotCommand(
            created.orderId(),
            created.buyerId(),
            created.totalAmount(),
            created.createdAt().atOffset(KST),
            OrderSnapshotSource.EVENT
        ));
        log.info("주문 스냅샷 저장 — orderId={}", created.orderId());
    }

    private void handleOrderRefundRequested(JsonNode payload) {
        OrderRefundRequestedMessage requested = objectMapper.treeToValue(payload, OrderRefundRequestedMessage.class);
        validateRefund(requested);

        processRefundUseCase.process(new ProcessRefundCommand(
            requested.orderId(),
            requested.orderProductId(),
            requested.buyerId(),
            requested.refundAmount(),
            requested.requestedAt().atOffset(KST)
        ));
        log.info("부분환불 처리 완료 — orderId={}, orderProductId={}", requested.orderId(), requested.orderProductId());
    }

    private void validate(OrderCreatedMessage message) {
        if (message.orderId() == null || message.buyerId() == null || message.createdAt() == null) {
            throw new IllegalArgumentException("ORDER_CREATED 필수 필드 누락: " + message);
        }
    }

    private void validateRefund(OrderRefundRequestedMessage message) {
        if (message.orderId() == null || message.orderProductId() == null
            || message.buyerId() == null || message.requestedAt() == null) {
            throw new IllegalArgumentException("ORDER_REFUND_REQUESTED 필수 필드 누락: " + message);
        }
    }
}
```

- [ ] **Step 4: 테스트 실행 — 통과 확인**

```bash
../gradlew :payment-service:test --tests "com.prompthub.paymentservice.OrderEventConsumerIntegrationTest"
```
Expected: PASS

- [ ] **Step 5: 전체 테스트 스위트 확인**

```bash
../gradlew :payment-service:test
```
Expected: `BUILD SUCCESSFUL`, 모든 테스트 통과

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/prompthub/paymentservice/infrastructure/messaging/consumer/OrderEventConsumer.java \
        src/test/java/com/prompthub/paymentservice/OrderEventConsumerIntegrationTest.java
git commit -m "$(cat <<'EOF'
feat: order-events ORDER_REFUND_REQUESTED 구독 → 부분환불 처리 연동

- OrderEventConsumer가 ORDER_CREATED와 ORDER_REFUND_REQUESTED를 함께 라우팅
- ProcessRefundUseCase 호출로 실제 부분환불 트랜잭션 실행

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 12: 추가 통합 테스트 — 누적 ALL_REFUNDED 도달 / PG 실패

**Files:**
- Create: `src/test/java/com/prompthub/paymentservice/PartialRefundIntegrationTest.java`

**Interfaces:**
- Consumes: 위 태스크들에서 만든 모든 컴포넌트(엔드투엔드 시나리오 검증용, 신규 인터페이스 없음)

- [ ] **Step 1: 통합 테스트 작성**

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

        send(orderId.toString(), json(orderId, UUID.randomUUID(), userId, 6_000));

        await().atMost(Duration.ofSeconds(15))
            .pollInterval(Duration.ofMillis(300))
            .untilAsserted(() -> {
                Payment updated = paymentJpaRepository.findById(payment.getId()).orElseThrow();
                assertThat(updated.getStatus()).isEqualTo(PaymentStatus.PARTIAL_REFUNDED);
            });

        send(orderId.toString(), json(orderId, UUID.randomUUID(), userId, 4_000));

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

        send(orderId.toString(), json(orderId, UUID.randomUUID(), userId, 4_000));

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

    private String json(UUID orderId, UUID orderProductId, UUID buyerId, int refundAmount) {
        return String.format(
            "{\"eventId\":\"%s\",\"eventType\":\"ORDER_REFUND_REQUESTED\",\"occurredAt\":\"2026-07-13T10:00:00\","
                + "\"aggregateType\":\"ORDER\",\"aggregateId\":\"%s\",\"payload\":{"
                + "\"orderId\":\"%s\",\"orderProductId\":\"%s\",\"buyerId\":\"%s\","
                + "\"refundAmount\":%d,\"requestedAt\":\"2026-07-13T10:00:00\"}}",
            UUID.randomUUID(), orderId, orderId, orderProductId, buyerId, refundAmount);
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

- [ ] **Step 2: 테스트 실행**

```bash
../gradlew :payment-service:test --tests "com.prompthub.paymentservice.PartialRefundIntegrationTest"
```
Expected: PASS (Task 9~11이 이미 구현되어 있으므로 별도 구현 스텝 없이 바로 통과해야 한다 — 실패하면 Task 9~11 구현을 재검토)

- [ ] **Step 3: 전체 스위트 재확인**

```bash
../gradlew :payment-service:test
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/prompthub/paymentservice/PartialRefundIntegrationTest.java
git commit -m "$(cat <<'EOF'
test: 부분환불 누적 ALL_REFUNDED 도달 및 PG 실패 시나리오 통합 테스트 추가

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 13: 문서 갱신 — `api-design.md` / `events.md` / `db-schema.md`

**Files:**
- Modify: `.claude/docs/api-design.md`
- Modify: `.claude/docs/events.md`
- Modify: `.claude/docs/db-schema.md`

- [ ] **Step 1: `api-design.md`에서 환불 REST 섹션 제거, 이벤트 기반 설명으로 교체**

`### POST /api/v1/payments/{paymentId}/refund — 환불 요청` 섹션(95~147행) 전체를 아래로 교체:

```markdown
### 환불 — 이벤트 기반 (REST 없음)

환불은 REST 엔드포인트가 아니라 order-service가 발행하는 Kafka 이벤트로 트리거된다.
OrderProduct 단위로만 존재하며, 주문 전체를 환불하려면 order-service가 상품 수만큼 이벤트를 여러 번 발행한다.

**이벤트 계약**: `order-events` 토픽의 `ORDER_REFUND_REQUESTED` — 상세 스키마는 `events.md` 참조.

**처리 흐름**
1. `OrderEventConsumer`가 `ORDER_REFUND_REQUESTED` 수신
2. `orderId`로 `PAID`/`PARTIAL_REFUNDED` 상태 Payment 조회(락)
3. 누적 환불액이 `total_amount`를 넘으면 처리 중단(DLT)
4. PG 환불 동기 호출(단일 트랜잭션 안에서 수행 — REFUNDING 같은 진행중 마커 없음)
5. 성공: 누적액이 `total_amount`에 도달했으면 `ALL_REFUNDED`, 아니면 `PARTIAL_REFUNDED`로 전이 + `payment-events`에 `PAYMENT_REFUNDED` 발행
6. 실패: `Refund.FAILED`만 기록, Payment 상태는 그대로 + `payment-events`에 `PAYMENT_REFUND_FAILED` 발행. 재시도 장치 없음(필요 시 order-service가 이벤트 재발행)

**금액 검증**: order-service가 보낸 `refundAmount`를 그대로 신뢰한다(payment-service는 상품별 가격 정보를 갖고 있지 않음). 누적 환불액이 결제 총액을 넘지 않는지만 확인한다.
```

`## PaymentStatus` 표(151~161행)를 아래로 교체:

```markdown
## PaymentStatus

| 상태 | 설명 |
|---|---|
| `READY` | Payment 레코드 생성, PG 요청 전 |
| `REQUESTED` | PG사에 결제 요청 전송 완료 |
| `PAID` | PG사 결제 승인 완료 |
| `FAILED` | PG사 결제 실패 |
| `PARTIAL_REFUNDED` | 일부 OrderProduct 환불 완료, 잔여 환불 가능액 존재 |
| `ALL_REFUNDED` | 누적 환불액이 결제 총액에 도달 |
| `UNKNOWN` | PG 응답 불명확, 수동 확인 필요 |
```

- [ ] **Step 2: `events.md` 갱신**

`## 구독 토픽` 표(26~30행) 아래에 행 추가:

```markdown
| `order-events` | `ORDER_REFUND_REQUESTED` | `payment-service-order-events` (전용) | OrderProduct 단위 부분환불 처리(PG 호출 포함 동기) | 재시도 3회(1s) 후 `order-events.DLT` |
```

`### PAYMENT_REFUNDED` 섹션(96~122행)의 payload 예시/표에 `orderProductId` 추가:

```json
{
  "eventId": "9c1f2a7e-4b8d-4e2a-9c11-2d3e4f5a0002",
  "eventType": "PAYMENT_REFUNDED",
  "occurredAt": "2026-06-15T20:00:00",
  "aggregateType": "ORDER",
  "aggregateId": "660e8400-e29b-41d4-a716-446655440001",
  "payload": {
    "paymentId":  "550e8400-e29b-41d4-a716-446655440000",
    "orderId":    "660e8400-e29b-41d4-a716-446655440001",
    "userId":     "770e8400-e29b-41d4-a716-446655440002",
    "orderProductId": "880e8400-e29b-41d4-a716-446655440003",
    "amount":     4000,
    "refundedAt": "2026-06-15T20:00:00+09:00"
  }
}
```

| 필드 (`payload`) | 타입 | 필수 | 설명 |
|---|---|---|---|
| `paymentId` | UUID | ✅ | Payment ID |
| `orderId` | UUID | ✅ | 주문 ID |
| `userId` | UUID | ✅ | 환불 요청 사용자 ID |
| `orderProductId` | UUID | ✅ | 환불된 OrderProduct ID |
| `amount` | Int | ✅ | 이번 환불 건(해당 상품)의 금액 |
| `refundedAt` | ISO 8601 (KST) | ✅ | PG 환불 완료 일시 |

`### PAYMENT_FAILED` 섹션 뒤에 새 섹션 추가:

```markdown
### PAYMENT_REFUND_FAILED

PG 환불 실패 시 발행. `Refund.status=FAILED`로만 기록되고 Payment 상태는 그대로 유지된다.

```json
{
  "eventId": "9c1f2a7e-4b8d-4e2a-9c11-2d3e4f5a0004",
  "eventType": "PAYMENT_REFUND_FAILED",
  "occurredAt": "2026-06-15T20:05:00",
  "aggregateType": "ORDER",
  "aggregateId": "660e8400-e29b-41d4-a716-446655440001",
  "payload": {
    "paymentId": "550e8400-e29b-41d4-a716-446655440000",
    "orderId":   "660e8400-e29b-41d4-a716-446655440001",
    "userId":    "770e8400-e29b-41d4-a716-446655440002",
    "orderProductId": "880e8400-e29b-41d4-a716-446655440003",
    "refundAmount": 4000,
    "failureReason": "잘못된 요청",
    "failedAt": "2026-06-15T20:05:00+09:00"
  }
}
```

| 필드 (`payload`) | 타입 | 필수 | 설명 |
|---|---|---|---|
| `paymentId` | UUID | ✅ | Payment ID |
| `orderId` | UUID | ✅ | 주문 ID |
| `userId` | UUID | ✅ | 결제 사용자 ID |
| `orderProductId` | UUID | ✅ | 환불 시도된 OrderProduct ID |
| `refundAmount` | Int | ✅ | 시도했던 환불 금액 |
| `failureReason` | String | — | PG 실패 사유(nullable) |
| `failedAt` | ISO 8601 (KST) | ✅ | 실패 처리 일시 |

구독자(order-service) 반응: 자기 쪽 반품 상태를 실패로 되돌리거나 재시도 여부 판단.
```

`## 구독 Payload 스키마` 섹션 뒤(또는 적절한 위치)에 `ORDER_REFUND_REQUESTED` 구독 스키마 추가:

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
    "refundAmount": 4000,
    "requestedAt": "2026-07-13T10:00:00"
  }
}
```

| 필드 (`payload`) | 타입 | 필수 | 설명 |
|---|---|---|---|
| `orderId` | UUID | ✅ | 주문 ID |
| `orderProductId` | UUID | ✅ | 환불 대상 OrderProduct ID (항상 필수 — 주문 전체 한번에 환불하는 이벤트는 없음) |
| `buyerId` | UUID | ✅ | 환불 요청 사용자 ID (참고용 로그, 별도 거부 로직 없음) |
| `refundAmount` | Int | ✅ | 환불 금액 — payment-service는 이 값을 그대로 신뢰(누적 초과 여부만 검증) |
| `requestedAt` | LocalDateTime | ✅ | 존 없음 → 소비 시 KST 부여 |
```

- [ ] **Step 3: `db-schema.md` 갱신**

`### payment_status` 표(11~20행)를 아래로 교체:

```markdown
### payment_status
| 값 | 설명 |
|---|---|
| `READY` | Payment 레코드 생성, PG 요청 전 |
| `REQUESTED` | PG사에 결제 요청 전송 완료 |
| `PAID` | PG사 결제 승인 완료 |
| `FAILED` | PG사 결제 실패 |
| `PARTIAL_REFUNDED` | 일부 OrderProduct 환불 완료, 잔여 환불 가능액 존재 |
| `ALL_REFUNDED` | 누적 환불액이 결제 총액에 도달 |
| `UNKNOWN` | PG 응답 불명확, 수동 확인 필요 |
```

`## refund 테이블` 표의 `order_product_id` 행(90행)을 수정:

```markdown
| `order_product_id` | UUID | ✅ | — | 환불 대상 OrderProduct ID. 부분환불만 존재하므로 항상 필수 |
```

`## refund 테이블` 섹션 끝(98행 이후)에 인덱스 설명 추가:

```markdown

**인덱스** (`schema.sql`):

| 인덱스 | 대상 | 목적 |
|---|---|---|
| `uk_refund_payment_order_product` | UNIQUE (`payment_id`, `order_product_id`) | 같은 결제의 같은 상품 중복 환불(이벤트 재전송 포함) 방지 |
```

- [ ] **Step 4: Commit**

```bash
git add .claude/docs/api-design.md .claude/docs/events.md .claude/docs/db-schema.md
git commit -m "$(cat <<'EOF'
docs: 부분환불(이벤트 기반) 반영 — api-design/events/db-schema

- 전체환불 REST 섹션 제거, ORDER_REFUND_REQUESTED 이벤트 기반 흐름으로 대체
- PaymentStatus PARTIAL_REFUNDED/ALL_REFUNDED 반영
- PAYMENT_REFUNDED payload에 orderProductId 추가, PAYMENT_REFUND_FAILED 신규 문서화
- refund 테이블 유니크 인덱스 문서화

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Self-Review 체크리스트 (계획 작성자용, 실행 전 확인)

- **스펙 커버리지**: `.claude/plans/15-partial-refund.md`의 확정 사항 1~8 전부 Task 1~13에 반영됨(REST 삭제=Task1, 상태개편=Task2, orderProductId 필수화=Task3, 조회메서드=Task4, PG 버그수정=Task5, 이벤트=Task6·8·9, DTO=Task7, 핵심로직=Task10, 컨슈머 연동=Task11, 통합테스트=Task12, 문서=Task13).
- **플레이스홀더 스캔**: TBD/TODO 없음. 모든 코드 블록은 실제 완성 코드.
- **타입 일관성**: `ProcessRefundCommand`/`ProcessRefundUseCase`/`ProcessRefundService`/`OrderEventConsumer`/`ProcessRefundServiceTest` 전체에서 필드명·타입 동일(`orderId`, `orderProductId`, `buyerId`, `refundAmount`, `requestedAt`). `PaymentGateway.refund(pgTxId, refundId, amount)` 시그니처가 Task5·Task10에서 일치.
- **알려진 리스크**: Task3의 `schema.sql` 마이그레이션(`DELETE FROM refund WHERE order_product_id IS NULL`)은 기존 로컬 개발 DB에 이미 NULL 행이 있으면 데이터 삭제를 유발한다 — 로컬 개발자 각자 확인 필요(운영 DB 아님, 세미 MVP 단계).
