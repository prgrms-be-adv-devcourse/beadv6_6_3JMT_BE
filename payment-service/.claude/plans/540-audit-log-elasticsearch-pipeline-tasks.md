# 감사로그 Elasticsearch 파이프라인 구현 태스크 목록

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** write-only Postgres `audit_log`를 Kafka(실시간) + Logstash http_poller(재조정)로 Elasticsearch에 미러링해, Kibana 검색으로 CS·장애분석·감사대응 조회 니즈를 충족한다.

**Architecture:** `AuditLogEventListener`가 감사로그 저장 성공 시 신규 내부 전용 Kafka 토픽(`payment-audit-log`)에 발행한다. payment-service는 재조정용 내부 엔드포인트(`GET /internal/audit-logs`)를 노출한다. Logstash가 이 토픽을 구독하는 입력과, 저 엔드포인트를 5분마다 폴링하는 http_poller 입력을 **격리된 별도 파이프라인**(`pipelines.yml`)으로 운영해 기존 `gateway-access`/`application-logs` 파이프라인 코드를 전혀 건드리지 않는다. 두 입력 모두 문서ID를 `audit_log.id`로 고정해 upsert 멱등성을 확보한다.

**Tech Stack:** Spring Boot 4.1, Spring Kafka, Spring Data JPA, Logstash 9.4.3(kafka input, http_poller input, split filter — 기본 번들 플러그인), Elasticsearch 9.4.3, Kibana 9.4.3, JUnit5 + Mockito + AssertJ, Testcontainers(PostgreSQL, Kafka).

## Global Constraints

- 문서·커밋 메시지·테스트 메서드명: 한국어. 클래스/필드/메서드 식별자: 영어. 커밋 형식 `type: 한국어 설명`(payment-service/.claude/rules/git-conventions.md).
- 통합 테스트는 Testcontainers(PostgreSQL + Kafka), EmbeddedKafka 금지. 단언은 AssertJ.
- `AuditLog` 도메인 모델·`AuditEventType`/`AuditEntityType` enum은 기존 그대로 재사용, 변경하지 않는다.
- `payment-audit-log` Kafka 토픽은 내부 전용 — `payment-events`(외부 서비스 계약)에 얹지 않는다.
- `/internal/audit-logs`는 Gateway 라우팅 표에 등록하지 않는다(무인증 전제가 여기서 성립).
- k8s/addons/elk, `.github/workflows/cd-selfhosted-kubernetes.yml` 수정은 payment-service 리포 밖이지만 사용자 승인 완료(설계 문서 §Elasticsearch/Logstash/Kibana, §배포).
- **`.github/workflows/cd-selfhosted-kubernetes.yml`의 기존 `kubectl delete job application-logs-ilm-bootstrap application-logs-kibana-bootstrap -n elk --ignore-not-found` 및 두 `kubectl wait --for=condition=complete job/application-logs-*` 라인은 글자 하나도 바꾸지 않는다.** `scripts/validate-k8s-cd-workflow.sh`가 이 3줄을 `grep -Eq`(부분 문자열 매치)로 검사하므로, 중간에 새 잡 이름을 끼워 넣으면 매치가 깨져 CI가 실패한다. 새 잡은 반드시 **별도의 새 줄**로 추가한다.
- 기존 `k8s/addons/elk/logstash.yaml`의 `gateway-access.conf` 내용(입력/필터/출력)은 한 글자도 수정하지 않는다 — 신규 파이프라인은 `pipelines.yml`로 완전히 격리한다(아래 Task 6 근거 참조).

---

### Task 1: Kafka 토픽 설정 + AuditLogMessage + AuditLogKafkaPublisher ✅

**Files:**
- Modify: `payment-service/src/main/java/com/prompthub/payment/infrastructure/messaging/config/PaymentTopic.java`
- Modify: `payment-service/src/main/java/com/prompthub/payment/infrastructure/messaging/config/KafkaConfig.java`
- Create: `payment-service/src/main/java/com/prompthub/payment/infrastructure/messaging/dto/AuditLogMessage.java`
- Create: `payment-service/src/main/java/com/prompthub/payment/infrastructure/messaging/AuditLogKafkaPublisher.java`
- Test: `payment-service/src/test/java/com/prompthub/payment/infrastructure/messaging/AuditLogKafkaPublisherTest.java`

**Interfaces:**
- Produces: `PaymentTopic.AUDIT_LOG`(String 상수), `AuditLogMessage`(record, 필드는 `AuditLog` getter와 1:1 대응), `AuditLogKafkaPublisher.publish(AuditLog auditLog): void`(Task 2가 사용).

- [x] **Step 1: 실패하는 테스트 작성**

`AuditLogKafkaPublisherTest.java`:

```java
package com.prompthub.payment.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prompthub.payment.domain.model.AuditLog;
import com.prompthub.payment.domain.model.Payment;
import com.prompthub.payment.infrastructure.messaging.config.PaymentTopic;
import com.prompthub.payment.infrastructure.messaging.dto.AuditLogMessage;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class AuditLogKafkaPublisherTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);

    private final AuditLogKafkaPublisher publisher = new AuditLogKafkaPublisher(kafkaTemplate);

    @Test
    @SuppressWarnings("unchecked")
    void 감사로그를_payment_audit_log_토픽으로_발행한다() {
        SendResult<String, Object> sendResult = mock(SendResult.class);
        RecordMetadata recordMetadata = mock(RecordMetadata.class);
        when(sendResult.getRecordMetadata()).thenReturn(recordMetadata);
        when(kafkaTemplate.send(anyString(), anyString(), any()))
            .thenReturn(CompletableFuture.completedFuture(sendResult));

        Payment payment = Payment.create(
            UUID.randomUUID(), UUID.randomUUID(), "pgTx-1", "TOSS_PAYMENTS", "CARD", 10_000);
        payment.markRequested(OffsetDateTime.now());
        payment.approve(10_000, "CARD", "{}", "{}", OffsetDateTime.now());
        AuditLog auditLog = AuditLog.forPaymentApproved(payment);

        publisher.publish(auditLog);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq(PaymentTopic.AUDIT_LOG), eq(auditLog.getId().toString()), captor.capture());

        AuditLogMessage message = (AuditLogMessage) captor.getValue();
        assertThat(message.id()).isEqualTo(auditLog.getId());
        assertThat(message.orderId()).isEqualTo(auditLog.getOrderId());
        assertThat(message.entityType()).isEqualTo("PAYMENT");
        assertThat(message.entityId()).isEqualTo(auditLog.getEntityId());
        assertThat(message.eventType()).isEqualTo("PAYMENT_APPROVED");
        assertThat(message.actorId()).isEqualTo(auditLog.getActorId());
        assertThat(message.newStatus()).isEqualTo("PAID");
        assertThat(message.failureCode()).isNull();
        assertThat(message.detail()).isNull();
        assertThat(message.occurredAt()).isEqualTo(auditLog.getOccurredAt());
    }
}
```

- [x] **Step 2: 테스트 실패 확인**

Run: `JAVA_HOME=~/.asdf/installs/java/temurin-21.0.5+11.0.LTS ../gradlew :payment-service:test --tests "com.prompthub.payment.infrastructure.messaging.AuditLogKafkaPublisherTest"`
Expected: FAIL — `AuditLogMessage`/`AuditLogKafkaPublisher` 클래스가 없어 컴파일 실패.

