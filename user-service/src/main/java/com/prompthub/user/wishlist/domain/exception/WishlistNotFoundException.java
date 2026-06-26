package com.prompthub.user.wishlist.domain.exception;

import com.prompthub.exception.BusinessException;
import com.prompthub.user.global.exception.UserErrorCode;

public class WishlistNotFoundException extends BusinessException {

    public WishlistNotFoundException() {
        super(UserErrorCode.WISHLIST_NOT_FOUND);
    }
}
