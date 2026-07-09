package com.prompthub.settlement.infrastructure.client.product;

import com.prompthub.settlement.application.port.ProductQueryPort;
import com.prompthub.settlement.grpc.product.ProductCountRequest;
import com.prompthub.settlement.grpc.product.ProductQueryServiceGrpc.ProductQueryServiceBlockingStub;
import io.grpc.StatusRuntimeException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProductQueryClient implements ProductQueryPort {

    private final ProductQueryServiceBlockingStub productQueryStub;

    @Override
    public int countBySeller(UUID sellerId) {
        ProductCountRequest request = ProductCountRequest.newBuilder()
                .setSellerId(sellerId.toString())
                .build();
        try {
            return productQueryStub.countBySeller(request).getProductCount();
        } catch (StatusRuntimeException exception) {
            log.warn("등록 상품 수 gRPC 조회에 실패해 0 으로 대체합니다. sellerId={}", sellerId, exception);
            return 0;
        }
    }
}
