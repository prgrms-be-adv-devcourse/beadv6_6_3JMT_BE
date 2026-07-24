package com.prompthub.ai.settlement.infrastructure.client.user.config;

import com.prompthub.user.grpc.sellersettlement.SellerSettlementQueryServiceGrpc;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.ImportGrpcClients;

@Configuration(proxyBeanMethods = false)
@ImportGrpcClients(
        target = "user-service",
        types = SellerSettlementQueryServiceGrpc.SellerSettlementQueryServiceBlockingStub.class
)
public class UserGrpcClientConfig {
}
