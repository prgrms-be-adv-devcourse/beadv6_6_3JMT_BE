package com.prompthub.user.sellersettlement.domain.exception;

import com.prompthub.exception.BusinessException;
import com.prompthub.user.global.exception.UserErrorCode;

public class SellerSettlementAccessDeniedException extends BusinessException {

    public SellerSettlementAccessDeniedException() {
        super(UserErrorCode.SELLER_SETTLEMENT_ACCESS_DENIED);
    }
}
