package com.prompthub.paymentservice.application.service;

import com.prompthub.exception.BusinessException;
import com.prompthub.paymentservice.application.dto.command.ConfirmPaymentCommand;
import com.prompthub.paymentservice.application.dto.result.PaymentResult;
import com.prompthub.paymentservice.application.exception.PaymentErrorCode;
import com.prompthub.paymentservice.application.gateway.external.ConfirmResult;
import com.prompthub.paymentservice.application.gateway.external.OrderGateway;
import com.prompthub.paymentservice.application.gateway.external.OrderPaymentInfo;
import com.prompthub.paymentservice.application.gateway.external.PaymentGateway;
import com.prompthub.paymentservice.application.gateway.external.PaymentGatewayException;
import com.prompthub.paymentservice.application.usecase.RecordOrderSnapshotUseCase;
import com.prompthub.paymentservice.domain.event.PaymentApprovedEvent;
import com.prompthub.paymentservice.domain.model.OrderSnapshot;
import com.prompthub.paymentservice.domain.model.OrderSnapshotSource;
import com.prompthub.paymentservice.domain.model.Payment;
import com.prompthub.paymentservice.domain.model.PaymentStatus;
import com.prompthub.paymentservice.domain.repository.OrderSnapshotRepository;
import com.prompthub.paymentservice.domain.repository.PaymentRepository;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConfirmPaymentServiceTest {

    @Mock
    PaymentRepository paymentRepository;
    @Mock
    OrderSnapshotRepository orderSnapshotRepository;
    @Mock
    OrderGateway orderGateway;
    @Mock
    RecordOrderSnapshotUseCase recordOrderSnapshotUseCase;
    @Mock
    PaymentGateway paymentGateway;
    @Mock
    ApplicationEventPublisher applicationEventPublisher;

    ConfirmPaymentService service;

    @BeforeEach
    void setUp() {
        // 실제 TX 없이 콜백만 실행하는 no-op PlatformTransactionManager (단위 테스트 전용)
        TransactionTemplate transactionTemplate = new TransactionTemplate(new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition def) {
                return new SimpleTransactionStatus();
            }
            @Override
            public void commit(TransactionStatus status) {}
            @Override
            public void rollback(TransactionStatus status) {}
        });
        service = new ConfirmPaymentService(
            paymentRepository, orderSnapshotRepository, orderGateway, recordOrderSnapshotUseCase,
            paymentGateway, applicationEventPublisher, transactionTemplate, false
        );
    }

    private OrderSnapshot snapshot(UUID orderId, UUID buyerId, int amount) {
        return OrderSnapshot.create(orderId, buyerId, amount, OrderSnapshotSource.EVENT, OffsetDateTime.now());
    }

    @Test
    void 진행_완료_상태_결제_존재_시_PAY002_예외() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(orderSnapshotRepository.findByOrderId(orderId))
            .thenReturn(Optional.of(snapshot(orderId, userId, 10_000)));
        when(paymentRepository.existsByOrderIdAndStatusIn(eq(orderId), any())).thenReturn(true);

        ConfirmPaymentCommand command = new ConfirmPaymentCommand("toss-key", orderId, userId);

        assertThatThrownBy(() -> service.confirm(command))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getErrorCode())
            .isEqualTo(PaymentErrorCode.DUPLICATE_PAYMENT);

        verify(paymentGateway, never()).confirm(anyString(), any(), anyInt());
    }

    @Test
    void 주문자_불일치_시_NOT_ORDER_OWNER_예외() {
        UUID orderId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();
        UUID requester = UUID.randomUUID();
        when(orderSnapshotRepository.findByOrderId(orderId))
            .thenReturn(Optional.of(snapshot(orderId, buyerId, 10_000)));

        ConfirmPaymentCommand command = new ConfirmPaymentCommand("toss-key", orderId, requester);

        assertThatThrownBy(() -> service.confirm(command))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getErrorCode())
            .isEqualTo(PaymentErrorCode.NOT_ORDER_OWNER);

        verify(paymentGateway, never()).confirm(anyString(), any(), anyInt());
    }

    @Test
    void Toss_성공_시_PAID_상태_이벤트_발행() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        OffsetDateTime approvedAt = OffsetDateTime.now();

        when(orderSnapshotRepository.findByOrderId(orderId))
            .thenReturn(Optional.of(snapshot(orderId, userId, 10_000)));
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentGateway.confirm(eq("toss-key"), eq(orderId), eq(10_000)))
            .thenReturn(new ConfirmResult("카드", 10_000, "{}", approvedAt));
        when(paymentRepository.findById(any(UUID.class))).thenAnswer(inv -> {
            Payment p = Payment.create(orderId, userId, "toss-key", "TOSS_PAYMENTS", "CARD", false, 10_000);
            p.markRequested(OffsetDateTime.now());
            return Optional.of(p);
        });

        ConfirmPaymentCommand command = new ConfirmPaymentCommand("toss-key", orderId, userId);
        PaymentResult result = service.confirm(command);

        assertThat(result.paymentId()).isNotNull();

        ArgumentCaptor<PaymentApprovedEvent> captor = ArgumentCaptor.forClass(PaymentApprovedEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().payment().getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(captor.getValue().payment().getApprovedAmount()).isEqualTo(10_000);
    }

    @Test
    void 스냅샷_부재_시_gRPC_폴백으로_승인() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        OffsetDateTime approvedAt = OffsetDateTime.now();

        when(orderSnapshotRepository.findByOrderId(orderId)).thenReturn(Optional.empty());
        when(orderGateway.getOrderPaymentInfo(orderId))
            .thenReturn(new OrderPaymentInfo(orderId, userId, 10_000, OffsetDateTime.now()));
        when(recordOrderSnapshotUseCase.record(any()))
            .thenReturn(snapshot(orderId, userId, 10_000));
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentGateway.confirm(eq("toss-key"), eq(orderId), eq(10_000)))
            .thenReturn(new ConfirmResult("카드", 10_000, "{}", approvedAt));
        when(paymentRepository.findById(any(UUID.class))).thenAnswer(inv -> {
            Payment p = Payment.create(orderId, userId, "toss-key", "TOSS_PAYMENTS", "CARD", false, 10_000);
            p.markRequested(OffsetDateTime.now());
            return Optional.of(p);
        });

        ConfirmPaymentCommand command = new ConfirmPaymentCommand("toss-key", orderId, userId);
        PaymentResult result = service.confirm(command);

        assertThat(result.paymentId()).isNotNull();
        verify(orderGateway).getOrderPaymentInfo(orderId);
        verify(applicationEventPublisher).publishEvent(any(PaymentApprovedEvent.class));
    }

    @Test
    void Toss_서버오류성_4xx_시_FAILED_상태_PG_INVALID_REQUEST_예외() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        when(orderSnapshotRepository.findByOrderId(orderId))
            .thenReturn(Optional.of(snapshot(orderId, userId, 10_000)));
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentGateway.confirm(anyString(), any(), anyInt()))
            .thenThrow(new PaymentGatewayException(
                PaymentErrorCode.PG_INVALID_REQUEST, "INVALID_REQUEST", "잘못된 요청입니다.", null, "{}"));
        when(paymentRepository.findById(any(UUID.class))).thenAnswer(inv -> {
            Payment p = Payment.create(orderId, userId, "toss-key", "TOSS_PAYMENTS", "CARD", false, 10_000);
            p.markRequested(OffsetDateTime.now());
            return Optional.of(p);
        });

        ConfirmPaymentCommand command = new ConfirmPaymentCommand("toss-key", orderId, userId);

        assertThatThrownBy(() -> service.confirm(command))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getErrorCode())
            .isEqualTo(PaymentErrorCode.PG_INVALID_REQUEST);

        verify(applicationEventPublisher, never()).publishEvent(any());

        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        Payment lastSaved = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertThat(lastSaved.getStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void Toss_실패_시_FAILED_상태_PAY_FAILED_예외() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        when(orderSnapshotRepository.findByOrderId(orderId))
            .thenReturn(Optional.of(snapshot(orderId, userId, 10_000)));
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentGateway.confirm(anyString(), any(), anyInt()))
            .thenThrow(new PaymentGatewayException(
                PaymentErrorCode.PAYMENT_FAILED, "REJECT", "카드 거절", null, "{}"));
        when(paymentRepository.findById(any(UUID.class))).thenAnswer(inv -> {
            Payment p = Payment.create(orderId, userId, "toss-key", "TOSS_PAYMENTS", "CARD", false, 10_000);
            p.markRequested(OffsetDateTime.now());
            return Optional.of(p);
        });

        ConfirmPaymentCommand command = new ConfirmPaymentCommand("toss-key", orderId, userId);

        assertThatThrownBy(() -> service.confirm(command))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getErrorCode())
            .isEqualTo(PaymentErrorCode.PAYMENT_FAILED);

        verify(applicationEventPublisher, never()).publishEvent(any());

        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        Payment lastSaved = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertThat(lastSaved.getStatus()).isEqualTo(PaymentStatus.FAILED);
    }
}
