package com.prompthub.user.sellersettlement.infrastructure.grpc;

import com.prompthub.grpc.product.ProductCountRequest;
import com.prompthub.grpc.product.ProductCountResponse;
import com.prompthub.grpc.product.ProductQueryServiceGrpc;
import com.prompthub.user.sellersettlement.application.client.ProductStatsClient;
import com.prompthub.user.sellersettlement.application.dto.SellerProductStats;
import io.grpc.StatusRuntimeException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProductStatsGrpcClient implements ProductStatsClient {

    private final ProductQueryServiceGrpc.ProductQueryServiceBlockingStub productQueryStub;

    @Override
    public SellerProductStats getSellerProductStats(UUID sellerId) {
        try {
            ProductCountResponse response = productQueryStub.countBySeller(
                    ProductCountRequest.newBuilder().setSellerId(sellerId.toString()).build());
            return new SellerProductStats(response.getProductCount(), response.getSalesCount());
        } catch (StatusRuntimeException e) {
            // 표시용 참고 데이터라 실패 시 0 으로 폴백해 정산 요약 조회 자체를 막지 않는다.
            log.warn("셀러 상품 통계 gRPC 조회 실패 — 0 으로 폴백. sellerId={}", sellerId, e);
            return SellerProductStats.empty();
        }
    }
}
