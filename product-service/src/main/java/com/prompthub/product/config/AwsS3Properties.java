package com.prompthub.product.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cloud.aws")
public record AwsS3Properties(String region, S3 s3) {

    public record S3(String bucket) {}
}
