package com.prompthub.paymentservice.application.service;

import com.prompthub.exception.BusinessException;
import com.prompthub.paymentservice.application.dto.command.GetRefundCommand;
import com.prompthub.paymentservice.application.dto.result.RefundQueryResult;
import com.prompthub.paymentservice.application.exception.PaymentErrorCode;
import com.prompthub.paymentservice.application.usecase.GetRefundUseCase;
import com.prompthub.paymentservice.domain.model.Payment;
import com.prompthub.paymentservice.domain.model.Refund;
import com.prompthub.paymentservice.domain.repository.PaymentRepository;
import com.prompthub.paymentservice.domain.repository.RefundRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GetRefundService implements GetRefundUseCase {

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;

    @Override
    public RefundQueryResult getRefund(GetRefundCommand command) {
        Payment payment = paymentRepository.findById(command.paymentId())
            .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND));

        Refund refund = refundRepository
            .findByPaymentIdAndOrderProductId(command.paymentId(), command.orderProductId())
            .orElseThrow(() -> new BusinessException(PaymentErrorCode.REFUND_NOT_FOUND));

        return new RefundQueryResult(
            payment.getId(),
            payment.getOrderId(),
            payment.getUserId(),
            refund.getOrderProductId(),
            refund.getRefundAmount(),
            payment.getStatus().name(),
            refund.getStatus().name(),
            refund.getCompletedAt()
        );
    }
}
