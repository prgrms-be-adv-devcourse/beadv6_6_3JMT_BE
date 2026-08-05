package com.prompthub.settlement.application.usecase.delivery;

import com.prompthub.settlement.application.dto.delivery.SettlementDeliverySummary;
import com.prompthub.settlement.domain.model.delivery.SettlementDeliveryStatus;
import java.util.UUID;

public interface SettlementDeliveryUseCase {

    SettlementDeliverySummary deliverBatch(UUID batchId);

    SettlementDeliveryStatus retry(UUID deliveryId);
}
