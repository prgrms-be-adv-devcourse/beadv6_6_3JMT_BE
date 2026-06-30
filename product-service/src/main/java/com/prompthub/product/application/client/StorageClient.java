package com.prompthub.product.application.client;

public interface StorageClient {

    PresignedUploadResult generatePresignedUrl(String key, String contentType);

    String upload(String key, byte[] bytes, String contentType);
}
