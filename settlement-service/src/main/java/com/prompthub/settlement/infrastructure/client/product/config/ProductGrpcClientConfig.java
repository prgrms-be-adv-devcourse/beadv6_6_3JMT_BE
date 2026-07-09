package com.prompthub.settlement.infrastructure.client.product.config;

import com.prompthub.settlement.grpc.product.ProductQueryServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ProductGrpcClientConfig {

    @Bean
    ProductQueryServiceGrpc.ProductQueryServiceBlockingStub productQueryStub(
            @Value("${grpc.client.product-service.address:localhost:9092}") String address
    ) {
        ManagedChannel channel = ManagedChannelBuilder.forTarget(address)
                .usePlaintext()
                .build();
        return ProductQueryServiceGrpc.newBlockingStub(channel);
    }
}
