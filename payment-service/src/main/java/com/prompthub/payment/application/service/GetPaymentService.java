package com.prompthub.payment.application.service;

import com.prompthub.exception.BusinessException;
import com.prompthub.payment.application.dto.command.GetPaymentCommand;
import com.prompthub.payment.application.dto.result.PaymentQueryResult;
import com.prompthub.payment.application.exception.PaymentErrorCode;
import com.prompthub.payment.application.usecase.GetPaymentUseCase;
import com.prompthub.payment.domain.model.Payment;
import com.prompthub.payment.domain.repository.PaymentRepository;
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