- [x] **Step 3: 최소 구현**

`PaymentTopic.java`에 추가:

```java
public static final String AUDIT_LOG = "payment-audit-log";
```

`KafkaConfig.java`에 추가(기존 import에 `org.apache.kafka.common.config.TopicConfig`, `java.time.Duration` 추가):

```java
@Bean
public NewTopic auditLogTopic() {
    return TopicBuilder.name(PaymentTopic.AUDIT_LOG)
        .partitions(1)
        .replicas(1)
        .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(Duration.ofDays(3).toMillis()))
        .build();
}
```

`AuditLogMessage.java`:

```java
package com.prompthub.payment.infrastructure.messaging.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AuditLogMessage(
    UUID id,
    UUID orderId,
    String entityType,
    UUID entityId,
    String eventType,
    UUID actorId,
    String newStatus,
    String failureCode,
    String detail,
    OffsetDateTime occurredAt,
    OffsetDateTime createdAt
) {}
```

`AuditLogKafkaPublisher.java`:

```java
package com.prompthub.payment.infrastructure.messaging;

import com.prompthub.payment.domain.model.AuditLog;
import com.prompthub.payment.infrastructure.messaging.config.PaymentTopic;
import com.prompthub.payment.infrastructure.messaging.dto.AuditLogMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuditLogKafkaPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publish(AuditLog auditLog) {
        AuditLogMessage message = new AuditLogMessage(
            auditLog.getId(), auditLog.getOrderId(), auditLog.getEntityType().name(),
            auditLog.getEntityId(), auditLog.getEventType().name(), auditLog.getActorId(),
            auditLog.getNewStatus(), auditLog.getFailureCode(), auditLog.getDetail(),
            auditLog.getOccurredAt(), auditLog.getCreatedAt()
        );
        kafkaTemplate.send(PaymentTopic.AUDIT_LOG, auditLog.getId().toString(), message)
            .whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("감사로그 Kafka 발행 실패 — auditLogId={}, cause={}", auditLog.getId(), ex.getMessage());
                } else {
                    log.info("감사로그 Kafka 발행 성공 — auditLogId={}, partition={}, offset={}",
                        auditLog.getId(), result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
                }
            });
    }
}
```

- [x] **Step 4: 테스트 통과 확인**

Run: `JAVA_HOME=~/.asdf/installs/java/temurin-21.0.5+11.0.LTS ../gradlew :payment-service:test --tests "com.prompthub.payment.infrastructure.messaging.AuditLogKafkaPublisherTest"`
Expected: PASS

- [x] **Step 5: Commit**

```bash
git add payment-service/src/main/java/com/prompthub/payment/infrastructure/messaging/config/PaymentTopic.java \
  payment-service/src/main/java/com/prompthub/payment/infrastructure/messaging/config/KafkaConfig.java \
  payment-service/src/main/java/com/prompthub/payment/infrastructure/messaging/dto/AuditLogMessage.java \
  payment-service/src/main/java/com/prompthub/payment/infrastructure/messaging/AuditLogKafkaPublisher.java \
  payment-service/src/test/java/com/prompthub/payment/infrastructure/messaging/AuditLogKafkaPublisherTest.java
git commit -m "feat: 감사로그 Kafka 발행 컴포넌트 추가"
```

---

### Task 2: AuditLogEventListener — 저장 성공 시 Kafka 발행 ✅

**Files:**
- Modify: `payment-service/src/main/java/com/prompthub/payment/infrastructure/persistence/AuditLogEventListener.java`
- Modify: `payment-service/src/test/java/com/prompthub/payment/infrastructure/persistence/AuditLogEventListenerTest.java`

**Interfaces:**
- Consumes: `AuditLogKafkaPublisher.publish(AuditLog): void`(Task 1)

- [x] **Step 1: 실패하는 테스트로 갱신**

`AuditLogEventListenerTest.java` 전체를 아래로 교체(생성자에 `auditLogKafkaPublisher` 추가, `save` 스텁을 입력값 그대로 반환하도록 전역 설정, 발행 검증 추가):

