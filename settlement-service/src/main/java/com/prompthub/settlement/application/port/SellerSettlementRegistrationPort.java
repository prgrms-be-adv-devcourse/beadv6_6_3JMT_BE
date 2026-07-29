package com.prompthub.settlement.application.port;

import com.prompthub.settlement.application.dto.SellerSettlementRegistrationCommand;
import com.prompthub.settlement.application.dto.SellerSettlementStoredSnapshot;

public interface SellerSettlementRegistrationPort {

    SellerSettlementStoredSnapshot register(
            SellerSettlementRegistrationCommand command);
}
