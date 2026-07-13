package com.prompthub.user.sellersettlement.application.usecase;

import com.prompthub.user.sellersettlement.application.event.SettlementCreatedEvent;

public interface SeedSellerSettlementUseCase {

    void seed(SettlementCreatedEvent event);
}
