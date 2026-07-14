package com.prompthub.order.infra.grpc.client.seller;

import com.prompthub.user.grpc.seller.SellerQueryServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile({"dev", "prod"})
public class SellerGrpcClientConfig {

	@Bean(destroyMethod = "shutdown")
	public ManagedChannel sellerManagedChannel(
		@Value("${prompthub.grpc.seller.host:localhost}") String host,
		@Value("${prompthub.grpc.seller.port:9091}") int port
	) {
		return ManagedChannelBuilder.forAddress(host, port)
			.usePlaintext()
			.build();
	}

	@Bean
	public SellerQueryServiceGrpc.SellerQueryServiceBlockingStub sellerQueryServiceBlockingStub(
		ManagedChannel sellerManagedChannel
	) {
		return SellerQueryServiceGrpc.newBlockingStub(sellerManagedChannel);
	}
}
