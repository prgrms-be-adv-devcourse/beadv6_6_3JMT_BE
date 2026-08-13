package com.prompthub.ai.recommendation.infrastructure.client.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.prompthub.product.grpc.GetSimilarProductsRequest;
import com.prompthub.product.grpc.GetSimilarProductsResponse;
import com.prompthub.product.grpc.ProductQueryServiceGrpc;
import com.prompthub.product.grpc.RecommendedProduct;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PersonalizedProductQueryClientTest {

    private static final UUID CART = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID PURCHASED = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID RECOMMENDED = UUID.fromString("30000000-0000-0000-0000-000000000001");

    @Test
    @DisplayName("장바구니와 구매 활동을 한 요청으로 보내 통합 추천을 읽는다")
    void sendsIntegratedRecommendationRequest() throws Exception {
        AtomicReference<GetSimilarProductsRequest> received = new AtomicReference<>();
        ProductQueryServiceGrpc.ProductQueryServiceImplBase service =
                new ProductQueryServiceGrpc.ProductQueryServiceImplBase() {
                    @Override
                    public void getSimilarProducts(
                            GetSimilarProductsRequest request,
                            StreamObserver<GetSimilarProductsResponse> observer) {
                        received.set(request);
                        observer.onNext(GetSimilarProductsResponse.newBuilder()
                                .addProducts(RecommendedProduct.newBuilder()
                                        .setProductId(RECOMMENDED.toString())
                                        .setTitle("추천 상품")
                                        .build())
                                .build());
                        observer.onCompleted();
                    }
                };

        String serverName = InProcessServerBuilder.generateName();
        Server server = InProcessServerBuilder.forName(serverName).directExecutor().addService(service).build().start();
        var channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
        try {
            PersonalizedProductQueryClient client = new PersonalizedProductQueryClient(
                    ProductQueryServiceGrpc.newBlockingStub(channel));

            assertThat(client.findRecommendations(List.of(CART), List.of(PURCHASED), 4))
                    .extracting(product -> product.id())
                    .containsExactly(RECOMMENDED);
            assertThat(received.get().getCartProductIdsList()).containsExactly(CART.toString());
            assertThat(received.get().getPurchasedProductIdsList()).containsExactly(PURCHASED.toString());
            assertThat(received.get().getLimit()).isEqualTo(4);
        } finally {
            channel.shutdownNow();
            server.shutdownNow();
        }
    }
}
