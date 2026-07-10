package com.prompthub.paymentservice.infrastructure.external.grpc;

import com.prompthub.grpc.order.v1.OrderPaymentServiceGrpc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;

@Configuration
public class OrderGrpcClientConfig {

    @Bean
    public OrderPaymentServiceGrpc.OrderPaymentServiceBlockingStub orderPaymentServiceBlockingStub(
        GrpcChannelFactory grpcChannelFactory
    ) {
        return OrderPaymentServiceGrpc.newBlockingStub(grpcChannelFactory.createChannel("order"));
    }
}