```java
package com.prompthub.payment.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.prompthub.payment.domain.event.PaymentApprovedEvent;
import com.prompthub.payment.domain.event.PaymentFailedEvent;
import com.prompthub.payment.domain.event.PaymentRefundFailedEvent;
import com.prompthub.payment.domain.event.PaymentRefundedEvent;
import com.prompthub.payment.domain.event.PaymentRequestedEvent;
import com.prompthub.payment.domain.model.AuditEntityType;
import com.prompthub.payment.domain.model.AuditEventType;
import com.prompthub.payment.domain.model.AuditLog;
import com.prompthub.payment.domain.model.Payment;
import com.prompthub.payment.domain.model.Refund;
import com.prompthub.payment.domain.repository.AuditLogRepository;
import com.prompthub.payment.infrastructure.messaging.AuditLogKafkaPublisher;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AuditLogEventListenerTest {

    private final AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
    private final AuditLogKafkaPublisher auditLogKafkaPublisher = mock(AuditLogKafkaPublisher.class);
    private final AuditLogEventListener listener =
        new AuditLogEventListener(auditLogRepository, auditLogKafkaPublisher);

    @BeforeEach
    void setUp() {
        when(auditLogRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void 결제_승인_이벤트_수신_시_감사로그를_저장하고_Kafka로_발행한다() {
        Payment payment = Payment.create(
            UUID.randomUUID(), UUID.randomUUID(), "pgTx-1", "TOSS_PAYMENTS", "CARD", 10_000);
        payment.markRequested(OffsetDateTime.now());
        payment.approve(10_000, "CARD", "{}", "{}", OffsetDateTime.now());

        listener.onPaymentApproved(new PaymentApprovedEvent(payment));

        ArgumentCaptor<AuditLog> savedCaptor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(savedCaptor.capture());
        AuditLog auditLog = savedCaptor.getValue();
        assertThat(auditLog.getOrderId()).isEqualTo(payment.getOrderId());
        assertThat(auditLog.getEntityType()).isEqualTo(AuditEntityType.PAYMENT);
        assertThat(auditLog.getEntityId()).isEqualTo(payment.getId());
        assertThat(auditLog.getEventType()).isEqualTo(AuditEventType.PAYMENT_APPROVED);
        assertThat(auditLog.getActorId()).isEqualTo(payment.getUserId());
        assertThat(auditLog.getNewStatus()).isEqualTo("PAID");
        assertThat(auditLog.getFailureCode()).isNull();
        assertThat(auditLog.getDetail()).isNull();

        ArgumentCaptor<AuditLog> publishedCaptor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogKafkaPublisher).publish(publishedCaptor.capture());
        assertThat(publishedCaptor.getValue()).isSameAs(auditLog);
    }

    @Test
    void 결제_실패_이벤트_수신_시_실패코드와_사유를_감사로그에_저장한다() {
        Payment payment = Payment.create(
            UUID.randomUUID(), UUID.randomUUID(), "pgTx-2", "TOSS_PAYMENTS", "CARD", 10_000);
        payment.markRequested(OffsetDateTime.now());
        payment.fail("REJECT", "카드 거절", "{}", "{}", OffsetDateTime.now());

        listener.onPaymentFailed(new PaymentFailedEvent(payment));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        AuditLog auditLog = captor.getValue();
        assertThat(auditLog.getOrderId()).isEqualTo(payment.getOrderId());
        assertThat(auditLog.getEventType()).isEqualTo(AuditEventType.PAYMENT_FAILED);
        assertThat(auditLog.getNewStatus()).isEqualTo("FAILED");
        assertThat(auditLog.getFailureCode()).isEqualTo("REJECT");
        assertThat(auditLog.getDetail()).isEqualTo("카드 거절");
        verify(auditLogKafkaPublisher).publish(auditLog);
    }

    @Test
    void 환불_완료_이벤트_수신_시_REFUND_REQUESTED와_REFUND_COMPLETED_두_건을_저장하고_각각_발행한다() {
        Payment payment = Payment.create(
            UUID.randomUUID(), UUID.randomUUID(), "pgTx-3", "TOSS_PAYMENTS", "CARD", 10_000);
        payment.markRequested(OffsetDateTime.now());
        payment.approve(10_000, "CARD", "{}", "{}", OffsetDateTime.now());
        Refund refund = Refund.create(payment.getId(), UUID.randomUUID(), 4_000, "단순 변심");
        refund.complete(OffsetDateTime.now());

        listener.onPaymentRefunded(new PaymentRefundedEvent(payment, refund));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository, times(2)).save(captor.capture());
        List<AuditLog> saved = captor.getAllValues();

        assertThat(saved).extracting(AuditLog::getEventType)
            .containsExactly(AuditEventType.REFUND_REQUESTED, AuditEventType.REFUND_COMPLETED);
        assertThat(saved).allSatisfy(log -> {
            assertThat(log.getOrderId()).isEqualTo(payment.getOrderId());
            assertThat(log.getEntityType()).isEqualTo(AuditEntityType.REFUND);
            assertThat(log.getEntityId()).isEqualTo(refund.getId());
        });
        verify(auditLogKafkaPublisher, times(2)).publish(any(AuditLog.class));
    }

    @Test
    void 환불_실패_이벤트_수신_시_REFUND_REQUESTED와_REFUND_FAILED_두_건을_저장하고_각각_발행한다() {
        Payment payment = Payment.create(
            UUID.randomUUID(), UUID.randomUUID(), "pgTx-4", "TOSS_PAYMENTS", "CARD", 10_000);
        payment.markRequested(OffsetDateTime.now());
        payment.approve(10_000, "CARD", "{}", "{}", OffsetDateTime.now());
        Refund refund = Refund.create(payment.getId(), UUID.randomUUID(), 4_000, "단순 변심");
        refund.fail("CANCEL_FAILED", "PG 오류", OffsetDateTime.now());

        listener.onPaymentRefundFailed(new PaymentRefundFailedEvent(payment, refund));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository, times(2)).save(captor.capture());
        List<AuditLog> saved = captor.getAllValues();

        assertThat(saved).extracting(AuditLog::getEventType)
            .containsExactly(AuditEventType.REFUND_REQUESTED, AuditEventType.REFUND_FAILED);
        assertThat(saved.get(1).getFailureCode()).isEqualTo("CANCEL_FAILED");
        assertThat(saved.get(1).getDetail()).isEqualTo("PG 오류");
        verify(auditLogKafkaPublisher, times(2)).publish(any(AuditLog.class));
    }

    @Test
    void 결제_요청_이벤트_수신_시_감사로그를_저장한다() {
        Payment payment = Payment.create(
            UUID.randomUUID(), UUID.randomUUID(), "pgTx-6", "TOSS_PAYMENTS", "CARD", 10_000);
        payment.markRequested(OffsetDateTime.now());

        listener.onPaymentRequested(new PaymentRequestedEvent(payment));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        AuditLog auditLog = captor.getValue();
        assertThat(auditLog.getOrderId()).isEqualTo(payment.getOrderId());
        assertThat(auditLog.getEntityType()).isEqualTo(AuditEntityType.PAYMENT);
        assertThat(auditLog.getEntityId()).isEqualTo(payment.getId());
        assertThat(auditLog.getEventType()).isEqualTo(AuditEventType.PAYMENT_REQUESTED);
        assertThat(auditLog.getActorId()).isEqualTo(payment.getUserId());
        assertThat(auditLog.getNewStatus()).isEqualTo("REQUESTED");
        assertThat(auditLog.getFailureCode()).isNull();
        assertThat(auditLog.getDetail()).isNull();
        verify(auditLogKafkaPublisher).publish(auditLog);
    }

    @Test
    void 감사로그_저장_실패해도_예외를_전파하지_않고_Kafka_발행도_하지_않는다() {
        Payment payment = Payment.create(
            UUID.randomUUID(), UUID.randomUUID(), "pgTx-5", "TOSS_PAYMENTS", "CARD", 10_000);
        payment.markRequested(OffsetDateTime.now());
        payment.approve(10_000, "CARD", "{}", "{}", OffsetDateTime.now());
        when(auditLogRepository.save(any())).thenThrow(new RuntimeException("DB down"));

        assertDoesNotThrow(() -> listener.onPaymentApproved(new PaymentApprovedEvent(payment)));

        verifyNoInteractions(auditLogKafkaPublisher);
    }

    @Test
    void Kafka_발행_실패해도_예외를_전파하지_않는다() {
        Payment payment = Payment.create(
            UUID.randomUUID(), UUID.randomUUID(), "pgTx-7", "TOSS_PAYMENTS", "CARD", 10_000);
        payment.markRequested(OffsetDateTime.now());
        payment.approve(10_000, "CARD", "{}", "{}", OffsetDateTime.now());
        org.mockito.Mockito.doThrow(new RuntimeException("Kafka down"))
            .when(auditLogKafkaPublisher).publish(any(AuditLog.class));

        assertDoesNotThrow(() -> listener.onPaymentApproved(new PaymentApprovedEvent(payment)));

        verify(auditLogRepository, times(1)).save(any());
    }
}
```

- [x] **Step 2: 테스트 실패 확인**

Run: `JAVA_HOME=~/.asdf/installs/java/temurin-21.0.5+11.0.LTS ../gradlew :payment-service:test --tests "com.prompthub.payment.infrastructure.persistence.AuditLogEventListenerTest"`
Expected: FAIL — 생성자 시그니처 불일치로 컴파일 실패.

- [x] **Step 3: 최소 구현**

`AuditLogEventListener.java` 전체 교체:

