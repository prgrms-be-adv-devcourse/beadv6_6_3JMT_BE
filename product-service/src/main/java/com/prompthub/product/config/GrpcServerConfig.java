package com.prompthub.product.config;

import com.prompthub.product.infra.grpc.ProductQueryGrpcService;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class GrpcServerConfig {

	@Value("${grpc.server.port:9082}")
	private int grpcPort;

	private final ProductQueryGrpcService productQueryGrpcService;

	private Server server;

	@EventListener(ContextRefreshedEvent.class)
	public void startGrpcServer() throws IOException {
		if (server != null && !server.isShutdown()) {
			return;
		}
		server = ServerBuilder.forPort(grpcPort)
			.addService(productQueryGrpcService)
			.build()
			.start();
		log.info("gRPC server started on port {}", grpcPort);
	}

	@PreDestroy
	public void stopGrpcServer() {
		if (server != null) {
			server.shutdown();
			log.info("gRPC server stopped");
		}
	}
}
