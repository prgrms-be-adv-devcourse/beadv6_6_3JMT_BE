package com.prompthub.product.application.gateway.external;

import com.prompthub.product.application.service.fileupload.UploadPurpose;
import com.prompthub.product.exception.ProductException;
import com.prompthub.product.exception.enums.ProductErrorCode;
import java.util.UUID;
import java.util.regex.Pattern;

public record ObjectStorageKey(String value) {

	private static final String TEMP_PREFIX = "products/temp/";
	private static final String PRODUCT_PREFIX = "products/";
	private static final Pattern FILE_NAME_PATTERN = Pattern.compile("[A-Za-z0-9-]+\\.[A-Za-z0-9]+");

	public ObjectStorageKey {
		if (value == null || value.isBlank()) {
			throw new ProductException(ProductErrorCode.INVALID_INPUT_VALUE, "object key는 비어 있을 수 없습니다.");
		}
		validateFormat(value);
	}

	public static ObjectStorageKey newTemp(UUID sellerId, UploadPurpose purpose, String extension) {
		String fileName = UUID.randomUUID() + "." + extension;
		return new ObjectStorageKey(TEMP_PREFIX + sellerId + "/" + purpose.code() + "/" + fileName);
	}

	public boolean isTemp() {
		return value.startsWith(TEMP_PREFIX);
	}

	public boolean isOwnedBy(UUID sellerId) {
		return isTemp() && segmentsAfterPrefix(TEMP_PREFIX)[0].equals(sellerId.toString());
	}

	public void validateFor(UUID sellerId, UploadPurpose expectedPurpose) {
		if (!isTemp()) {
			String[] segments = segmentsAfterPrefix(PRODUCT_PREFIX);
			if (!segments[1].equals(expectedPurpose.code())) {
				throw new ProductException(ProductErrorCode.INVALID_INPUT_VALUE);
			}
			return;
		}

		String[] segments = segmentsAfterPrefix(TEMP_PREFIX);
		if (!segments[0].equals(sellerId.toString())) {
			throw new ProductException(ProductErrorCode.PRODUCT_FORBIDDEN, "본인 소유의 업로드 파일만 사용할 수 있습니다.");
		}
		if (!segments[1].equals(expectedPurpose.code())) {
			throw new ProductException(ProductErrorCode.INVALID_INPUT_VALUE);
		}
	}

	public ObjectStorageKey promote(UUID productId, UUID sellerId, UploadPurpose expectedPurpose) {
		validateFor(sellerId, expectedPurpose);
		if (!isTemp()) {
			return this;
		}
		String[] segments = segmentsAfterPrefix(TEMP_PREFIX);
		return new ObjectStorageKey("products/" + productId + "/" + segments[1] + "/" + segments[2]);
	}

	private static void validateFormat(String value) {
		String prefix = value.startsWith(TEMP_PREFIX) ? TEMP_PREFIX : PRODUCT_PREFIX;
		if (!value.startsWith(prefix)) {
			throw new ProductException(ProductErrorCode.INVALID_INPUT_VALUE);
		}

		String[] segments = segmentsAfterPrefix(value, prefix);
		try {
			UUID.fromString(segments[0]);
			UploadPurpose.from(segments[1]);
		} catch (IllegalArgumentException | ProductException e) {
			throw new ProductException(ProductErrorCode.INVALID_INPUT_VALUE);
		}
		if (!FILE_NAME_PATTERN.matcher(segments[2]).matches()) {
			throw new ProductException(ProductErrorCode.INVALID_INPUT_VALUE);
		}
	}

	private String[] segmentsAfterPrefix(String prefix) {
		return segmentsAfterPrefix(value, prefix);
	}

	private static String[] segmentsAfterPrefix(String value, String prefix) {
		String[] segments = value.substring(prefix.length()).split("/", -1);
		if (segments.length != 3) {
			throw new ProductException(ProductErrorCode.INVALID_INPUT_VALUE);
		}
		return segments;
	}
}
