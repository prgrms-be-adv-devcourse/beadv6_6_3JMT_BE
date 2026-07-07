package com.prompthub.paymentservice.application.service;

import com.prompthub.exception.BusinessException;
import com.prompthub.paymentservice.application.dto.command.ConfirmPaymentCommand;
import com.prompthub.paymentservice.application.dto.result.PaymentResult;
import com.prompthub.paymentservice.application.exception.PaymentErrorCode;
import com.prompthub.paymentservice.application.gateway.external.PaymentGateway;
import com.prompthub.paymentservice.application.gateway.external.PaymentGatewayException;
import com.prompthub.paymentservice.application.gateway.external.ConfirmResult;
import com.prompthub.paymentservice.domain.repository.PaymentRepository;
import com.prompthub.paymentservice.application.usecase.ConfirmPaymentUseCase;
import com.prompthub.paymentservice.domain.event.PaymentApprovedEvent;
import com.prompthub.paymentservice.domain.model.Payment;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
public class ConfirmPaymentService implements ConfirmPaymentUseCase {

    private static final String PG_PROVIDER = "TOSS_PAYMENTS";
    private static final String PAYMENT_METHOD = "CARD";

    private final PaymentRepository paymentRepository;
    private final PaymentGateway paymentGateway;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final TransactionTemplate transactionTemplate;
    private final boolean testMode;

    public ConfirmPaymentService(
        PaymentRepository paymentRepository,
        PaymentGateway paymentGateway,
        ApplicationEventPublisher applicationEventPublisher,
        TransactionTemplate transactionTemplate,
        @Value("${payment.toss.test-mode:false}") boolean testMode
    ) {
        this.paymentRepository = paymentRepository;
        this.paymentGateway = paymentGateway;
        this.applicationEventPublisher = applicationEventPublisher;
        this.transactionTemplate = transactionTemplate;
        this.testMode = testMode;
    }

    @Override
    public PaymentResult confirm(ConfirmPaymentCommand command) {
        String idempotencyKey = "pay-" + command.orderId();

        // TX1: 결제 초기화 — 커밋 후 DB 커넥션 반납
        UUID paymentId;
        try {
            paymentId = transactionTemplate.execute(status -> {
                paymentRepository.findByIdempotencyKey(idempotencyKey)
                    .ifPresent(p -> {
                        throw new BusinessException(PaymentErrorCode.DUPLICATE_PAYMENT);
                    });

                Payment payment = Payment.create(
                    command.orderId(), command.userId(),
                    command.paymentKey(), PG_PROVIDER, PAYMENT_METHOD, testMode,
                    command.amount(), 0
                );
                paymentRepository.saveAndFlush(payment);
                payment.markRequested(OffsetDateTime.now());
                paymentRepository.save(payment);
                return payment.getId();
            });
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(PaymentErrorCode.DUPLICATE_PAYMENT);
        }

        // Toss API 호출 — 트랜잭션 밖, DB 커넥션 미점유
        try {
            ConfirmResult result = paymentGateway.confirm(
                command.paymentKey(), command.orderId(), command.amount()
            );

            // TX2: 승인 결과 반영
            return transactionTemplate.execute(status -> {
                Payment payment = paymentRepository.findById(paymentId).orElseThrow();
                payment.approve(result.approvedAmount(), result.paymentMethod(),
                    result.responsePayload(), result.approvedAt());
                paymentRepository.save(payment);
                applicationEventPublisher.publishEvent(new PaymentApprovedEvent(payment));
                return new PaymentResult(payment.getId());
            });

        } catch (PaymentGatewayException e) {
            log.error("PG사 결제 실패 — paymentKey={}, tossCode={}, reason={}",
                command.paymentKey(), e.getFailureCode(), e.getFailureReason(), e);

            // TX3: 실패 반영
            transactionTemplate.execute(status -> {
                Payment payment = paymentRepository.findById(paymentId).orElseThrow();
                payment.fail(e.getFailureCode(), e.getFailureReason(),
                    e.getRequestPayload(), e.getResponsePayload(), OffsetDateTime.now());
                paymentRepository.save(payment);
                return null;
            });

            throw new BusinessException(e.getErrorCode(), e.getFailureReason());
        }
    }
}
