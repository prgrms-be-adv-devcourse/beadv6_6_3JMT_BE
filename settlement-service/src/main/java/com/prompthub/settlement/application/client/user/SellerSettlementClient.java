package com.prompthub.settlement.application.client.user;

import com.prompthub.settlement.application.dto.delivery.SellerSettlementRegistrationCommand;
import com.prompthub.settlement.application.dto.delivery.SellerSettlementStoredSnapshot;

public interface SellerSettlementClient {

    SellerSettlementStoredSnapshot register(
            SellerSettlementRegistrationCommand command);
}
