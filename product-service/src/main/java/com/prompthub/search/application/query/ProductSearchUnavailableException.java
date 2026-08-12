package com.prompthub.search.application.query;

public class ProductSearchUnavailableException extends RuntimeException {

	public ProductSearchUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}

	public ProductSearchUnavailableException(String message) {
		super(message);
	}
}
