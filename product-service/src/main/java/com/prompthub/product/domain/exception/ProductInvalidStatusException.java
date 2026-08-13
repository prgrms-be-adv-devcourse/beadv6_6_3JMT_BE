package com.prompthub.product.domain.exception;

/** 현재 상태에서 허용되지 않는 상품 상태 전이를 시도했을 때 던진다. */
public class ProductInvalidStatusException extends RuntimeException {

	public ProductInvalidStatusException(String message) {
		super(message);
	}
}
