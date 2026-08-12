package com.prompthub.product.presentation.dto.response.fileupload;

public record UploadUrlResponse(
	String tempObjectKey,
	String presignedPutUrl,
	String presignedGetUrl
) {
}
