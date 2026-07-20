package com.prompthub.payment.application.service;

import com.prompthub.payment.application.dto.command.RefundCommand;
import com.prompthub.payment.application.gateway.external.PaymentGateway;
import com.prompthub.payment.application.gateway.external.PaymentGatewayException;
import com.prompthub.payment.application.gateway.external.RefundResult;
import com.prompthub.payment.application.exception.PaymentErrorCode;
import com.prompthub.payment.domain.event.PaymentRefundFailedEvent;
import com.prompthub.payment.domain.event.PaymentRefundedEvent;
import com.prompthub.payment.domain.model.Payment;
import com.prompthub.payment.domain.model.PaymentStatus;
import com.prompthub.payment.domain.model.Refund;
import com.prompthub.payment.domain.model.RefundStatus;
import com.prompthub.payment.domain.repository.PaymentRepository;
import com.prompthub.payment.domain.repository.RefundRepository;
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
class RefundServiceTest {

    @Mock
    PaymentRepository paymentRepository;
    @Mock
    RefundRepository refundRepository;
    @Mock
    PaymentGateway paymentGateway;
    @Mock
    ApplicationEventPublisher applicationEventPublisher;

    RefundService service;

    @BeforeEach
    void setUp() {
        service = new RefundService(paymentRepository, refundRepository, paymentGateway, applicationEventPublisher);
    }

    @Test
    void 이미_처리된_refundRequestId면_정상_종료하고_아무것도_안_한다() {
        UUID refundRequestId = UUID.randomUUID();
        when(refundRepository.existsByRefundRequestId(refundRequestId)).thenReturn(true);

        RefundCommand command = new RefundCommand(
            UUID.randomUUID(), refundRequestId, 3_000, OffsetDateTime.now());

        service.refund(command);

        verify(paymentRepository, never()).findByOrderIdAndStatusInForUpdate(any(), any());
        verify(refundRepository, never()).save(any());
        verify(applicationEventPublisher, never()).publishEvent(any());
    }

    @Test
    void 결제_건_없으면_예외() {
        UUID orderId = UUID.randomUUID();
        when(refundRepository.existsByRefundRequestId(any())).thenReturn(false);
        when(paymentRepository.findByOrderIdAndStatusInForUpdate(any(), any())).thenReturn(Optional.empty());

        RefundCommand command = new RefundCommand(
            orderId, UUID.randomUUID(), 3_000, OffsetDateTime.now());

        assertThatThrownBy(() -> service.refund(command))
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

        RefundCommand command = new RefundCommand(
            payment.getOrderId(), UUID.randomUUID(), 3_000, OffsetDateTime.now());

        service.refund(command);

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
    void 부분_환불_성공_시_Payment_상태는_PAID_유지() {
        Payment payment = 결제_생성_후_승인(10_000);
        UUID refundRequestId = UUID.randomUUID();
        OffsetDateTime refundedAt = OffsetDateTime.now();
        when(refundRepository.existsByRefundRequestId(refundRequestId)).thenReturn(false);
        when(paymentRepository.findByOrderIdAndStatusInForUpdate(any(), any())).thenReturn(Optional.of(payment));
        when(refundRepository.findByPaymentIdAndStatus(payment.getId(), RefundStatus.COMPLETED))
            .thenReturn(List.of());
        when(refundRepository.save(any(Refund.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentGateway.refund(anyString(), any(), anyInt())).thenReturn(new RefundResult(refundedAt));

        RefundCommand command = new RefundCommand(
            payment.getOrderId(), refundRequestId, 4_000, OffsetDateTime.now());
        service.refund(command);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);

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
    void 누적_환불액_totalAmount_도달해도_Payment_상태는_PAID_유지() {
        Payment payment = 결제_생성_후_승인(10_000);
        when(refundRepository.existsByRefundRequestId(any())).thenReturn(false);
        when(paymentRepository.findByOrderIdAndStatusInForUpdate(any(), any())).thenReturn(Optional.of(payment));
        when(refundRepository.findByPaymentIdAndStatus(payment.getId(), RefundStatus.COMPLETED))
            .thenReturn(List.of(기존_완료_환불(payment.getId(), 6_000)));
        when(refundRepository.save(any(Refund.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentGateway.refund(anyString(), any(), anyInt())).thenReturn(new RefundResult(OffsetDateTime.now()));

        RefundCommand command = new RefundCommand(
            payment.getOrderId(), UUID.randomUUID(), 4_000, OffsetDateTime.now());
        service.refund(command);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);

        ArgumentCaptor<Refund> refundCaptor = ArgumentCaptor.forClass(Refund.class);
        verify(refundRepository).save(refundCaptor.capture());
        assertThat(refundCaptor.getValue().getStatus()).isEqualTo(RefundStatus.COMPLETED);
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

        RefundCommand command = new RefundCommand(
            payment.getOrderId(), UUID.randomUUID(), 4_000, OffsetDateTime.now());
        service.refund(command);

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
