package com.prompthub.user.sellersettlement.infrastructure.grpc;

import com.prompthub.user.grpc.sellersettlement.SellerSettlementQueryServiceGrpc;
import com.prompthub.user.grpc.sellersettlement.command.SellerSettlementCommandServiceGrpc;
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
@EnableConfigurationProperties({
        SellerSettlementGrpcSecurityProperties.class,
        SellerSettlementCommandGrpcSecurityProperties.class
})
public class SellerSettlementGrpcServerConfig {

    @Bean
    @ConditionalOnProperty(
            prefix = "grpc.server",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    SellerSettlementGrpcServerLifecycle sellerSettlementGrpcServerLifecycle(
            @Value("${grpc.server.port:9081}") int grpcPort,
            SellerSettlementQueryServiceGrpc.SellerSettlementQueryServiceImplBase queryService,
            SellerSettlementGrpcAuthInterceptor queryAuthInterceptor,
            SellerSettlementCommandServiceGrpc.SellerSettlementCommandServiceImplBase commandService,
            SellerSettlementCommandGrpcAuthInterceptor commandAuthInterceptor) {
        return new SellerSettlementGrpcServerLifecycle(
                grpcPort,
                queryService,
                queryAuthInterceptor,
                commandService,
                commandAuthInterceptor);
    }

    @Slf4j
    static final class SellerSettlementGrpcServerLifecycle {

        private final int grpcPort;
        private final SellerSettlementQueryServiceGrpc.SellerSettlementQueryServiceImplBase queryService;
        private final SellerSettlementGrpcAuthInterceptor queryAuthInterceptor;
        private final SellerSettlementCommandServiceGrpc.SellerSettlementCommandServiceImplBase commandService;
        private final SellerSettlementCommandGrpcAuthInterceptor commandAuthInterceptor;
        private Server server;

        private SellerSettlementGrpcServerLifecycle(
                int grpcPort,
                SellerSettlementQueryServiceGrpc.SellerSettlementQueryServiceImplBase queryService,
                SellerSettlementGrpcAuthInterceptor queryAuthInterceptor,
                SellerSettlementCommandServiceGrpc.SellerSettlementCommandServiceImplBase commandService,
                SellerSettlementCommandGrpcAuthInterceptor commandAuthInterceptor) {
            this.grpcPort = grpcPort;
            this.queryService = queryService;
            this.queryAuthInterceptor = queryAuthInterceptor;
            this.commandService = commandService;
            this.commandAuthInterceptor = commandAuthInterceptor;
        }

        @EventListener(ContextRefreshedEvent.class)
        public void startGrpcServer() throws IOException {
            if (server != null && !server.isShutdown()) {
                return;
            }
            server = ServerBuilder.forPort(grpcPort)
                    .addService(ServerInterceptors.intercept(
                            queryService, queryAuthInterceptor))
                    .addService(ServerInterceptors.intercept(
                            commandService, commandAuthInterceptor))
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
