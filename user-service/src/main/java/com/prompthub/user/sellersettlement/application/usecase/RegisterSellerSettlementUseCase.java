package com.prompthub.user.sellersettlement.application.usecase;

import com.prompthub.user.sellersettlement.application.dto.RegisterSellerSettlementCommand;
import com.prompthub.user.sellersettlement.application.dto.RegisteredSellerSettlementSnapshot;

public interface RegisterSellerSettlementUseCase {

    RegisteredSellerSettlementSnapshot register(
            RegisterSellerSettlementCommand command);
}