```java
package com.prompthub.payment.infrastructure.persistence;

import com.prompthub.payment.domain.event.PaymentApprovedEvent;
import com.prompthub.payment.domain.event.PaymentFailedEvent;
import com.prompthub.payment.domain.event.PaymentRefundFailedEvent;
import com.prompthub.payment.domain.event.PaymentRefundedEvent;
import com.prompthub.payment.domain.event.PaymentRequestedEvent;
import com.prompthub.payment.domain.model.AuditLog;
import com.prompthub.payment.domain.repository.AuditLogRepository;
import com.prompthub.payment.infrastructure.messaging.AuditLogKafkaPublisher;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuditLogEventListener {

    private final AuditLogRepository auditLogRepository;
    private final AuditLogKafkaPublisher auditLogKafkaPublisher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentRequested(PaymentRequestedEvent event) {
        save(AuditLog.forPaymentRequested(event.payment()), event.payment().getId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentApproved(PaymentApprovedEvent event) {
        save(AuditLog.forPaymentApproved(event.payment()), event.payment().getId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentFailed(PaymentFailedEvent event) {
        save(AuditLog.forPaymentFailed(event.payment()), event.payment().getId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentRefunded(PaymentRefundedEvent event) {
        save(AuditLog.forRefundRequested(event.payment(), event.refund()), event.refund().getId());
        save(AuditLog.forRefundCompleted(event.payment(), event.refund()), event.refund().getId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentRefundFailed(PaymentRefundFailedEvent event) {
        save(AuditLog.forRefundRequested(event.payment(), event.refund()), event.refund().getId());
        save(AuditLog.forRefundFailed(event.payment(), event.refund()), event.refund().getId());
    }

    private void save(AuditLog auditLog, UUID entityId) {
        AuditLog saved;
        try {
            saved = auditLogRepository.save(auditLog);
        } catch (Exception e) {
            log.error("감사로그 저장 실패 — entityId={}, eventType={}, cause={}",
                entityId, auditLog.getEventType(), e.getMessage());
            return;
        }
        try {
            auditLogKafkaPublisher.publish(saved);
        } catch (Exception e) {
            log.error("감사로그 Kafka 발행 실패 — entityId={}, eventType={}, cause={}",
                entityId, auditLog.getEventType(), e.getMessage());
        }
    }
}
```

Step 1에서 만든 테스트 파일 중 마지막 `Kafka_발행_실패해도_예외를_전파하지_않는다` 테스트에서 모순되는 `verify(auditLogRepository, never()).save(any())` 줄을 삭제한다(같은 테스트에 `times(1)` 검증이 이미 있음).

- [x] **Step 4: 테스트 통과 확인**

Run: `JAVA_HOME=~/.asdf/installs/java/temurin-21.0.5+11.0.LTS ../gradlew :payment-service:test --tests "com.prompthub.payment.infrastructure.persistence.AuditLogEventListenerTest"`
Expected: PASS (7개 테스트)

- [x] **Step 5: Commit**

```bash
git add payment-service/src/main/java/com/prompthub/payment/infrastructure/persistence/AuditLogEventListener.java \
  payment-service/src/test/java/com/prompthub/payment/infrastructure/persistence/AuditLogEventListenerTest.java
git commit -m "feat: 감사로그 저장 성공 시 Kafka 발행 연동"
```

---

### Task 3: AuditLogRepository — 재조정용 조회 계약 추가 ✅

**Files:**
- Modify: `payment-service/src/main/java/com/prompthub/payment/domain/repository/AuditLogRepository.java`
- Modify: `payment-service/src/main/java/com/prompthub/payment/infrastructure/persistence/AuditLogRepositoryAdapter.java`
- Modify: `payment-service/src/main/java/com/prompthub/payment/infrastructure/persistence/AuditLogJpaRepository.java`
- Test: `payment-service/src/test/java/com/prompthub/payment/infrastructure/persistence/AuditLogJpaRepositoryTest.java`(기존 파일에 테스트 메서드 추가)

**Interfaces:**
- Produces: `AuditLogRepository.findAllCreatedAfter(OffsetDateTime since, int limit): List<AuditLog>`(Task 4가 사용)

- [x] **Step 1: 실패하는 테스트 추가**

`AuditLogJpaRepositoryTest.java`에 아래 테스트와 import(`java.util.List`, `org.springframework.data.domain.PageRequest`)를 추가:

```java
    @Test
    void createdAt_이후_로우를_오름차순으로_상한개수만큼_조회한다() {
        Payment payment = Payment.create(
            UUID.randomUUID(), UUID.randomUUID(), "pg-tx-audit-3", "TOSS_PAYMENTS", "CARD", 10_000);
        payment.markRequested(OffsetDateTime.now());
        payment.approve(10_000, "CARD", "{}", "{}", OffsetDateTime.now());
        AuditLog first = auditLogJpaRepository.saveAndFlush(AuditLog.forPaymentApproved(payment));
        AuditLog second = auditLogJpaRepository.saveAndFlush(AuditLog.forPaymentApproved(payment));
        AuditLog third = auditLogJpaRepository.saveAndFlush(AuditLog.forPaymentApproved(payment));

        List<AuditLog> result = auditLogJpaRepository.findByCreatedAtGreaterThanEqualOrderByCreatedAtAsc(
            first.getCreatedAt(), PageRequest.of(0, 2));

        assertThat(result).hasSize(2);
        assertThat(result).extracting(AuditLog::getId).containsExactly(first.getId(), second.getId());
        assertThat(result).extracting(AuditLog::getId).doesNotContain(third.getId());
    }
```

- [x] **Step 2: 테스트 실패 확인**

Run: `JAVA_HOME=~/.asdf/installs/java/temurin-21.0.5+11.0.LTS ../gradlew :payment-service:test --tests "com.prompthub.payment.infrastructure.persistence.AuditLogJpaRepositoryTest"`
Expected: FAIL — 메서드 없어 컴파일 실패.

- [x] **Step 3: 최소 구현**

`AuditLogJpaRepository.java`:

```java
package com.prompthub.payment.infrastructure.persistence;

import com.prompthub.payment.domain.model.AuditLog;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogJpaRepository extends JpaRepository<AuditLog, UUID> {
    List<AuditLog> findByCreatedAtGreaterThanEqualOrderByCreatedAtAsc(OffsetDateTime since, Pageable pageable);
}
```

`AuditLogRepository.java`(domain):

```java
package com.prompthub.payment.domain.repository;

import com.prompthub.payment.domain.model.AuditLog;
import java.time.OffsetDateTime;
import java.util.List;

public interface AuditLogRepository {
    AuditLog save(AuditLog auditLog);

    List<AuditLog> findAllCreatedAfter(OffsetDateTime since, int limit);
}
```

`AuditLogRepositoryAdapter.java`:

```java
package com.prompthub.payment.infrastructure.persistence;

import com.prompthub.payment.domain.model.AuditLog;
import com.prompthub.payment.domain.repository.AuditLogRepository;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AuditLogRepositoryAdapter implements AuditLogRepository {

    private final AuditLogJpaRepository jpaRepository;

    @Override
    public AuditLog save(AuditLog auditLog) {
        return jpaRepository.save(auditLog);
    }

    @Override
    public List<AuditLog> findAllCreatedAfter(OffsetDateTime since, int limit) {
        return jpaRepository.findByCreatedAtGreaterThanEqualOrderByCreatedAtAsc(since, PageRequest.of(0, limit));
    }
}
```

- [x] **Step 4: 테스트 통과 확인**

Run: `JAVA_HOME=~/.asdf/installs/java/temurin-21.0.5+11.0.LTS ../gradlew :payment-service:test --tests "com.prompthub.payment.infrastructure.persistence.AuditLogJpaRepositoryTest"`
Expected: PASS

- [x] **Step 5: Commit**

