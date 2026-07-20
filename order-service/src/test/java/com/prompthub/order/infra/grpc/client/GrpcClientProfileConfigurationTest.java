package com.prompthub.order.infra.grpc.client;

import com.prompthub.order.application.client.ProductClient;
import com.prompthub.order.application.client.SellerClient;
import com.prompthub.order.infra.grpc.client.product.ProductGrpcClientAdapter;
import com.prompthub.order.infra.grpc.client.product.ProductGrpcClientConfig;
import com.prompthub.order.infra.grpc.client.product.ProductGrpcResilienceConfig;
import com.prompthub.order.infra.grpc.client.seller.SellerGrpcClientAdapter;
import com.prompthub.order.infra.grpc.client.seller.SellerGrpcClientConfig;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class GrpcClientProfileConfigurationTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(
			ProductGrpcClientConfig.class,
			ProductGrpcResilienceConfig.class,
			ProductGrpcClientAdapter.class,
			SellerGrpcClientConfig.class,
			SellerGrpcClientAdapter.class
		)
		.withInitializer(context -> context.getBeanFactory()
			.setConversionService(ApplicationConversionService.getSharedInstance()))
		.withPropertyValues(
			"resilience4j.circuitbreaker.configs.product-grpc-default.sliding-window-size=20",
			"resilience4j.circuitbreaker.configs.product-grpc-default.minimum-number-of-calls=10",
			"resilience4j.circuitbreaker.configs.product-grpc-default.failure-rate-threshold=50",
			"resilience4j.circuitbreaker.configs.product-grpc-default.slow-call-duration-threshold=700ms",
			"resilience4j.circuitbreaker.configs.product-grpc-default.slow-call-rate-threshold=50",
			"resilience4j.circuitbreaker.configs.product-grpc-default.wait-duration-in-open-state=30s",
			"resilience4j.circuitbreaker.configs.product-grpc-default.permitted-number-of-calls-in-half-open-state=3",
			"resilience4j.bulkhead.instances.product-grpc-bulkhead.max-concurrent-calls=20",
			"resilience4j.bulkhead.instances.product-grpc-bulkhead.max-wait-duration=0ms"
		);

	@ParameterizedTest
	@ValueSource(strings = {"default", "local", "dev", "prod"})
	void registersGrpcClientsForEveryRuntimeProfile(String profile) {
		contextRunner
			.withPropertyValues("spring.profiles.active=" + profile)
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(ProductClient.class);
				assertThat(context.getBean(ProductClient.class)).isInstanceOf(ProductGrpcClientAdapter.class);
				assertThat(context).hasSingleBean(SellerClient.class);
				assertThat(context.getBean(SellerClient.class)).isInstanceOf(SellerGrpcClientAdapter.class);
			});
	}
}
