package com.prompthub.user.wishlist.domain.exception;

import com.prompthub.exception.BusinessException;
import com.prompthub.user.global.exception.UserErrorCode;

public class WishlistDuplicatedException extends BusinessException {

    public WishlistDuplicatedException() {
        super(UserErrorCode.WISHLIST_DUPLICATED);
    }
}
