package com.prompthub.payment.infrastructure.external.grpc;

import com.prompthub.order.grpc.OrderQueryServiceGrpc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;

@Configuration
public class OrderGrpcClientConfig {

    @Bean
    public OrderQueryServiceGrpc.OrderQueryServiceBlockingStub orderQueryServiceBlockingStub(
        GrpcChannelFactory grpcChannelFactory
    ) {
        return OrderQueryServiceGrpc.newBlockingStub(grpcChannelFactory.createChannel("order"));
    }
}
