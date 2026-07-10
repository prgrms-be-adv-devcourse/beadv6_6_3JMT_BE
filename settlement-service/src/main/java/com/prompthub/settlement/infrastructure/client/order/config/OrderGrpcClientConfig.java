package com.prompthub.settlement.infrastructure.client.order.config;

import com.prompthub.order.grpc.OrderQueryServiceGrpc;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.ImportGrpcClients;

/**
 * order-service 정산 대상 라인 조회용 gRPC 채널·블로킹 스텁 빈 설정(#260).
 * 채널 주소는 yml(grpc.client.order-service.address)로 주입한다.
 */
@Configuration
@ImportGrpcClients(
        target = "order-service",
        types = OrderQueryServiceGrpc.OrderQueryServiceBlockingStub.class
)
public class OrderGrpcClientConfig {
}
