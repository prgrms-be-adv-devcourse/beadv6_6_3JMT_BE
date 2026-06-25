package com.prompthub.settlement.application.usecase;

import com.prompthub.settlement.application.event.OrderEventEnvelope;
import com.prompthub.settlement.application.event.OrderPaidEvent;
import com.prompthub.settlement.application.event.OrderRefundedEvent;

public interface SettlementSourceUseCase {

    void recordOrderPaid(OrderEventEnvelope<OrderPaidEvent> envelope);

    void recordOrderRefunded(OrderEventEnvelope<OrderRefundedEvent> envelope);
}