```bash
git add payment-service/src/main/java/com/prompthub/payment/domain/repository/AuditLogRepository.java \
  payment-service/src/main/java/com/prompthub/payment/infrastructure/persistence/AuditLogRepositoryAdapter.java \
  payment-service/src/main/java/com/prompthub/payment/infrastructure/persistence/AuditLogJpaRepository.java \
  payment-service/src/test/java/com/prompthub/payment/infrastructure/persistence/AuditLogJpaRepositoryTest.java
git commit -m "feat: 감사로그 재조정 조회(findAllCreatedAfter) 계약 추가"
```

---

### Task 4: GetAuditLogsSinceUseCase + Service ✅

**Files:**
- Create: `payment-service/src/main/java/com/prompthub/payment/application/dto/result/AuditLogResult.java`
- Create: `payment-service/src/main/java/com/prompthub/payment/application/usecase/GetAuditLogsSinceUseCase.java`
- Create: `payment-service/src/main/java/com/prompthub/payment/application/service/GetAuditLogsSinceService.java`
- Test: `payment-service/src/test/java/com/prompthub/payment/application/service/GetAuditLogsSinceServiceTest.java`

**Interfaces:**
- Consumes: `AuditLogRepository.findAllCreatedAfter(OffsetDateTime, int): List<AuditLog>`(Task 3)
- Produces: `GetAuditLogsSinceUseCase.getSince(OffsetDateTime since): List<AuditLogResult>`(Task 5가 사용)

- [x] **Step 1: 실패하는 테스트 작성**

```java
package com.prompthub.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prompthub.payment.application.dto.result.AuditLogResult;
import com.prompthub.payment.domain.model.AuditLog;
import com.prompthub.payment.domain.model.Payment;
import com.prompthub.payment.domain.repository.AuditLogRepository;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class GetAuditLogsSinceServiceTest {

    private final AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
    private final GetAuditLogsSinceService service = new GetAuditLogsSinceService(auditLogRepository);

    @Test
    void since_이후_감사로그를_결과로_변환하고_2000건_상한을_적용한다() {
        Payment payment = Payment.create(
            java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
            "pgTx-1", "TOSS_PAYMENTS", "CARD", 10_000);
        payment.markRequested(OffsetDateTime.now());
        payment.approve(10_000, "CARD", "{}", "{}", OffsetDateTime.now());
        AuditLog auditLog = AuditLog.forPaymentApproved(payment);
        OffsetDateTime since = OffsetDateTime.now().minusMinutes(15);
        when(auditLogRepository.findAllCreatedAfter(eq(since), anyInt())).thenReturn(List.of(auditLog));

        List<AuditLogResult> results = service.getSince(since);

        verify(auditLogRepository).findAllCreatedAfter(eq(since), eq(2000));
        assertThat(results).hasSize(1);
        AuditLogResult result = results.get(0);
        assertThat(result.id()).isEqualTo(auditLog.getId());
        assertThat(result.orderId()).isEqualTo(auditLog.getOrderId());
        assertThat(result.entityType()).isEqualTo("PAYMENT");
        assertThat(result.eventType()).isEqualTo("PAYMENT_APPROVED");
        assertThat(result.newStatus()).isEqualTo("PAID");
        assertThat(result.occurredAt()).isEqualTo(auditLog.getOccurredAt());
        assertThat(result.createdAt()).isEqualTo(auditLog.getCreatedAt());
    }
}
```

- [x] **Step 2: 테스트 실패 확인**

Run: `JAVA_HOME=~/.asdf/installs/java/temurin-21.0.5+11.0.LTS ../gradlew :payment-service:test --tests "com.prompthub.payment.application.service.GetAuditLogsSinceServiceTest"`
Expected: FAIL — 클래스 없어 컴파일 실패.

- [x] **Step 3: 최소 구현**

`AuditLogResult.java`:

```java
package com.prompthub.payment.application.dto.result;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AuditLogResult(
    UUID id,
    UUID orderId,
    String entityType,
    UUID entityId,
    String eventType,
    UUID actorId,
    String newStatus,
    String failureCode,
    String detail,
    OffsetDateTime occurredAt,
    OffsetDateTime createdAt
) {}
```

`GetAuditLogsSinceUseCase.java`:

```java
package com.prompthub.payment.application.usecase;

import com.prompthub.payment.application.dto.result.AuditLogResult;
import java.time.OffsetDateTime;
import java.util.List;

public interface GetAuditLogsSinceUseCase {
    List<AuditLogResult> getSince(OffsetDateTime since);
}
```

`GetAuditLogsSinceService.java`:

```java
package com.prompthub.payment.application.service;

import com.prompthub.payment.application.dto.result.AuditLogResult;
import com.prompthub.payment.application.usecase.GetAuditLogsSinceUseCase;
import com.prompthub.payment.domain.model.AuditLog;
import com.prompthub.payment.domain.repository.AuditLogRepository;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GetAuditLogsSinceService implements GetAuditLogsSinceUseCase {

    private static final int MAX_RESULTS = 2000;

    private final AuditLogRepository auditLogRepository;

    @Override
    public List<AuditLogResult> getSince(OffsetDateTime since) {
        return auditLogRepository.findAllCreatedAfter(since, MAX_RESULTS).stream()
            .map(this::toResult)
            .toList();
    }

    private AuditLogResult toResult(AuditLog auditLog) {
        return new AuditLogResult(
            auditLog.getId(), auditLog.getOrderId(), auditLog.getEntityType().name(),
            auditLog.getEntityId(), auditLog.getEventType().name(), auditLog.getActorId(),
            auditLog.getNewStatus(), auditLog.getFailureCode(), auditLog.getDetail(),
            auditLog.getOccurredAt(), auditLog.getCreatedAt()
        );
    }
}
```

- [x] **Step 4: 테스트 통과 확인**

Run: `JAVA_HOME=~/.asdf/installs/java/temurin-21.0.5+11.0.LTS ../gradlew :payment-service:test --tests "com.prompthub.payment.application.service.GetAuditLogsSinceServiceTest"`
Expected: PASS

- [x] **Step 5: Commit**

```bash
git add payment-service/src/main/java/com/prompthub/payment/application/dto/result/AuditLogResult.java \
  payment-service/src/main/java/com/prompthub/payment/application/usecase/GetAuditLogsSinceUseCase.java \
  payment-service/src/main/java/com/prompthub/payment/application/service/GetAuditLogsSinceService.java \
  payment-service/src/test/java/com/prompthub/payment/application/service/GetAuditLogsSinceServiceTest.java
git commit -m "feat: 감사로그 since 조회 UseCase 추가"
```

---

### Task 5: AuditLogQueryController — 내부 재조정 엔드포인트 ✅

**Files:**
- Create: `payment-service/src/main/java/com/prompthub/payment/presentation/dto/response/AuditLogResponse.java`
- Create: `payment-service/src/main/java/com/prompthub/payment/presentation/AuditLogQueryController.java`
- Test: `payment-service/src/test/java/com/prompthub/payment/AuditLogQueryControllerIntegrationTest.java`

**Interfaces:**
- Consumes: `GetAuditLogsSinceUseCase.getSince(OffsetDateTime): List<AuditLogResult>`(Task 4)
- Produces: `GET /internal/audit-logs?since={ISO8601}`(생략 시 `now-15분`). 응답 바디: `{"success":true,"data":[{...}],"message":"success"}` — Task 6 Logstash http_poller가 이 모양을 그대로 소비한다.

