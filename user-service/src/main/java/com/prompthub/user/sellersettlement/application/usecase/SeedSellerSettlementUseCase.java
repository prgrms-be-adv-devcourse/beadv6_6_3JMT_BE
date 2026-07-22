package com.prompthub.user.sellersettlement.application.usecase;

import com.prompthub.user.sellersettlement.application.event.SettlementCreatedEventV1;
import com.prompthub.user.sellersettlement.application.event.SettlementCreatedEventV2;

public interface SeedSellerSettlementUseCase {

    void seed(SettlementCreatedEventV1 event);

    void seed(SettlementCreatedEventV2 event);
}
