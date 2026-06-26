package com.prompthub.product.infra.grpc.server;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ProductGrpcServerConfig {

	@Bean(initMethod = "start", destroyMethod = "shutdown")
	public Server productGrpcServer(
		ProductInternalGrpcService productInternalGrpcService,
		@Value("${prompthub.grpc.server.port:9092}") int port
	) throws IOException {
		return NettyServerBuilder.forPort(port)
			.addService(productInternalGrpcService)
			.build();
	}
}
