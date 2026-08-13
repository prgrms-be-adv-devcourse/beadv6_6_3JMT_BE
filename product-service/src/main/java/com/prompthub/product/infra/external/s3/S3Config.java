package com.prompthub.product.infra.external.s3;

import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
@EnableConfigurationProperties(AwsS3Properties.class)
public class S3Config {

	// ponytail: 고정값. copy/delete는 소용량 요청이라 넉넉히 잡음 — 운영에서 짧아서 문제되면
	// 그때 설정값으로 뺀다.
	private static final Duration API_CALL_TIMEOUT = Duration.ofSeconds(10);
	private static final Duration API_CALL_ATTEMPT_TIMEOUT = Duration.ofSeconds(5);

	@Bean
	public S3Client s3Client(AwsS3Properties properties) {
		return S3Client.builder()
			.region(Region.of(properties.region()))
			.overrideConfiguration(ClientOverrideConfiguration.builder()
				.apiCallTimeout(API_CALL_TIMEOUT)
				.apiCallAttemptTimeout(API_CALL_ATTEMPT_TIMEOUT)
				.build())
			.build();
	}

	// presign은 로컬 서명 연산이라 네트워크 호출이 없다 — 타임아웃 대상이 아니다.
	@Bean
	public S3Presigner s3Presigner(AwsS3Properties properties) {
		return S3Presigner.builder()
			.region(Region.of(properties.region()))
			.build();
	}
}
