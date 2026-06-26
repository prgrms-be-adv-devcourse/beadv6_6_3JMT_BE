package com.prompthub.product.config;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Slf4j
@Configuration
@Profile({"prod", "local"})
public class GrpcSellerClientConfig {

	@Value("${grpc.client.user-service.host:user-service}")
	private String host;

	@Value("${grpc.client.user-service.port:9081}")
	private int port;

	private ManagedChannel channel;

	@Bean
	public ManagedChannel userServiceGrpcChannel() {
		channel = ManagedChannelBuilder.forAddress(host, port)
			.usePlaintext()
			.build();
		log.info("gRPC channel to user-service created: {}:{}", host, port);
		return channel;
	}

	@PreDestroy
	public void shutdown() {
		if (channel != null && !channel.isShutdown()) {
			channel.shutdown();
			log.info("gRPC channel to user-service shut down");
		}
	}
}
