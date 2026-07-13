package com.prompthub.user.sellersettlement.infrastructure.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.prompthub.product.grpc.GetSellerStatsResponse;
import com.prompthub.product.grpc.ProductQueryServiceGrpc;
import com.prompthub.user.sellersettlement.application.dto.SellerProductStats;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProductStatsGrpcClientTest {

    @Mock
    private ProductQueryServiceGrpc.ProductQueryServiceBlockingStub productQueryStub;

    @Test
    @DisplayName("GetSellerStats 응답의 상품 수·판매건수를 SellerProductStats로 매핑한다")
    void getSellerProductStats_mapsResponse() {
        // given
        UUID sellerId = UUID.randomUUID();
        when(productQueryStub.getSellerStats(any())).thenReturn(
                GetSellerStatsResponse.newBuilder()
                        .setSellerId(sellerId.toString())
                        .setProductCount(3)
                        .setSalesCount(1342L)
                        .build());
        ProductStatsGrpcClient client = new ProductStatsGrpcClient(productQueryStub);

        // when
        SellerProductStats stats = client.getSellerProductStats(sellerId);

        // then
        assertThat(stats.registeredProductCount()).isEqualTo(3);
        assertThat(stats.salesCount()).isEqualTo(1342L);
    }

    @Test
    @DisplayName("gRPC 조회 실패 시 0으로 폴백해 정산 요약 조회를 막지 않는다")
    void getSellerProductStats_onFailure_fallsBackToZero() {
        // given
        when(productQueryStub.getSellerStats(any()))
                .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));
        ProductStatsGrpcClient client = new ProductStatsGrpcClient(productQueryStub);

        // when
        SellerProductStats stats = client.getSellerProductStats(UUID.randomUUID());

        // then
        assertThat(stats.registeredProductCount()).isZero();
        assertThat(stats.salesCount()).isZero();
    }
}
