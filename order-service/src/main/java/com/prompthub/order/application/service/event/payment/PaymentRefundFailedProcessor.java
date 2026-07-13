package com.prompthub.order.application.service.event.payment;

import com.prompthub.order.application.service.event.common.ConsumedEventContext;
import com.prompthub.order.application.service.event.common.ProcessedEventService;
import com.prompthub.order.application.service.refund.RefundResultContextLoader;
import com.prompthub.order.domain.enums.OrderRefundStatus;
import com.prompthub.order.domain.model.OrderRefund;
import com.prompthub.order.infra.messaging.kafka.event.PaymentRefundFailedPayload;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PaymentRefundFailedProcessor implements PaymentEventProcessor<PaymentRefundFailedPayload> {

    private final ProcessedEventService processedEventService;
    private final RefundResultContextLoader contextLoader;

    public void process(ConsumedEventContext context, PaymentRefundFailedPayload payload) {
        processedEventService.executeOnce(context, () -> processEvent(payload));
    }

    private void processEvent(PaymentRefundFailedPayload payload) {
        OrderRefund refund = contextLoader.loadValidatedRefund(payload);
        if (refund.getStatus() == OrderRefundStatus.FAILED) {
            return;
        }
        contextLoader.loadOrderForUpdate(payload.orderId());
        refund.fail(payload.failureCode(), payload.failureReason(), payload.failedAt());
    }
}
