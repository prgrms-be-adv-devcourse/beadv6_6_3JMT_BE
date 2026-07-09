package com.prompthub.order.infra.grpc.server;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;

import java.io.IOException;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class GrpcServerConfig {

    @Value("${grpc.server.port:9083}")
    private int grpcPort;

    private final OrderPaymentGrpcService orderPaymentGrpcService;
    private Server server;

    @EventListener(ContextRefreshedEvent.class)
    public void startGrpcServer() throws IOException {
        if (server != null && !server.isShutdown()) {
            return;
        }
        server = ServerBuilder.forPort(grpcPort)
                .addService(orderPaymentGrpcService)
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
