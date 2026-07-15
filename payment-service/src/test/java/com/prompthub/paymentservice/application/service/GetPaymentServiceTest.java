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
