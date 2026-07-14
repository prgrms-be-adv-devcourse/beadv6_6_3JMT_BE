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
