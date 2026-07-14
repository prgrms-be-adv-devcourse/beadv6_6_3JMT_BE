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
