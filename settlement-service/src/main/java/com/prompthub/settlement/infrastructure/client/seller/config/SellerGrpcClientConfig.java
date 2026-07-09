package com.prompthub.settlement.infrastructure.client.seller.config;

import com.prompthub.settlement.grpc.seller.SellerQueryServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SellerGrpcClientConfig {

    @Bean
    SellerQueryServiceGrpc.SellerQueryServiceBlockingStub sellerQueryStub(
            @Value("${grpc.client.user-service.address:localhost:9091}") String address) {
        ManagedChannel channel = ManagedChannelBuilder.forTarget(address)
                .usePlaintext()
                .build();
        return SellerQueryServiceGrpc.newBlockingStub(channel);
    }
}
