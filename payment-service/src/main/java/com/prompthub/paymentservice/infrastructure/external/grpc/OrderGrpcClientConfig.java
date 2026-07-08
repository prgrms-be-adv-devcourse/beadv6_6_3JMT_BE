package com.prompthub.paymentservice.infrastructure.external.grpc;

import com.prompthub.grpc.order.v1.OrderInternalServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OrderGrpcClientConfig {

    @Bean(destroyMethod = "shutdown")
    public ManagedChannel orderManagedChannel(
        @Value("${prompthub.grpc.order.host:localhost}") String host,
        @Value("${prompthub.grpc.order.port:9083}") int port
    ) {
        return ManagedChannelBuilder.forAddress(host, port)
            .usePlaintext()
            .build();
    }

    @Bean
    public OrderInternalServiceGrpc.OrderInternalServiceBlockingStub orderInternalServiceBlockingStub(
        ManagedChannel orderManagedChannel
    ) {
        return OrderInternalServiceGrpc.newBlockingStub(orderManagedChannel);
    }
}