- [x] **Step 1: 실패하는 통합 테스트 작성**

```java
package com.prompthub.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.prompthub.payment.domain.model.AuditLog;
import com.prompthub.payment.domain.model.Payment;
import com.prompthub.payment.infrastructure.persistence.AuditLogJpaRepository;
import com.prompthub.payment.support.AbstractIntegrationTest;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

class AuditLogQueryControllerIntegrationTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;

    RestTemplate restTemplate = new RestTemplate();

    @Autowired
    AuditLogJpaRepository auditLogJpaRepository;

    @Test
    void since_파라미터로_그_이후_감사로그만_반환한다() {
        Payment payment = Payment.create(
            UUID.randomUUID(), UUID.randomUUID(), "pgTx-query-1", "TOSS_PAYMENTS", "CARD", 10_000);
        payment.markRequested(OffsetDateTime.now());
        payment.approve(10_000, "CARD", "{}", "{}", OffsetDateTime.now());
        AuditLog before = auditLogJpaRepository.saveAndFlush(AuditLog.forPaymentApproved(payment));
        OffsetDateTime since = before.getCreatedAt().plusNanos(1_000);
        AuditLog after = auditLogJpaRepository.saveAndFlush(AuditLog.forPaymentApproved(payment));

        // OffsetDateTime.toString()은 "+09:00"처럼 쿼리스트링에서 문제되는 문자를 포함하므로
        // 문자열 이어붙이기 대신 UriComponentsBuilder로 인코딩한다(서블릿 컨테이너가 디코딩되지 않은
        // "+"를 공백으로 해석해 파싱이 깨지는 것을 방지).
        URI uri = UriComponentsBuilder.fromHttpUrl("http://localhost:" + port + "/internal/audit-logs")
            .queryParam("since", since.toString())
            .build()
            .encode()
            .toUri();
        ResponseEntity<String> response = restTemplate.getForEntity(uri, String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).contains(after.getId().toString());
        assertThat(response.getBody()).doesNotContain(before.getId().toString());
    }

    @Test
    void since_파라미터_생략_시_기본_15분_윈도우로_동작한다() {
        String url = "http://localhost:" + port + "/internal/audit-logs";
        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).contains("\"success\":true");
    }
}
```

- [x] **Step 2: 테스트 실패 확인**

Run: `JAVA_HOME=~/.asdf/installs/java/temurin-21.0.5+11.0.LTS ../gradlew :payment-service:test --tests "com.prompthub.payment.AuditLogQueryControllerIntegrationTest"`
Expected: FAIL — 404(컨트롤러 없음).

- [x] **Step 3: 최소 구현**

`AuditLogResponse.java`:

```java
package com.prompthub.payment.presentation.dto.response;

import com.prompthub.payment.application.dto.result.AuditLogResult;
import java.time.OffsetDateTime;
import java.util.UUID;

public record AuditLogResponse(
    UUID id,
    UUID orderId,
    String entityType,
    UUID entityId,
    String eventType,
    UUID actorId,
    String newStatus,
    String failureCode,
    String detail,
    OffsetDateTime occurredAt,
    OffsetDateTime createdAt
) {
    public static AuditLogResponse from(AuditLogResult result) {
        return new AuditLogResponse(
            result.id(), result.orderId(), result.entityType(), result.entityId(),
            result.eventType(), result.actorId(), result.newStatus(), result.failureCode(),
            result.detail(), result.occurredAt(), result.createdAt()
        );
    }
}
```

`AuditLogQueryController.java`:

```java
package com.prompthub.payment.presentation;

import com.prompthub.payment.application.usecase.GetAuditLogsSinceUseCase;
import com.prompthub.payment.presentation.dto.response.AuditLogResponse;
import com.prompthub.presentation.dto.ApiResult;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Logstash http_poller 재조정 전용 내부 엔드포인트.
 * Gateway 라우팅 표에 등록하지 않아 클러스터 밖에서 호출할 수 없다 — 인증 헤더를 두지 않는 이유.
 */
@RestController
@RequiredArgsConstructor
public class AuditLogQueryController {

    private static final int RECONCILIATION_WINDOW_MINUTES = 15;

    private final GetAuditLogsSinceUseCase getAuditLogsSinceUseCase;

    @GetMapping("/internal/audit-logs")
    public ResponseEntity<ApiResult<List<AuditLogResponse>>> getRecentAuditLogs(
        @RequestParam(required = false) OffsetDateTime since
    ) {
        OffsetDateTime effectiveSince = since != null
            ? since
            : OffsetDateTime.now().minusMinutes(RECONCILIATION_WINDOW_MINUTES);
        List<AuditLogResponse> responses = getAuditLogsSinceUseCase.getSince(effectiveSince).stream()
            .map(AuditLogResponse::from)
            .toList();
        return ResponseEntity.ok(ApiResult.success(responses));
    }
}
```

- [x] **Step 4: 테스트 통과 확인**

Run: `JAVA_HOME=~/.asdf/installs/java/temurin-21.0.5+11.0.LTS ../gradlew :payment-service:test --tests "com.prompthub.payment.AuditLogQueryControllerIntegrationTest"`
Expected: PASS

- [x] **Step 5: Commit**

```bash
git add payment-service/src/main/java/com/prompthub/payment/presentation/dto/response/AuditLogResponse.java \
  payment-service/src/main/java/com/prompthub/payment/presentation/AuditLogQueryController.java \
  payment-service/src/test/java/com/prompthub/payment/AuditLogQueryControllerIntegrationTest.java
git commit -m "feat: 감사로그 재조정용 내부 조회 엔드포인트 추가"
```

---

### Task 6: Logstash — 격리된 payment-audit-log 파이프라인 추가 ✅

**Files:**
- Modify: `k8s/addons/elk/logstash.yaml`

**설계 근거(왜 기존 `gateway-access.conf`를 고치지 않는가):** 이 ConfigMap이 마운트되는 방식(개별 `subPath` volumeMount, `pipelines.yml` 없음) 때문에 지금은 Logstash 기본 동작으로 파이프라인 디렉터리 전체가 **하나의 병합 파이프라인**으로 실행된다. 만약 kafka/http_poller 입력을 `gateway-access.conf`에 같이 넣거나 같은 디렉터리에 가드 없이 추가하면, 그 파일의 라우팅 ruby 필터(`else event.cancel`)가 새 이벤트를 전부 드롭해버린다. 이를 피하려고 매 이벤트에 `[type]` 가드를 넣어 기존 필터 체인을 감싸는 방법도 가능하지만, 이미 검증된 gateway/application 필터·마스킹 로직을 건드리는 리스크가 있다. 대신 `pipelines.yml`으로 **완전히 독립된 두 번째 파이프라인**을 만들면 기존 파일을 한 글자도 안 건드리고 신규 로직을 그 파일 안에서만 완결시킬 수 있다 — 격리가 더 안전한 선택이다.

- [x] **Step 1: 플러그인 가용성 확인(검증만, 코드 변경 없음)**

Run: `docker run --rm docker.elastic.co/logstash/logstash:9.4.3 bin/logstash-plugin list | grep -E "logstash-input-kafka|logstash-input-http_poller|logstash-filter-split"`
Expected: 3개 플러그인 모두 출력됨(기본 번들에 포함되어 있어야 이 설계가 성립). 하나라도 없으면 이 Task를 진행하기 전에 사용자에게 공유 — Dockerfile 커스터마이징이 추가로 필요해진다.

