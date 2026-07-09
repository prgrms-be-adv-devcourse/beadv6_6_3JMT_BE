package com.prompthub.user.wishlist.infrastructure.grpc;

import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.ImportGrpcClients;

import com.prompthub.user.grpc.product.ProductServiceGrpc;

@Configuration
@ImportGrpcClients(
        target = "product-service",
        types = ProductServiceGrpc.ProductServiceBlockingStub.class
)
public class GrpcClientConfig {
}
