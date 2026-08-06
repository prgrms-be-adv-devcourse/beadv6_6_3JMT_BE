package com.prompthub.ai.recommendation.infrastructure.client.product.config;

import com.prompthub.product.grpc.ProductQueryServiceGrpc;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.ImportGrpcClients;

/**
 * product-service 호출 채널. 대상마다 설정을 나눠 두어 주소·옵션을 독립적으로 관리한다.
 * (user-service 채널은 settlement 쪽 설정이 따로 갖고 있다)
 */
@Configuration(proxyBeanMethods = false)
@ImportGrpcClients(
        target = "product-service",
        types = ProductQueryServiceGrpc.ProductQueryServiceBlockingStub.class
)
public class ProductGrpcClientConfig {
}
