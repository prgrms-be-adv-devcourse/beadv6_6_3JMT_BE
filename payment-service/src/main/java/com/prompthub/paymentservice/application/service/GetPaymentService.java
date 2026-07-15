package com.prompthub.paymentservice.application.service;

import com.prompthub.exception.BusinessException;
import com.prompthub.paymentservice.application.dto.command.GetPaymentCommand;
import com.prompthub.paymentservice.application.dto.result.PaymentQueryResult;
import com.prompthub.paymentservice.application.exception.PaymentErrorCode;
import com.prompthub.paymentservice.application.usecase.GetPaymentUseCase;
import com.prompthub.paymentservice.domain.model.Payment;
import com.prompthub.paymentservice.domain.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GetPaymentService implements GetPaymentUseCase {

    private final PaymentRepository paymentRepository;

    @Override
    public PaymentQueryResult getPayment(GetPaymentCommand command) {
        Payment payment = paymentRepository.findLatestByOrderId(command.orderId())
            .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND));

        return new PaymentQueryResult(
            payment.getId(),
            payment.getOrderId(),
            payment.getUserId(),
            payment.getStatus().name(),
            payment.getApprovedAmount(),
            payment.getApprovedAt(),
            payment.getFailedAt()
        );
    }
}
