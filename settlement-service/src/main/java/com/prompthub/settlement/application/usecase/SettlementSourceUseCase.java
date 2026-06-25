package com.prompthub.settlement.application.usecase;

import com.prompthub.settlement.application.event.OrderEventEnvelope;
import com.prompthub.settlement.application.event.OrderPaidEvent;

public interface SettlementSourceUseCase {

    void recordOrderPaid(OrderEventEnvelope<OrderPaidEvent> envelope);
}
