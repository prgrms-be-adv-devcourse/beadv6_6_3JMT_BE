package com.prompthub.product.domain.model.vo;

import java.util.Objects;

public record ProductDeliverable(Type type, String value) {

	public ProductDeliverable {
		Objects.requireNonNull(type, "type must not be null");
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("deliverable value must not be blank");
		}
	}

	public enum Type {
		INLINE_CONTENT,
		FILE_OBJECT_KEY,
		EXTERNAL_URL
	}
}
