package com.prompthub.paymentservice.application.service;

import com.prompthub.exception.BusinessException;
import com.prompthub.paymentservice.application.dto.command.RefundPaymentCommand;
import com.prompthub.paymentservice.application.exception.PaymentErrorCode;
import com.prompthub.paymentservice.domain.repository.PaymentRepository;
import com.prompthub.paymentservice.domain.repository.RefundRepository;
import com.prompthub.paymentservice.domain.event.PaymentRefundRequestedEvent;
import com.prompthub.paymentservice.domain.model.Payment;
import com.prompthub.paymentservice.domain.model.PaymentStatus;
import com.prompthub.paymentservice.domain.model.Refund;
import java.time.OffsetDateTime;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefundPaymentServiceTest {

    @Mock
    PaymentRepository paymentRepository;
    @Mock
    RefundRepository refundRepository;
    @Mock
    ApplicationEventPublisher applicationEventPublisher;

    RefundPaymentService service;

    @BeforeEach
    void setUp() {
        service = new RefundPaymentService(paymentRepository, refundRepository, applicationEventPublisher);
    }

    @Test
    void 결제_건_없으면_PAY005_예외() {
        UUID paymentId = UUID.randomUUID();
        when(paymentRepository.findByIdForUpdate(paymentId)).thenReturn(Optional.empty());

        RefundPaymentCommand command = new RefundPaymentCommand(paymentId, UUID.randomUUID());

        assertThatThrownBy(() -> service.refund(command))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getErrorCode())
            .isEqualTo(PaymentErrorCode.PAYMENT_NOT_FOUND);

        verify(applicationEventPublisher, never()).publishEvent(any());
    }

    @Test
    void 본인_아닌_결제_환불_시_PAY006_예외() {
        UUID otherId = UUID.randomUUID();
        Payment payment = 결제_생성_후_승인();
        when(paymentRepository.findByIdForUpdate(payment.getId())).thenReturn(Optional.of(payment));

        RefundPaymentCommand command = new RefundPaymentCommand(payment.getId(), otherId);

        assertThatThrownBy(() -> service.refund(command))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getErrorCode())
            .isEqualTo(PaymentErrorCode.UNAUTHORIZED_REFUND);

        verify(applicationEventPublisher, never()).publishEvent(any());
    }

    @Test
    void PAID_아닌_상태_환불_시_PAY004_예외() {
        UUID userId = UUID.randomUUID();
        // READY 상태 — PAID 아님
        Payment payment = Payment.create(UUID.randomUUID(), userId, "pg-key", "TOSS_PAYMENTS", "CARD", false, 10_000);
        when(paymentRepository.findByIdForUpdate(payment.getId())).thenReturn(Optional.of(payment));

        RefundPaymentCommand command = new RefundPaymentCommand(payment.getId(), userId);

        assertThatThrownBy(() -> service.refund(command))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getErrorCode())
            .isEqualTo(PaymentErrorCode.REFUND_NOT_ALLOWED);

        verify(applicationEventPublisher, never()).publishEvent(any());
    }

    @Test
    void PAID_상태_환불_요청_시_REFUNDING_전환_이벤트_발행() {
        Payment payment = 결제_생성_후_승인();
        when(paymentRepository.findByIdForUpdate(payment.getId())).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(refundRepository.save(any(Refund.class))).thenAnswer(inv -> inv.getArgument(0));

        RefundPaymentCommand command = new RefundPaymentCommand(payment.getId(), payment.getUserId());
        service.refund(command);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDING);

        ArgumentCaptor<Refund> refundCaptor = ArgumentCaptor.forClass(Refund.class);
        verify(refundRepository).save(refundCaptor.capture());
        assertThat(refundCaptor.getValue().getRefundAmount()).isEqualTo(payment.getTotalAmount());

        ArgumentCaptor<PaymentRefundRequestedEvent> eventCaptor =
            ArgumentCaptor.forClass(PaymentRefundRequestedEvent.class);
        verify(applicationEventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().paymentId()).isEqualTo(payment.getId());
    }

    private Payment 결제_생성_후_승인() {
        UUID userId = UUID.randomUUID();
        Payment payment = Payment.create(UUID.randomUUID(), userId, "pg-key", "TOSS_PAYMENTS", "CARD", false, 10_000);
        payment.markRequested(OffsetDateTime.now());
        payment.approve(10_000, "카드", "{}", OffsetDateTime.now());
        return payment;
    }
}
