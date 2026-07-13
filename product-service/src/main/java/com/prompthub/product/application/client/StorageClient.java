package com.prompthub.product.application.client;

public interface StorageClient {

    String generatePresignedDownloadUrl(String key);

    String generatePresignedUploadUrl(String key, String contentType);

    String upload(String key, byte[] bytes, String contentType);

    void copyObject(String sourceKey, String destKey);

    void deleteObject(String key);
}
