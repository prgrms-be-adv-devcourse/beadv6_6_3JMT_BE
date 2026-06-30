package com.prompthub.product.infra.storage;

import com.prompthub.product.application.client.PresignedUploadResult;
import com.prompthub.product.application.client.StorageClient;
import com.prompthub.product.config.AwsS3Properties;
import com.prompthub.product.exception.ProductException;
import com.prompthub.product.exception.enums.ProductErrorCode;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@Slf4j
@Component
@RequiredArgsConstructor
public class S3StorageAdapter implements StorageClient {

    private static final Duration PRESIGNED_URL_EXPIRATION = Duration.ofMinutes(15);

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final AwsS3Properties awsS3Properties;

    @Override
    public PresignedUploadResult generatePresignedUrl(String key, String contentType) {
        try {
            PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(PRESIGNED_URL_EXPIRATION)
                .putObjectRequest(r -> r
                    .bucket(awsS3Properties.s3().bucket())
                    .key(key)
                    .contentType(contentType)
                    .build())
                .build();

            PresignedPutObjectRequest presigned = s3Presigner.presignPutObject(presignRequest);
            String objectUrl = buildObjectUrl(key);
            return new PresignedUploadResult(presigned.url().toString(), key, objectUrl);
        } catch (Exception e) {
            log.error("S3 presign failed: {}", e.getMessage(), e);
            throw new ProductException(ProductErrorCode.S3_PRESIGN_FAILED);
        }
    }

    @Override
    public String upload(String key, byte[] bytes, String contentType) {
        try {
            PutObjectRequest request = PutObjectRequest.builder()
                .bucket(awsS3Properties.s3().bucket())
                .key(key)
                .contentType(contentType)
                .build();
            s3Client.putObject(request, RequestBody.fromBytes(bytes));
            return buildObjectUrl(key);
        } catch (Exception e) {
            log.error("S3 upload failed key={}: {}", key, e.getMessage(), e);
            throw new ProductException(ProductErrorCode.S3_PRESIGN_FAILED);
        }
    }

    private String buildObjectUrl(String key) {
        return "https://" + awsS3Properties.s3().bucket()
            + ".s3." + awsS3Properties.region() + ".amazonaws.com/" + key;
    }
}
