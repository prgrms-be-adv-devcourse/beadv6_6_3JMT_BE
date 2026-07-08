package com.prompthub.user.sellersettlement.domain.exception;

import com.prompthub.exception.BusinessException;
import com.prompthub.user.global.exception.UserErrorCode;

public class SellerSettlementNotFoundException extends BusinessException {

    public SellerSettlementNotFoundException() {
        super(UserErrorCode.SELLER_SETTLEMENT_NOT_FOUND);
    }
}
