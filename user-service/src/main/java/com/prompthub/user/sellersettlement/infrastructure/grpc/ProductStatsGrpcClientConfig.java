package com.prompthub.user.sellersettlement.infrastructure.grpc;

import com.prompthub.grpc.product.ProductQueryServiceGrpc;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.ImportGrpcClients;

@Configuration
@ImportGrpcClients(
        target = "product-service",
        types = ProductQueryServiceGrpc.ProductQueryServiceBlockingStub.class
)
public class ProductStatsGrpcClientConfig {
}
