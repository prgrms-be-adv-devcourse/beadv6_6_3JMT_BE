package com.prompthub.product.exception.enums;

import com.prompthub.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ProductErrorCode implements ErrorCode {

	INVALID_INPUT_VALUE(HttpStatus.BAD_REQUEST, "V001", "입력값이 올바르지 않습니다."),
	INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "SYS001", "서버 내부 오류가 발생했습니다."),
	PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "P001", "상품이 존재하지 않습니다."),
	PRODUCT_NOT_ON_SALE(HttpStatus.BAD_REQUEST, "P002", "판매 중인 상품이 아닙니다.");

	private final HttpStatus status;
	private final String code;
	private final String message;
}
