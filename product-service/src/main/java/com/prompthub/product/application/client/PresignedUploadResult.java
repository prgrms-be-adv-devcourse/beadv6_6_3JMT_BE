package com.prompthub.product.application.client;

public record PresignedUploadResult(String uploadUrl, String key, String objectUrl) {}
