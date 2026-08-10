package com.prompthub.product.presentation.dto.response;

public record UploadUrlResponse(
	String tempObjectKey,
	String presignedPutUrl,
	String presignedGetUrl
) {
}