- [x] **Step 2: ConfigMap에 신규 파이프라인 conf + pipelines.yml 추가**

`k8s/addons/elk/logstash.yaml`의 `logstash-pipeline` ConfigMap `data:` 아래, 기존 `gateway-access.conf:` 항목은 그대로 두고 그 뒤에 아래 두 키를 추가한다(YAML 들여쓰기는 기존 `gateway-access.conf:`와 동일 레벨):

```yaml
  payment-audit-log.conf: |
    input {
      kafka {
        bootstrap_servers => "kafka.prompthub.svc.cluster.local:9092"
        topics => ["payment-audit-log"]
        group_id => "logstash-payment-audit-log"
        codec => json
      }
      http_poller {
        urls => {
          recent_audit_logs => "http://payment-service.prompthub.svc.cluster.local:8084/internal/audit-logs"
        }
        request_timeout => 10
        schedule => { every => "5m" }
        codec => "json"
      }
    }

    filter {
      if [data] {
        split {
          field => "[data]"
        }
        ruby {
          code => '
            row = event.get("[data]")
            if row.is_a?(Hash)
              row.each { |k, v| event.set(k, v) }
            end
            event.remove("[data]")
            event.remove("success")
            event.remove("message")
          '
        }
      }

      date {
        match => ["[createdAt]", "ISO8601"]
        target => "@timestamp"
      }
    }

    output {
      elasticsearch {
        hosts => ["http://elasticsearch.elk.svc.cluster.local:9200"]
        ilm_enabled => false
        manage_template => false
        index => "payment-audit-log-%{+YYYY.MM.dd}"
        document_id => "%{id}"
      }
    }
  pipelines.yml: |
    - pipeline.id: main
      path.config: "/usr/share/logstash/pipeline/gateway-access.conf"
    - pipeline.id: payment-audit-log
      path.config: "/usr/share/logstash/pipeline/payment-audit-log.conf"
```

- [x] **Step 3: ILM 부트스트랩 Job 추가**

`application-logs-ilm-bootstrap` Job 정의 바로 뒤(`---` 구분자 다음)에 아래 Job을 새로 추가한다(라벨·리소스·nodeSelector는 기존 두 Job과 동일 패턴):

```yaml
---
apiVersion: batch/v1
kind: Job
metadata:
  name: payment-audit-log-ilm-bootstrap
  namespace: elk
  labels:
    app.kubernetes.io/name: payment-audit-log-ilm-bootstrap
    app.kubernetes.io/part-of: prompthub
    app.kubernetes.io/component: observability
    app.kubernetes.io/managed-by: kustomize
spec:
  backoffLimit: 6
  ttlSecondsAfterFinished: 86400
  template:
    metadata:
      labels:
        app.kubernetes.io/name: payment-audit-log-ilm-bootstrap
        app.kubernetes.io/part-of: prompthub
        app.kubernetes.io/component: observability
        app.kubernetes.io/managed-by: kustomize
    spec:
      restartPolicy: OnFailure
      automountServiceAccountToken: false
      enableServiceLinks: false
      nodeSelector:
        prompthub.io/node-pool: control-stateful
      tolerations:
        - key: node-role.kubernetes.io/control-plane
          operator: Exists
          effect: NoSchedule
      securityContext:
        seccompProfile:
          type: RuntimeDefault
      containers:
        - name: bootstrap
          image: curlimages/curl:8.14.1
          imagePullPolicy: IfNotPresent
          command:
            - sh
            - -ec
            - |
              elastic_url=http://elasticsearch.elk.svc.cluster.local:9200
              until curl --fail --silent --show-error "${elastic_url}/_cluster/health?wait_for_status=yellow&timeout=1s" >/dev/null; do
                sleep 5
              done

              curl --fail --silent --show-error -H "Content-Type: application/json" \
                -X PUT "${elastic_url}/_ilm/policy/payment-audit-log-90d" \
                --data '{"policy":{"phases":{"hot":{"actions":{}},"delete":{"min_age":"90d","actions":{"delete":{}}}}}}'
              curl --fail --silent --show-error -H "Content-Type: application/json" \
                -X PUT "${elastic_url}/_index_template/payment-audit-log-template" \
                --data '{"index_patterns":["payment-audit-log-*"],"template":{"settings":{"index.lifecycle.name":"payment-audit-log-90d","number_of_shards":1,"number_of_replicas":0},"mappings":{"dynamic":true,"properties":{"orderId":{"type":"keyword"},"entityId":{"type":"keyword"},"actorId":{"type":"keyword"},"entityType":{"type":"keyword"},"eventType":{"type":"keyword"},"newStatus":{"type":"keyword"},"failureCode":{"type":"keyword"},"occurredAt":{"type":"date"},"createdAt":{"type":"date"},"detail":{"type":"match_only_text"}}}}}}'
          securityContext:
            allowPrivilegeEscalation: false
            capabilities:
              drop:
                - ALL
          resources:
            requests:
              cpu: 25m
              memory: 32Mi
            limits:
              cpu: 100m
              memory: 128Mi
```

- [x] **Step 4: Logstash Deployment에 volumeMount 2개 추가**

기존 `containers[0].volumeMounts`(pipeline 볼륨, `gateway-access.conf` subPath)의 형제 항목으로 추가:

```yaml
            - name: pipeline
              mountPath: /usr/share/logstash/pipeline/payment-audit-log.conf
              subPath: payment-audit-log.conf
              readOnly: true
            - name: pipeline
              mountPath: /usr/share/logstash/config/pipelines.yml
              subPath: pipelines.yml
              readOnly: true
```

- [x] **Step 5: 로컬 렌더링 검증(kubectl 설치돼 있는 경우)**

Run: `kubectl kustomize k8s/addons/elk | grep -A3 "name: payment-audit-log"`
Expected: `payment-audit-log-ilm-bootstrap` Job과 ConfigMap 데이터 키가 렌더링 결과에 출력됨. `kubectl` 미설치 환경이면 이 단계는 스킵하고 CI(`deploy-elk` job의 `kubectl kustomize` 스텝)에서 최종 검증한다.

- [x] **Step 6: Commit**

```bash
git add k8s/addons/elk/logstash.yaml
git commit -m "feat: 감사로그 전용 격리 Logstash 파이프라인 추가"
```

---

### Task 7: Kibana — payment-audit-log 데이터뷰 부트스트랩

**Files:**
- Modify: `k8s/addons/elk/kibana.yaml`

- [ ] **Step 1: saved objects ConfigMap 추가**

`application-logs-saved-objects` ConfigMap 정의 바로 뒤(`---` 다음)에 추가:

