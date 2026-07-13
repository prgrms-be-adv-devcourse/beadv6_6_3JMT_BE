package com.prompthub.paymentservice.application.service;

import com.prompthub.exception.BusinessException;
import com.prompthub.paymentservice.application.dto.command.ConfirmPaymentCommand;
import com.prompthub.paymentservice.application.dto.command.RecordOrderSnapshotCommand;
import com.prompthub.paymentservice.application.dto.result.PaymentResult;
import com.prompthub.paymentservice.application.exception.PaymentErrorCode;
import com.prompthub.paymentservice.application.gateway.external.ConfirmResult;
import com.prompthub.paymentservice.application.gateway.external.OrderGateway;
import com.prompthub.paymentservice.application.gateway.external.OrderPaymentInfo;
import com.prompthub.paymentservice.application.gateway.external.PaymentGateway;
import com.prompthub.paymentservice.application.gateway.external.PaymentGatewayException;
import com.prompthub.paymentservice.application.usecase.ConfirmPaymentUseCase;
import com.prompthub.paymentservice.application.usecase.RecordOrderSnapshotUseCase;
import com.prompthub.paymentservice.domain.event.PaymentApprovedEvent;
import com.prompthub.paymentservice.domain.event.PaymentFailedEvent;
import com.prompthub.paymentservice.domain.model.OrderSnapshot;
import com.prompthub.paymentservice.domain.model.OrderSnapshotSource;
import com.prompthub.paymentservice.domain.model.Payment;
import com.prompthub.paymentservice.domain.model.PaymentStatus;
import com.prompthub.paymentservice.domain.repository.OrderSnapshotRepository;
import com.prompthub.paymentservice.domain.repository.PaymentRepository;
import java.time.OffsetDateTime;
import java.util.Set;
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

    // 진행·완료 상태가 이미 있으면 재결제 차단. REQUESTED·FAILED·READY는 비차단(재결제 허용).
    private static final Set<PaymentStatus> BLOCKING_STATUSES = Set.of(
        PaymentStatus.PAID, PaymentStatus.REFUNDING, PaymentStatus.REFUNDED, PaymentStatus.UNKNOWN
    );

    private final PaymentRepository paymentRepository;
    private final OrderSnapshotRepository orderSnapshotRepository;
    private final OrderGateway orderGateway;
    private final RecordOrderSnapshotUseCase recordOrderSnapshotUseCase;
    private final PaymentGateway paymentGateway;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final TransactionTemplate transactionTemplate;
    private final boolean testMode;

    public ConfirmPaymentService(
        PaymentRepository paymentRepository,
        OrderSnapshotRepository orderSnapshotRepository,
        OrderGateway orderGateway,
        RecordOrderSnapshotUseCase recordOrderSnapshotUseCase,
        PaymentGateway paymentGateway,
        ApplicationEventPublisher applicationEventPublisher,
        TransactionTemplate transactionTemplate,
        @Value("${payment.toss.test-mode:false}") boolean testMode
    ) {
        this.paymentRepository = paymentRepository;
        this.orderSnapshotRepository = orderSnapshotRepository;
        this.orderGateway = orderGateway;
        this.recordOrderSnapshotUseCase = recordOrderSnapshotUseCase;
        this.paymentGateway = paymentGateway;
        this.applicationEventPublisher = applicationEventPublisher;
        this.transactionTemplate = transactionTemplate;
        this.testMode = testMode;
    }

    @Override
    public PaymentResult confirm(ConfirmPaymentCommand command) {
        // TX1: 스냅샷 확보 + 검증 + Payment 생성/REQUESTED — 커밋 후 DB 커넥션 반납
        Prepared prepared;
        try {
            prepared = transactionTemplate.execute(status -> {
                OrderSnapshot snapshot = resolveSnapshot(command.orderId());

                if (!snapshot.getBuyerId().equals(command.userId())) {
                    throw new BusinessException(PaymentErrorCode.NOT_ORDER_OWNER);
                }
                if (paymentRepository.existsByOrderIdAndStatusIn(command.orderId(), BLOCKING_STATUSES)) {
                    throw new BusinessException(PaymentErrorCode.DUPLICATE_PAYMENT);
                }

                Payment payment = Payment.create(
                    command.orderId(), command.userId(),
                    command.paymentKey(), PG_PROVIDER, PAYMENT_METHOD, testMode,
                    snapshot.getTotalAmount()
                );
                paymentRepository.saveAndFlush(payment);
                payment.markRequested(OffsetDateTime.now());
                paymentRepository.save(payment);
                return new Prepared(payment.getId(), snapshot.getTotalAmount());
            });
        } catch (DataIntegrityViolationException e) {
            // pg_tx_id UNIQUE 충돌 — 동일 paymentKey 재요청(D8)
            throw new BusinessException(PaymentErrorCode.DUPLICATE_PAYMENT);
        }

        UUID paymentId = prepared.paymentId();

        // Toss API 호출 — 트랜잭션 밖, DB 커넥션 미점유. 금액의 진실 공급원은 스냅샷.
        try {
            ConfirmResult result = paymentGateway.confirm(
                command.paymentKey(), command.orderId(), prepared.amount()
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
            // uk_payment_order_paid 충돌 — 서로 다른 paymentKey로 같은 주문 동시 결제(§5.3)
            throw new BusinessException(PaymentErrorCode.DUPLICATE_PAYMENT);
        }
    }

    /**
     * 스냅샷 확보. 로컬에 있으면 그대로, 없으면 gRPC 폴백으로 조회해 기록한다.
     * gRPC 기록은 REQUIRES_NEW라 유니크 충돌 시에도 TX1을 오염시키지 않으므로, 충돌 시 재조회로 회복한다.
     */
    private OrderSnapshot resolveSnapshot(UUID orderId) {
        return orderSnapshotRepository.findByOrderId(orderId)
            .orElseGet(() -> fetchAndRecordSnapshot(orderId));
    }

    private OrderSnapshot fetchAndRecordSnapshot(UUID orderId) {
        OrderPaymentInfo info = orderGateway.getOrderPaymentInfo(orderId);
        try {
            return recordOrderSnapshotUseCase.record(new RecordOrderSnapshotCommand(
                info.orderId(), info.buyerId(), info.totalAmount(),
                info.orderCreatedAt(), OrderSnapshotSource.QUERY
            ));
        } catch (DataIntegrityViolationException raced) {
            // 그 사이 ORDER_CREATED 이벤트가 스냅샷을 기록 — 재조회로 회복
            return orderSnapshotRepository.findByOrderId(orderId)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.ORDER_INFO_UNAVAILABLE));
        }
    }

    private record Prepared(UUID paymentId, int amount) {}
}
