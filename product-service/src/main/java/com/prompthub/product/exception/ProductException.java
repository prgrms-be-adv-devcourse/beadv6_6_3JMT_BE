package com.prompthub.product.exception;

import com.prompthub.exception.BusinessException;
import com.prompthub.exception.ErrorCode;

public class ProductException extends BusinessException {

	public ProductException(ErrorCode errorCode) {
		super(errorCode);
	}

	public ProductException(ErrorCode errorCode, String message) {
		super(errorCode, message);
	}
}
