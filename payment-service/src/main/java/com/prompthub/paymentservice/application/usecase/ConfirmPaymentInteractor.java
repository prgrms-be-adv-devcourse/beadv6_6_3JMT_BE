package com.prompthub.paymentservice.application.usecase;

import com.prompthub.exception.BusinessException;
import com.prompthub.paymentservice.application.dto.command.ConfirmPaymentCommand;
import com.prompthub.paymentservice.application.dto.result.PaymentResult;
import com.prompthub.paymentservice.application.exception.PaymentErrorCode;
import com.prompthub.paymentservice.application.gateway.external.PaymentGateway;
import com.prompthub.paymentservice.application.gateway.external.PaymentGatewayException;
import com.prompthub.paymentservice.application.gateway.external.TossConfirmResult;
import com.prompthub.paymentservice.application.gateway.persistence.PaymentRepository;
import com.prompthub.paymentservice.domain.event.PaymentApprovedEvent;
import com.prompthub.paymentservice.domain.model.Payment;
import java.time.OffsetDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ConfirmPaymentInteractor implements ConfirmPaymentUseCase {

    private final PaymentRepository paymentRepository;
    private final PaymentGateway paymentGateway;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final boolean testMode;

    public ConfirmPaymentInteractor(
        PaymentRepository paymentRepository,
        PaymentGateway paymentGateway,
        ApplicationEventPublisher applicationEventPublisher,
        @Value("${payment.toss.test-mode:false}") boolean testMode
    ) {
        this.paymentRepository = paymentRepository;
        this.paymentGateway = paymentGateway;
        this.applicationEventPublisher = applicationEventPublisher;
        this.testMode = testMode;
    }

    @Override
    public PaymentResult confirm(ConfirmPaymentCommand command) {
        String idempotencyKey = "pay-" + command.orderId();
        paymentRepository.findByIdempotencyKey(idempotencyKey)
            .ifPresent(p -> {
                throw new BusinessException(PaymentErrorCode.DUPLICATE_PAYMENT);
            });

        Payment payment = Payment.create(
            command.orderId(), command.userId(),
            command.paymentKey(), "TOSS_PAYMENTS", "CARD", testMode,
            command.amount(), 0
        );
        paymentRepository.save(payment);

        payment.markRequested(OffsetDateTime.now());
        paymentRepository.save(payment);

        try {
            TossConfirmResult result = paymentGateway.confirm(
                command.paymentKey(), command.orderId(), command.amount()
            );
            payment.approve(result.approvedAmount(), result.paymentMethod(),
                result.responsePayload(), result.approvedAt());
            paymentRepository.save(payment);

            applicationEventPublisher.publishEvent(new PaymentApprovedEvent(payment));

            return new PaymentResult(payment.getId());

        } catch (PaymentGatewayException e) {
            payment.fail(e.getFailureCode(), e.getFailureReason(),
                e.getRequestPayload(), e.getResponsePayload(), OffsetDateTime.now());
            paymentRepository.save(payment);
            throw new BusinessException(e.getErrorCode(), e.getFailureReason());
        }
    }
}
