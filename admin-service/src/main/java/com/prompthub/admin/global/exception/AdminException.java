package com.prompthub.admin.global.exception;

import com.prompthub.exception.BusinessException;
import com.prompthub.exception.ErrorCode;

public class AdminException extends BusinessException {

	public AdminException(ErrorCode errorCode) {
		super(errorCode);
	}
}
