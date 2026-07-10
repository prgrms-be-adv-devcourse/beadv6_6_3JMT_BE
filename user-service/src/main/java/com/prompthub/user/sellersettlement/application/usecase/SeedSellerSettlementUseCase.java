package com.prompthub.user.sellersettlement.application.usecase;

import com.prompthub.user.sellersettlement.application.event.SettlementCreatedPayload;

public interface SeedSellerSettlementUseCase {

    void seed(SettlementCreatedPayload payload);
}
