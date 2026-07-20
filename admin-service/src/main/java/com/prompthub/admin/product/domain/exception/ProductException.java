package com.prompthub.admin.product.domain.exception;

import com.prompthub.admin.global.exception.AdminErrorCode;
import com.prompthub.admin.global.exception.AdminException;

public class ProductException extends AdminException {

	public ProductException(AdminErrorCode errorCode) {
		super(errorCode);
	}
}
