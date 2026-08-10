package com.prompthub.product.application.service.fileupload;

import com.prompthub.product.exception.ProductException;
import com.prompthub.product.exception.enums.ProductErrorCode;

public enum UploadPurpose {

	THUMBNAIL("thumbnail"),
	IMAGE("image"),
	FILE("file");

	private final String code;

	UploadPurpose(String code) {
		this.code = code;
	}

	public String code() {
		return code;
	}

	public static UploadPurpose from(String value) {
		for (UploadPurpose purpose : values()) {
			if (purpose.code.equals(value)) {
				return purpose;
			}
		}
		throw new ProductException(ProductErrorCode.INVALID_UPLOAD_FILE_TYPE);
	}
}
