package com.prompthub.user.seller.domain.exception;

import com.prompthub.exception.BusinessException;
import com.prompthub.user.global.exception.UserErrorCode;

public class SellerAlreadyAppliedException extends BusinessException {

    public SellerAlreadyAppliedException() {
        super(UserErrorCode.AUTH_SELLER_ALREADY_APPLIED);
    }
}
