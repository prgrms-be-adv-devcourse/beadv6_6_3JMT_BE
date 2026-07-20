package com.prompthub.admin.product.domain.exception;

import com.prompthub.admin.global.exception.AdminErrorCode;
import com.prompthub.admin.global.exception.AdminException;

public class ProductAdminException extends AdminException {

	public ProductAdminException(AdminErrorCode errorCode) {
		super(errorCode);
	}
}
