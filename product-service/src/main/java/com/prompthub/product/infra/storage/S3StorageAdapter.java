package com.prompthub.product.infra.storage;

import com.prompthub.product.application.client.StorageClient;
import com.prompthub.product.config.AwsS3Properties;
import com.prompthub.product.exception.ProductException;
import com.prompthub.product.exception.enums.ProductErrorCode;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@Slf4j
@Component
@RequiredArgsConstructor
public class S3StorageAdapter implements StorageClient {

    private static final Duration PRESIGNED_GET_EXPIRATION = Duration.ofMinutes(30);
    private static final Duration PRESIGNED_PUT_EXPIRATION = Duration.ofMinutes(10);

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final AwsS3Properties awsS3Properties;

    @Override
    public String generatePresignedDownloadUrl(String key) {
        try {
            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(PRESIGNED_GET_EXPIRATION)
                .getObjectRequest(GetObjectRequest.builder()
                    .bucket(awsS3Properties.s3().bucket())
                    .key(key)
                    .build())
                .build();
            PresignedGetObjectRequest presigned = s3Presigner.presignGetObject(presignRequest);
            return presigned.url().toString();
        } catch (Exception e) {
            log.error("S3 presign download failed key={}: {}", key, e.getMessage(), e);
            throw new ProductException(ProductErrorCode.S3_PRESIGN_FAILED);
        }
    }

    @Override
    public String generatePresignedUploadUrl(String key, String contentType) {
        try {
            PutObjectRequest objectRequest = PutObjectRequest.builder()
                .bucket(awsS3Properties.s3().bucket())
                .key(key)
                .contentType(contentType)
                .build();
            PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(PRESIGNED_PUT_EXPIRATION)
                .putObjectRequest(objectRequest)
                .build();
            PresignedPutObjectRequest presigned = s3Presigner.presignPutObject(presignRequest);
            return presigned.url().toString();
        } catch (Exception e) {
            log.error("S3 presign upload failed key={}: {}", key, e.getMessage(), e);
            throw new ProductException(ProductErrorCode.S3_PRESIGN_FAILED);
        }
    }

    @Override
    public void copyObject(String sourceKey, String destKey) {
        try {
            s3Client.copyObject(CopyObjectRequest.builder()
                .sourceBucket(awsS3Properties.s3().bucket())
                .sourceKey(sourceKey)
                .destinationBucket(awsS3Properties.s3().bucket())
                .destinationKey(destKey)
                .build());
        } catch (Exception e) {
            log.error("S3 copy failed {} -> {}: {}", sourceKey, destKey, e.getMessage(), e);
            throw new ProductException(ProductErrorCode.S3_PRESIGN_FAILED);
        }
    }

    @Override
    public void deleteObject(String key) {
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(awsS3Properties.s3().bucket())
                .key(key)
                .build());
        } catch (Exception e) {
            log.error("S3 delete failed key={}: {}", key, e.getMessage(), e);
        }
    }

}
