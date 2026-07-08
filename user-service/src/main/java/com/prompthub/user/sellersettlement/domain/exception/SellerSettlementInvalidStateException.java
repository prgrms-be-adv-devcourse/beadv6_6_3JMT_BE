package com.prompthub.user.sellersettlement.domain.exception;

import com.prompthub.exception.BusinessException;
import com.prompthub.user.global.exception.UserErrorCode;

public class SellerSettlementInvalidStateException extends BusinessException {

    public SellerSettlementInvalidStateException() {
        super(UserErrorCode.SELLER_SETTLEMENT_INVALID_STATE);
    }
}
