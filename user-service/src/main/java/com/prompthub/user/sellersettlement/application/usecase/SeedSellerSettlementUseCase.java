package com.prompthub.user.sellersettlement.application.usecase;

import com.prompthub.user.sellersettlement.application.event.SettlementCreatedMessage;

public interface SeedSellerSettlementUseCase {

    void seed(SettlementCreatedMessage message);
}
