package com.prompthub.paymentservice.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.prompthub.common.event.EventMessage;
import com.prompthub.paymentservice.domain.event.PaymentApprovedEvent;
import com.prompthub.paymentservice.domain.event.PaymentFailedEvent;
import com.prompthub.paymentservice.domain.model.Payment;
import com.prompthub.paymentservice.domain.model.Refund;
import com.prompthub.paymentservice.infrastructure.messaging.config.PaymentTopic;
import com.prompthub.paymentservice.infrastructure.messaging.dto.PaymentApprovedMessage;
import com.prompthub.paymentservice.infrastructure.messaging.dto.PaymentFailedMessage;
import com.prompthub.paymentservice.infrastructure.messaging.dto.PaymentRefundedMessage;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class KafkaPaymentEventPublisherTest {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);

    private final KafkaPaymentEventPublisher publisher = new KafkaPaymentEventPublisher(kafkaTemplate);

    @SuppressWarnings("unchecked")
    private void stubSendSuccess() {
        SendResult<String, Object> sendResult = mock(SendResult.class);
        RecordMetadata recordMetadata = mock(RecordMetadata.class);
        when(sendResult.getRecordMetadata()).thenReturn(recordMetadata);
        when(kafkaTemplate.send(anyString(), anyString(), any()))
            .thenReturn(CompletableFuture.completedFuture(sendResult));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 결제_승인_시_EventMessage_봉투로_발행한다() {
        stubSendSuccess();
        Payment payment = Payment.create(
            UUID.randomUUID(), UUID.randomUUID(), "pgTx-1", "TOSS_PAYMENTS", "CARD", true, 10_000);
        payment.markRequested(OffsetDateTime.now());
        OffsetDateTime approvedAt = OffsetDateTime.now();
        payment.approve(10_000, "CARD", "{}", approvedAt);

        publisher.onPaymentApproved(new PaymentApprovedEvent(payment));

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        org.mockito.Mockito.verify(kafkaTemplate)
            .send(org.mockito.ArgumentMatchers.eq(PaymentTopic.PAYMENT_EVENTS),
                org.mockito.ArgumentMatchers.eq(payment.getOrderId().toString()), captor.capture());

        EventMessage<PaymentApprovedMessage> message = (EventMessage<PaymentApprovedMessage>) captor.getValue();
        assertThat(message.eventId()).isNotNull();
        assertThat(message.eventType()).isEqualTo("PAYMENT_APPROVED");
        assertThat(message.occurredAt()).isEqualTo(approvedAt.withOffsetSameInstant(KST).toLocalDateTime());
        assertThat(message.aggregateType()).isEqualTo("ORDER");
        assertThat(message.aggregateId()).isEqualTo(payment.getOrderId());
        assertThat(message.payload().paymentId()).isEqualTo(payment.getId());
        assertThat(message.payload().orderId()).isEqualTo(payment.getOrderId());
        assertThat(message.payload().userId()).isEqualTo(payment.getUserId());
        assertThat(message.payload().amount()).isEqualTo(10_000);
    }

    @Test
    @SuppressWarnings("unchecked")
    void 결제_실패_시_EventMessage_봉투로_발행한다() {
        stubSendSuccess();
        Payment payment = Payment.create(
            UUID.randomUUID(), UUID.randomUUID(), "pgTx-2", "TOSS_PAYMENTS", "CARD", true, 10_000);
        payment.markRequested(OffsetDateTime.now());
        OffsetDateTime failedAt = OffsetDateTime.now();
        payment.fail("REJECT", "카드 거절", "{}", "{}", failedAt);

        publisher.onPaymentFailed(new PaymentFailedEvent(payment));

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        org.mockito.Mockito.verify(kafkaTemplate)
            .send(org.mockito.ArgumentMatchers.eq(PaymentTopic.PAYMENT_EVENTS),
                org.mockito.ArgumentMatchers.eq(payment.getOrderId().toString()), captor.capture());

        EventMessage<PaymentFailedMessage> message = (EventMessage<PaymentFailedMessage>) captor.getValue();
        assertThat(message.eventType()).isEqualTo("PAYMENT_FAILED");
        assertThat(message.occurredAt()).isEqualTo(failedAt.withOffsetSameInstant(KST).toLocalDateTime());
        assertThat(message.aggregateType()).isEqualTo("ORDER");
        assertThat(message.aggregateId()).isEqualTo(payment.getOrderId());
        assertThat(message.payload().paymentId()).isEqualTo(payment.getId());
        assertThat(message.payload().orderId()).isEqualTo(payment.getOrderId());
        assertThat(message.payload().userId()).isEqualTo(payment.getUserId());
    }

    @Test
    @SuppressWarnings("unchecked")
    void 환불_완료_시_EventMessage_봉투로_발행한다() {
        stubSendSuccess();
        Payment payment = Payment.create(
            UUID.randomUUID(), UUID.randomUUID(), "pgTx-3", "TOSS_PAYMENTS", "CARD", true, 10_000);
        payment.markRequested(OffsetDateTime.now());
        payment.approve(10_000, "CARD", "{}", OffsetDateTime.now());
        payment.startRefunding();
        OffsetDateTime refundedAt = OffsetDateTime.now();
        payment.completeRefund(refundedAt);
        Refund refund = Refund.create(payment.getId(), payment.getUserId(), 10_000, "단순 변심", null);

        publisher.publishRefunded(payment, refund);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        org.mockito.Mockito.verify(kafkaTemplate)
            .send(org.mockito.ArgumentMatchers.eq(PaymentTopic.PAYMENT_EVENTS),
                org.mockito.ArgumentMatchers.eq(payment.getOrderId().toString()), captor.capture());

        EventMessage<PaymentRefundedMessage> message = (EventMessage<PaymentRefundedMessage>) captor.getValue();
        assertThat(message.eventType()).isEqualTo("PAYMENT_REFUNDED");
        assertThat(message.occurredAt()).isEqualTo(refundedAt.withOffsetSameInstant(KST).toLocalDateTime());
        assertThat(message.aggregateType()).isEqualTo("ORDER");
        assertThat(message.aggregateId()).isEqualTo(payment.getOrderId());
        assertThat(message.payload().paymentId()).isEqualTo(payment.getId());
        assertThat(message.payload().amount()).isEqualTo(10_000);
    }
}