```yaml
---
apiVersion: v1
kind: ConfigMap
metadata:
  name: payment-audit-log-saved-objects
  namespace: elk
  labels:
    app.kubernetes.io/name: payment-audit-log-saved-objects
    app.kubernetes.io/part-of: prompthub
    app.kubernetes.io/component: observability
    app.kubernetes.io/managed-by: kustomize
data:
  payment-audit-log.ndjson: |
    {"type":"index-pattern","id":"payment-audit-log","attributes":{"title":"payment-audit-log-*","name":"Payment Audit Log","timeFieldName":"@timestamp"}}
    {"type":"search","id":"payment-audit-log-search","attributes":{"title":"Payment Audit Log","description":"결제/환불 감사로그 — CS·장애분석·감사대응 조회","columns":["orderId","entityType","eventType","newStatus","failureCode","occurredAt"],"sort":[["@timestamp","desc"]],"kibanaSavedObjectMeta":{"searchSourceJSON":"{\"index\":\"payment-audit-log\",\"query\":{\"language\":\"kuery\",\"query\":\"\"},\"filter\":[]}"}}}
```

- [ ] **Step 2: 부트스트랩 Job 추가**

`application-logs-kibana-bootstrap` Job 정의 바로 뒤(`---` 다음)에 추가(기존 Job과 동일 패턴, `successCount`는 saved object 2개이므로 동일하게 2):

```yaml
---
apiVersion: batch/v1
kind: Job
metadata:
  name: payment-audit-log-kibana-bootstrap
  namespace: elk
  labels:
    app.kubernetes.io/name: payment-audit-log-kibana-bootstrap
    app.kubernetes.io/part-of: prompthub
    app.kubernetes.io/component: observability
    app.kubernetes.io/managed-by: kustomize
spec:
  backoffLimit: 6
  ttlSecondsAfterFinished: 86400
  template:
    metadata:
      labels:
        app.kubernetes.io/name: payment-audit-log-kibana-bootstrap
        app.kubernetes.io/part-of: prompthub
        app.kubernetes.io/component: observability
        app.kubernetes.io/managed-by: kustomize
    spec:
      restartPolicy: OnFailure
      automountServiceAccountToken: false
      enableServiceLinks: false
      nodeSelector:
        prompthub.io/node-pool: control-stateful
      tolerations:
        - key: node-role.kubernetes.io/control-plane
          operator: Exists
          effect: NoSchedule
      securityContext:
        seccompProfile:
          type: RuntimeDefault
      containers:
        - name: bootstrap
          image: curlimages/curl:8.14.1
          imagePullPolicy: IfNotPresent
          command:
            - sh
            - -ec
            - |
              kibana_url=http://kibana.elk.svc.cluster.local:5601
              until curl --fail --silent --show-error "${kibana_url}/api/status" >/dev/null; do
                sleep 5
              done

              response=$(curl --fail --silent --show-error \
                -X POST "${kibana_url}/api/saved_objects/_import?overwrite=true" \
                -H "kbn-xsrf: payment-audit-log-bootstrap" \
                -F file=@/bootstrap/payment-audit-log.ndjson)
              echo "${response}" | grep -q '"success":true'
              echo "${response}" | grep -q '"successCount":2'
          securityContext:
            allowPrivilegeEscalation: false
            capabilities:
              drop:
                - ALL
          resources:
            requests:
              cpu: 25m
              memory: 32Mi
            limits:
              cpu: 100m
              memory: 128Mi
          volumeMounts:
            - name: saved-objects
              mountPath: /bootstrap
              readOnly: true
      volumes:
        - name: saved-objects
          configMap:
            name: payment-audit-log-saved-objects
```

- [ ] **Step 3: 로컬 렌더링 검증(kubectl 설치돼 있는 경우)**

Run: `kubectl kustomize k8s/addons/elk | grep -A3 "name: payment-audit-log-kibana-bootstrap"`
Expected: Job 정의가 렌더링 결과에 출력됨.

- [ ] **Step 4: Commit**

```bash
git add k8s/addons/elk/kibana.yaml
git commit -m "feat: payment-audit-log Kibana 데이터뷰 부트스트랩 추가"
```

---

### Task 8: CD 워크플로 — 신규 Job delete/wait 라인 추가

**Files:**
- Modify: `.github/workflows/cd-selfhosted-kubernetes.yml`

- [ ] **Step 1: `deploy-elk` job의 "ELK application log 수집 적용" 스텝에 새 줄 추가**

기존 라인(**절대 수정하지 않음**):

```yaml
          kubectl delete job application-logs-ilm-bootstrap application-logs-kibana-bootstrap -n elk --ignore-not-found
```

바로 아래에 새 줄 추가:

```yaml
          kubectl delete job payment-audit-log-ilm-bootstrap payment-audit-log-kibana-bootstrap -n elk --ignore-not-found
```

기존 라인(**절대 수정하지 않음**):

```yaml
          kubectl wait --for=condition=complete job/application-logs-ilm-bootstrap -n elk --timeout=10m
          kubectl wait --for=condition=complete job/application-logs-kibana-bootstrap -n elk --timeout=10m
```

바로 아래에 새 줄 2개 추가:

```yaml
          kubectl wait --for=condition=complete job/payment-audit-log-ilm-bootstrap -n elk --timeout=10m
          kubectl wait --for=condition=complete job/payment-audit-log-kibana-bootstrap -n elk --timeout=10m
```

- [ ] **Step 2: 검증 스크립트 통과 확인**

Run: `bash scripts/validate-k8s-cd-workflow.sh`
Expected: `CI/CD workflow validation passed.` — 기존 3개 anchor 라인이 원문 그대로 남아있어야 `require_pattern` 통과.

Run(`kubectl` 설치돼 있는 경우): `bash scripts/validate-k8s-manifests.sh`
Expected: `Kubernetes manifest validation passed.`

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/cd-selfhosted-kubernetes.yml
git commit -m "chore: payment-audit-log ELK 부트스트랩 Job 배포 단계 추가"
```

---

### Task 9: 문서 갱신

**Files:**
- Modify: `payment-service/.claude/docs/db-schema.md`
- Modify: `payment-service/.claude/docs/events.md`

- [ ] **Step 1: db-schema.md의 audit_log 섹션에 ES 파이프라인 한 줄 추가**

`## audit_log 테이블` 섹션의 첫 문단(“결제·환불의 시도/종결 상태 전이... 저장까지만 구현됨.”) 바로 뒤에 문단 추가:

```markdown
이 테이블 데이터는 Kafka(`payment-audit-log` 토픽, 실시간)와 Logstash `http_poller`(`/internal/audit-logs`, 5분 간격 재조정)를 통해 Elasticsearch(`payment-audit-log-*` 인덱스)로 미러링된다(#540). Postgres가 유일한 source of truth이고 ES는 Kibana 검색 전용 미러다.
```

- [ ] **Step 2: events.md에 내부 전용 토픽 섹션 추가**

기존 "발행/소비 매트릭스" 섹션 뒤에 새 절 추가:

```markdown
## 내부 전용 토픽 (서비스 간 계약 아님)

| 토픽 | 발행자 | 소비자 | 용도 |
|---|---|---|---|
| `payment-audit-log` | payment-service(`AuditLogKafkaPublisher`) | Logstash(ELK, k8s/addons/elk) | 감사로그를 Elasticsearch로 실시간 미러링(#540). 타 서비스는 이 토픽을 구독하지 않는다 — 외부 서비스 계약(`payment-events`)과 분리된 내부 전용 채널. |
```

- [ ] **Step 3: Commit**

```bash
git add payment-service/.claude/docs/db-schema.md payment-service/.claude/docs/events.md
git commit -m "docs: 감사로그 Elasticsearch 파이프라인 문서 반영"
```
