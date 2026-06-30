package com.prompthub.product.presentation.dto.response;

import com.prompthub.product.application.client.PresignedUploadResult;

public record PresignedUrlResponse(String uploadUrl, String key, String objectUrl) {

    public static PresignedUrlResponse from(PresignedUploadResult result) {
        return new PresignedUrlResponse(result.uploadUrl(), result.key(), result.objectUrl());
    }
}
