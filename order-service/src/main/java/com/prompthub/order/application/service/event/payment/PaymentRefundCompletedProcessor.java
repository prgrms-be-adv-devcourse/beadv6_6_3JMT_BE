package com.prompthub.order.application.service.event.payment;

import com.prompthub.common.event.EventMessage;
import com.prompthub.order.application.service.event.common.ConsumedEventContext;
import com.prompthub.order.application.service.event.common.ProcessedEventService;
import com.prompthub.order.application.service.event.outbox.OutboxEventAppender;
import com.prompthub.order.application.service.event.order.OrderEventMessageFactory;
import com.prompthub.order.application.service.refund.RefundResultContextLoader;
import com.prompthub.order.domain.enums.OrderRefundStatus;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderRefund;
import com.prompthub.order.infra.messaging.kafka.event.OrderProductRefundedPayload;
import com.prompthub.order.infra.messaging.kafka.event.PaymentRefundCompletedPayload;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PaymentRefundCompletedProcessor implements PaymentEventProcessor<PaymentRefundCompletedPayload> {

    private final ProcessedEventService processedEventService;
    private final RefundResultContextLoader contextLoader;
    private final OrderEventMessageFactory eventMessageFactory;
    private final OutboxEventAppender outboxEventAppender;

    public void process(ConsumedEventContext context, PaymentRefundCompletedPayload payload) {
        processedEventService.executeOnce(context, () -> processEvent(payload));
    }

    private void processEvent(PaymentRefundCompletedPayload payload) {
        OrderRefund refund = contextLoader.loadValidatedRefund(payload);
        if (refund.getStatus() == OrderRefundStatus.COMPLETED) {
            return;
        }
        Order order = contextLoader.loadOrderForUpdate(payload.orderId());
        refund.complete(payload.refundedAt());
        order.recalculateRefundStatus();

        OrderProductRefundedPayload outboxPayload = OrderProductRefundedPayload.from(
            refund,
            payload.refundedAt()
        );
        EventMessage<OrderProductRefundedPayload> message = eventMessageFactory
            .createOrderProductRefundedMessage(order.getId(), outboxPayload);
        outboxEventAppender.append(message);
    }
}
