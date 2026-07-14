package com.prompthub.order.application.service.refund;

import com.prompthub.order.application.dto.RefundFailureCommand;
import com.prompthub.order.application.port.RefundMetricsPort;
import com.prompthub.order.domain.enums.OrderRefundStatus;
import com.prompthub.order.domain.model.OrderRefund;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OrderRefundFailureService {

    private final OrderRefundResultContextLoader contextLoader;
    private final RefundMetricsPort refundMetrics;

    @Transactional
    public void fail(RefundFailureCommand command) {
        OrderRefundResultContextLoader.Context context = contextLoader.loadForUpdate(command);
        OrderRefund refund = context.refund();
        boolean alreadyFailed = refund.getStatus() == OrderRefundStatus.FAILED;

        refund.fail(
            command.failureCode(), command.failureReason(), command.retryable(), command.failedAt()
        );
        if (alreadyFailed) {
            return;
        }

        context.order().restoreRefundProducts(refund.productIds());
        refundMetrics.recordFailed(command.retryable());
    }
}
