package com.prompthub.paymentservice.application.service;

import com.prompthub.exception.BusinessException;
import com.prompthub.paymentservice.application.dto.command.RefundCommand;
import com.prompthub.paymentservice.application.exception.PaymentErrorCode;
import com.prompthub.paymentservice.application.gateway.external.PaymentGateway;
import com.prompthub.paymentservice.application.gateway.external.PaymentGatewayException;
import com.prompthub.paymentservice.application.gateway.external.RefundResult;
import com.prompthub.paymentservice.application.usecase.RefundUseCase;
import com.prompthub.paymentservice.domain.event.PaymentRefundFailedEvent;
import com.prompthub.paymentservice.domain.event.PaymentRefundedEvent;
import com.prompthub.paymentservice.domain.model.Payment;
import com.prompthub.paymentservice.domain.model.PaymentStatus;
import com.prompthub.paymentservice.domain.model.Refund;
import com.prompthub.paymentservice.domain.model.RefundStatus;
import com.prompthub.paymentservice.domain.repository.PaymentRepository;
import com.prompthub.paymentservice.domain.repository.RefundRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class RefundService implements RefundUseCase {

    private static final List<PaymentStatus> REFUNDABLE_STATUSES =
        List.of(PaymentStatus.PAID, PaymentStatus.PARTIAL_REFUNDED);

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final PaymentGateway paymentGateway;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Override
    public void refund(RefundCommand command) {
        if (refundRepository.existsByRefundRequestId(command.refundRequestId())) {
            log.info("이미 처리된 환불 요청 — 중복 이벤트로 판단하고 종료. refundRequestId={}", command.refundRequestId());
            return;
        }

        Payment payment = paymentRepository
            .findByOrderIdAndStatusInForUpdate(command.orderId(), REFUNDABLE_STATUSES)
            .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND));

        Refund refund = Refund.create(payment.getId(), command.refundRequestId(), command.refundAmount(), null);

        int totalRefundedAmount = refundRepository
            .findByPaymentIdAndStatus(payment.getId(), RefundStatus.COMPLETED)
            .stream()
            .mapToInt(Refund::getRefundAmount)
            .sum();
        int remaining = payment.getTotalAmount() - totalRefundedAmount;

        if (command.refundAmount() > remaining) {
            log.warn("환불 가능 잔액 초과 — paymentId={}, remaining={}, requested={}",
                payment.getId(), remaining, command.refundAmount());
            refund.fail("환불 가능 잔액을 초과했습니다.");
            refundRepository.save(refund);
            applicationEventPublisher.publishEvent(
                new PaymentRefundFailedEvent(payment, refund, "환불 가능 잔액을 초과했습니다."));
            return;
        }

        try {
            RefundResult result = paymentGateway.refund(payment.getPgTxId(), refund.getId(), command.refundAmount());
            refund.complete(result.refundedAt());
            payment.applyRefund(result.refundedAt(), command.refundAmount() == remaining);
            paymentRepository.save(payment);
            refundRepository.save(refund);
            applicationEventPublisher.publishEvent(new PaymentRefundedEvent(payment, refund));
        } catch (PaymentGatewayException e) {
            log.error("PG 환불 실패 — paymentId={}, refundRequestId={}, code={}, reason={}",
                payment.getId(), command.refundRequestId(), e.getFailureCode(), e.getFailureReason());
            refund.fail(e.getFailureReason());
            refundRepository.save(refund);
            applicationEventPublisher.publishEvent(
                new PaymentRefundFailedEvent(payment, refund, e.getFailureReason()));
        }
    }
}
