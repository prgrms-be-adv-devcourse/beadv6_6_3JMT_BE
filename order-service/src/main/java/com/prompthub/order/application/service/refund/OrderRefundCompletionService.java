package com.prompthub.order.application.service.refund;

import com.prompthub.order.application.dto.RefundCompletionCommand;
import com.prompthub.order.application.port.OrderRefundCompletedEventPort;
import com.prompthub.order.application.port.RefundMetricsPort;
import com.prompthub.order.domain.enums.OrderRefundStatus;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderRefund;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OrderRefundCompletionService {

    private final OrderRefundResultContextLoader contextLoader;
    private final OrderRefundCompletedEventPort completedEventPort;
    private final RefundMetricsPort refundMetrics;

    @Transactional
    public void complete(RefundCompletionCommand command) {
        OrderRefundResultContextLoader.Context context = contextLoader.loadForUpdate(command);
        OrderRefund refund = context.refund();
        Order order = context.order();
        boolean alreadyCompleted = refund.getStatus() == OrderRefundStatus.COMPLETED;

        refund.complete(command.refundedAt());
        if (alreadyCompleted) {
            return;
        }

        order.completeRefundProducts(refund.productIds(), command.refundedAt());
        completedEventPort.emit(refund, order, command.refundedAt());
        refundMetrics.recordCompleted();
    }
}
