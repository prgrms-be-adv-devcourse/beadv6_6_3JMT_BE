package com.prompthub.settlement.infrastructure.client.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.prompthub.settlement.grpc.product.ProductCountResponse;
import com.prompthub.settlement.grpc.product.ProductQueryServiceGrpc.ProductQueryServiceBlockingStub;
import io.grpc.Status;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProductQueryClientTest {

    @Mock
    private ProductQueryServiceBlockingStub productQueryStub;

    @InjectMocks
    private ProductQueryClient productQueryClient;

    @Test
    @DisplayName("정상 응답의 productCount 를 반환한다")
    void countBySeller_returnsProductCount() {
        UUID sellerId = UUID.randomUUID();
        given(productQueryStub.countBySeller(any()))
                .willReturn(ProductCountResponse.newBuilder()
                        .setSellerId(sellerId.toString()).setProductCount(12).build());

        int count = productQueryClient.countBySeller(sellerId);

        assertThat(count).isEqualTo(12);
    }

    @Test
    @DisplayName("gRPC 호출이 실패해도 예외를 던지지 않고 0 을 반환한다(정산 요약 보호)")
    void countBySeller_grpcError_returnsZero() {
        UUID sellerId = UUID.randomUUID();
        given(productQueryStub.countBySeller(any()))
                .willThrow(Status.UNAVAILABLE.asRuntimeException());

        int count = productQueryClient.countBySeller(sellerId);

        assertThat(count).isZero();
    }
}
