package com.prompthub.paymentservice.application.service;

import com.prompthub.exception.BusinessException;
import com.prompthub.paymentservice.application.dto.command.RefundPaymentCommand;
import com.prompthub.paymentservice.application.exception.PaymentErrorCode;
import com.prompthub.paymentservice.domain.repository.PaymentRepository;
import com.prompthub.paymentservice.domain.repository.RefundRepository;
import com.prompthub.paymentservice.application.usecase.RefundPaymentUseCase;
import com.prompthub.paymentservice.domain.event.PaymentRefundRequestedEvent;
import com.prompthub.paymentservice.domain.model.Payment;
import com.prompthub.paymentservice.domain.model.PaymentStatus;
import com.prompthub.paymentservice.domain.model.Refund;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
public class RefundPaymentService implements RefundPaymentUseCase {

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Override
    public void refund(RefundPaymentCommand command) {
        Payment payment = paymentRepository.findById(command.paymentId())
            .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND));

        if (!payment.getUserId().equals(command.userId())) {
            throw new BusinessException(PaymentErrorCode.UNAUTHORIZED_REFUND);
        }
        if (payment.getStatus() != PaymentStatus.PAID) {
            throw new BusinessException(PaymentErrorCode.REFUND_NOT_ALLOWED);
        }

        payment.startRefunding();
        Refund refund = Refund.create(
            payment.getId(), command.userId(), payment.getTotalAmount(), null, null
        );
        paymentRepository.save(payment);
        refundRepository.save(refund);

        applicationEventPublisher.publishEvent(
            new PaymentRefundRequestedEvent(payment.getId(), refund.getId())
        );
    }
}
