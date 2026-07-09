package com.prompthub.settlement.infrastructure.client.seller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.prompthub.settlement.grpc.seller.SellerBatchQueryResponse;
import com.prompthub.settlement.grpc.seller.SellerInfo;
import com.prompthub.settlement.grpc.seller.SellerQueryServiceGrpc.SellerQueryServiceBlockingStub;
import io.grpc.Status;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SellerQueryClientTest {

    @Mock
    private SellerQueryServiceBlockingStub sellerQueryStub;

    @InjectMocks
    private SellerQueryClient sellerQueryClient;

    @Test
    @DisplayName("정상 응답을 sellerId→sellerName 맵으로 변환한다")
    void findSellerNames_mapsResponseToNameMap() {
        UUID sellerA = UUID.randomUUID();
        UUID sellerB = UUID.randomUUID();
        SellerBatchQueryResponse response = SellerBatchQueryResponse.newBuilder()
                .addSellers(SellerInfo.newBuilder()
                        .setSellerId(sellerA.toString()).setSellerName("프롬프트마스터").setStatus("ACTIVE").build())
                .addSellers(SellerInfo.newBuilder()
                        .setSellerId(sellerB.toString()).setSellerName("AI스튜디오").setStatus("ACTIVE").build())
                .build();
        given(sellerQueryStub.findSellers(any())).willReturn(response);

        Map<UUID, String> names = sellerQueryClient.findSellerNames(List.of(sellerA, sellerB));

        assertThat(names).containsEntry(sellerA, "프롬프트마스터").containsEntry(sellerB, "AI스튜디오");
    }

    @Test
    @DisplayName("응답에 없는(빈 이름) 판매자는 맵에서 제외한다")
    void findSellerNames_blankNameExcluded() {
        UUID sellerA = UUID.randomUUID();
        UUID sellerB = UUID.randomUUID();
        SellerBatchQueryResponse response = SellerBatchQueryResponse.newBuilder()
                .addSellers(SellerInfo.newBuilder()
                        .setSellerId(sellerA.toString()).setSellerName("프롬프트마스터").build())
                .build();
        given(sellerQueryStub.findSellers(any())).willReturn(response);

        Map<UUID, String> names = sellerQueryClient.findSellerNames(List.of(sellerA, sellerB));

        assertThat(names).containsEntry(sellerA, "프롬프트마스터");
        assertThat(names).doesNotContainKey(sellerB);
    }

    @Test
    @DisplayName("gRPC 호출이 실패해도 예외를 던지지 않고 빈 맵을 반환한다(정산 조회 보호)")
    void findSellerNames_grpcError_returnsEmptyMap() {
        UUID sellerId = UUID.randomUUID();
        given(sellerQueryStub.findSellers(any()))
                .willThrow(Status.UNAVAILABLE.asRuntimeException());

        Map<UUID, String> names = sellerQueryClient.findSellerNames(List.of(sellerId));

        assertThat(names).isEmpty();
    }

    @Test
    @DisplayName("조회 대상이 비면 gRPC 호출 없이 빈 맵을 반환한다")
    void findSellerNames_emptyInput_returnsEmptyMapWithoutCall() {
        Map<UUID, String> names = sellerQueryClient.findSellerNames(List.of());

        assertThat(names).isEmpty();
        org.mockito.BDDMockito.then(sellerQueryStub).shouldHaveNoInteractions();
    }
}
