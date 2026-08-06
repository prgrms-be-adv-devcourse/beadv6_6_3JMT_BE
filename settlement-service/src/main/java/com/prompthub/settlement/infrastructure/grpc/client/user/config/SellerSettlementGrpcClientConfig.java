package com.prompthub.settlement.infrastructure.grpc.client.user.config;

import com.prompthub.user.grpc.sellersettlement.command.SellerSettlementCommandServiceGrpc;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.ImportGrpcClients;

@Configuration
@EnableConfigurationProperties(SellerSettlementGrpcClientProperties.class)
@ImportGrpcClients(
        target = "user-service",
        types = SellerSettlementCommandServiceGrpc
                .SellerSettlementCommandServiceBlockingStub.class)
public class SellerSettlementGrpcClientConfig {
}
