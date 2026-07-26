package com.prompthub.user.sellersettlement.infrastructure.grpc;

import com.prompthub.user.grpc.sellersettlement.SellerSettlementQueryServiceGrpc;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptors;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SellerSettlementGrpcSecurityProperties.class)
public class SellerSettlementGrpcServerConfig {

    @Bean
    @ConditionalOnProperty(
            prefix = "grpc.server",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    SellerSettlementGrpcServerLifecycle sellerSettlementGrpcServerLifecycle(
            @Value("${grpc.server.port:9081}") int grpcPort,
            SellerSettlementQueryServiceGrpc.SellerSettlementQueryServiceImplBase service,
            SellerSettlementGrpcAuthInterceptor authInterceptor) {
        return new SellerSettlementGrpcServerLifecycle(grpcPort, service, authInterceptor);
    }

    @Slf4j
    static final class SellerSettlementGrpcServerLifecycle {

        private final int grpcPort;
        private final SellerSettlementQueryServiceGrpc.SellerSettlementQueryServiceImplBase service;
        private final SellerSettlementGrpcAuthInterceptor authInterceptor;
        private Server server;

        private SellerSettlementGrpcServerLifecycle(
                int grpcPort,
                SellerSettlementQueryServiceGrpc.SellerSettlementQueryServiceImplBase service,
                SellerSettlementGrpcAuthInterceptor authInterceptor) {
            this.grpcPort = grpcPort;
            this.service = service;
            this.authInterceptor = authInterceptor;
        }

        @EventListener(ContextRefreshedEvent.class)
        public void startGrpcServer() throws IOException {
            if (server != null && !server.isShutdown()) {
                return;
            }
            server = ServerBuilder.forPort(grpcPort)
                    .addService(ServerInterceptors.intercept(service, authInterceptor))
                    .build()
                    .start();
            log.info("판매자 정산 gRPC 서버가 포트 {}에서 시작되었습니다.", grpcPort);
        }

        @PreDestroy
        public void stopGrpcServer() {
            if (server != null) {
                server.shutdown();
                log.info("판매자 정산 gRPC 서버가 종료되었습니다.");
            }
        }
    }
}
