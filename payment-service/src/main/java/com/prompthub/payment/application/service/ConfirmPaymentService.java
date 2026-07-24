package com.prompthub.payment.application.service;

import com.prompthub.exception.BusinessException;
import com.prompthub.payment.application.dto.command.ConfirmPaymentCommand;
import com.prompthub.payment.application.dto.result.PaymentResult;
import com.prompthub.payment.application.exception.PaymentErrorCode;
import com.prompthub.payment.application.gateway.external.ConfirmResult;
import com.prompthub.payment.application.gateway.external.OrderGateway;
import com.prompthub.payment.application.gateway.external.OrderPaymentInfo;
import com.prompthub.payment.application.gateway.external.PaymentGateway;
import com.prompthub.payment.application.gateway.external.PaymentGatewayException;
import com.prompthub.payment.application.usecase.ConfirmPaymentUseCase;
import com.prompthub.payment.domain.event.PaymentApprovedEvent;
import com.prompthub.payment.domain.event.PaymentFailedEvent;
import com.prompthub.payment.domain.event.PaymentRequestedEvent;
import com.prompthub.payment.domain.model.Payment;
import com.prompthub.payment.domain.model.PaymentStatus;
import com.prompthub.payment.domain.repository.PaymentRepository;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
public class ConfirmPaymentService implements ConfirmPaymentUseCase {

    private static final String PG_PROVIDER = "TOSS_PAYMENTS";
    private static final String PAYMENT_METHOD = "CARD";

    // 진행 중(READY·REQUESTED)만 비차단. PAID(환불 발생 여부와 무관하게 유지)·FAILED(재결제 영구 차단 정책)·UNKNOWN은 전부 차단.
    private static final Set<PaymentStatus> BLOCKING_STATUSES = Set.of(
        PaymentStatus.PAID, PaymentStatus.FAILED, PaymentStatus.UNKNOWN
    );

    private final PaymentRepository paymentRepository;
    private final OrderGateway orderGateway;
    private final PaymentGateway paymentGateway;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final TransactionTemplate transactionTemplate;

    public ConfirmPaymentService(
        PaymentRepository paymentRepository,
        OrderGateway orderGateway,
        PaymentGateway paymentGateway,
        ApplicationEventPublisher applicationEventPublisher,
        TransactionTemplate transactionTemplate
    ) {
        this.paymentRepository = paymentRepository;
        this.orderGateway = orderGateway;
        this.paymentGateway = paymentGateway;
        this.applicationEventPublisher = applicationEventPublisher;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public PaymentResult confirm(ConfirmPaymentCommand command) {
        if (paymentRepository.existsByPaymentKey(command.paymentKey())) {
            throw new BusinessException(PaymentErrorCode.DUPLICATE_PAYMENT);
        }
        if (paymentRepository.existsByOrderIdAndStatusIn(command.orderId(), BLOCKING_STATUSES)) {
            throw new BusinessException(PaymentErrorCode.DUPLICATE_PAYMENT);
        }

        // 주문 정보 gRPC 조회 — DB 트랜잭션 밖에서 실행해 네트워크 왕복 동안 커넥션을 점유하지 않는다.
        OrderPaymentInfo orderInfo = orderGateway.getOrderPaymentInfo(command.orderId());

        if (!orderInfo.buyerId().equals(command.userId())) {
            throw new BusinessException(PaymentErrorCode.NOT_ORDER_OWNER);
        }

        if (command.amount() != orderInfo.totalAmount()) {
            throw new BusinessException(PaymentErrorCode.AMOUNT_MISMATCH);
        }

        // TX1: Payment 생성 + REQUESTED 전이 — 커밋 후 DB 커넥션 반납
        UUID paymentId;
        try {
            paymentId = transactionTemplate.execute(status -> {
                Payment payment = Payment.create(
                    command.orderId(), command.userId(),
                    command.paymentKey(), PG_PROVIDER, PAYMENT_METHOD,
                    orderInfo.totalAmount()
                );
                paymentRepository.saveAndFlush(payment);
                payment.markRequested(OffsetDateTime.now());
                paymentRepository.save(payment);
                applicationEventPublisher.publishEvent(new PaymentRequestedEvent(payment));
                return payment.getId();
            });
        } catch (DataIntegrityViolationException e) {
            // payment_key/orderId 사전 체크와 INSERT 사이의 좁은 레이스 — 최종 방어선
            throw new BusinessException(PaymentErrorCode.DUPLICATE_PAYMENT);
        }

        // Toss API 호출 — 트랜잭션 밖, DB 커넥션 미점유.
        try {
            ConfirmResult result = paymentGateway.confirm(
                command.paymentKey(), command.orderId(), orderInfo.totalAmount()
            );

            // TX2: 승인 결과 반영
            return transactionTemplate.execute(status -> {
                Payment payment = paymentRepository.findById(paymentId).orElseThrow();
                payment.approve(result.approvedAmount(), result.paymentMethod(),
                    result.requestPayload(), result.responsePayload(), result.approvedAt());
                paymentRepository.save(payment);
                applicationEventPublisher.publishEvent(new PaymentApprovedEvent(payment));
                return new PaymentResult(payment.getId());
            });

        } catch (PaymentGatewayException e) {
            log.error("PG사 결제 실패 — paymentKey={}, tossCode={}, reason={}",
                command.paymentKey(), e.getFailureCode(), e.getFailureReason(), e);

            // TX3: 실패 반영 + PAYMENT_FAILED 발행 (별도 커밋되므로 AFTER_COMMIT 리스너 정상 발화)
            transactionTemplate.execute(status -> {
                Payment payment = paymentRepository.findById(paymentId).orElseThrow();
                payment.fail(e.getFailureCode(), e.getFailureReason(),
                    e.getRequestPayload(), e.getResponsePayload(), OffsetDateTime.now());
                paymentRepository.save(payment);
                applicationEventPublisher.publishEvent(new PaymentFailedEvent(payment));
                return null;
            });

            throw new BusinessException(e.getErrorCode(), e.getFailureReason());

        } catch (DataIntegrityViolationException e) {
            // uk_payment_order_paid 충돌 — 서로 다른 paymentKey로 같은 주문 동시 결제
            throw new BusinessException(PaymentErrorCode.DUPLICATE_PAYMENT);
        }
    }
}
