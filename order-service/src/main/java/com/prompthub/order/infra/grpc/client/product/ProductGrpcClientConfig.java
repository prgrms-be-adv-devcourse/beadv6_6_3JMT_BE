package com.prompthub.order.infra.grpc.client.product;

import com.prompthub.grpc.product.v1.ProductInternalServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile({"dev", "prod"})
public class ProductGrpcClientConfig {

	@Bean(destroyMethod = "shutdown")
	public ManagedChannel productManagedChannel(
		@Value("${prompthub.grpc.product.host:localhost}") String host,
		@Value("${prompthub.grpc.product.port:9092}") int port
	) {
		return ManagedChannelBuilder.forAddress(host, port)
			.usePlaintext()
			.build();
	}

	@Bean
	public ProductInternalServiceGrpc.ProductInternalServiceBlockingStub productInternalServiceBlockingStub(
		ManagedChannel productManagedChannel
	) {
		return ProductInternalServiceGrpc.newBlockingStub(productManagedChannel);
	}
}
