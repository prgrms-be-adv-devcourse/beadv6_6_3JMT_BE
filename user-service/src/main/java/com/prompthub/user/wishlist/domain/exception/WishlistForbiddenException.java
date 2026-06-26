package com.prompthub.user.wishlist.domain.exception;

import com.prompthub.exception.BusinessException;
import com.prompthub.user.global.exception.UserErrorCode;

public class WishlistForbiddenException extends BusinessException {

    public WishlistForbiddenException() {
        super(UserErrorCode.WISHLIST_FORBIDDEN);
    }
}
