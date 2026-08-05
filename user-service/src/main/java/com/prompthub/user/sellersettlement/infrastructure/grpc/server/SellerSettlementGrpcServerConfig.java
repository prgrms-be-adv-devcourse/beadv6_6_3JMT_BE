package com.prompthub.user.sellersettlement.infrastructure.grpc.server;

import com.prompthub.user.grpc.sellersettlement.SellerSettlementQueryServiceGrpc;
import com.prompthub.user.grpc.sellersettlement.command.SellerSettlementCommandServiceGrpc;
import com.prompthub.user.sellersettlement.infrastructure.grpc.server.analysis.SellerSettlementAnalysisGrpcAuthInterceptor;
import com.prompthub.user.sellersettlement.infrastructure.grpc.server.analysis.SellerSettlementAnalysisGrpcSecurityProperties;
import com.prompthub.user.sellersettlement.infrastructure.grpc.server.registration.SellerSettlementRegistrationGrpcAuthInterceptor;
import com.prompthub.user.sellersettlement.infrastructure.grpc.server.registration.SellerSettlementRegistrationGrpcSecurityProperties;
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
        SellerSettlementAnalysisGrpcSecurityProperties.class,
        SellerSettlementRegistrationGrpcSecurityProperties.class
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
            SellerSettlementAnalysisGrpcAuthInterceptor analysisAuthInterceptor,
            SellerSettlementCommandServiceGrpc.SellerSettlementCommandServiceImplBase registrationService,
            SellerSettlementRegistrationGrpcAuthInterceptor registrationAuthInterceptor) {
        return new SellerSettlementGrpcServerLifecycle(
                grpcPort,
                queryService,
                analysisAuthInterceptor,
                registrationService,
                registrationAuthInterceptor);
    }

    @Slf4j
    static final class SellerSettlementGrpcServerLifecycle {

        private final int grpcPort;
        private final SellerSettlementQueryServiceGrpc.SellerSettlementQueryServiceImplBase queryService;
        private final SellerSettlementAnalysisGrpcAuthInterceptor analysisAuthInterceptor;
        private final SellerSettlementCommandServiceGrpc.SellerSettlementCommandServiceImplBase registrationService;
        private final SellerSettlementRegistrationGrpcAuthInterceptor registrationAuthInterceptor;
        private Server server;

        private SellerSettlementGrpcServerLifecycle(
                int grpcPort,
                SellerSettlementQueryServiceGrpc.SellerSettlementQueryServiceImplBase queryService,
                SellerSettlementAnalysisGrpcAuthInterceptor analysisAuthInterceptor,
                SellerSettlementCommandServiceGrpc.SellerSettlementCommandServiceImplBase registrationService,
                SellerSettlementRegistrationGrpcAuthInterceptor registrationAuthInterceptor) {
            this.grpcPort = grpcPort;
            this.queryService = queryService;
            this.analysisAuthInterceptor = analysisAuthInterceptor;
            this.registrationService = registrationService;
            this.registrationAuthInterceptor = registrationAuthInterceptor;
        }

        @EventListener(ContextRefreshedEvent.class)
        public void startGrpcServer() throws IOException {
            if (server != null && !server.isShutdown()) {
                return;
            }
            server = ServerBuilder.forPort(grpcPort)
                    .addService(ServerInterceptors.intercept(
                            queryService, analysisAuthInterceptor))
                    .addService(ServerInterceptors.intercept(
                            registrationService, registrationAuthInterceptor))
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
