package com.prompthub.ai.recommendation.infrastructure.client.product;

import com.prompthub.ai.recommendation.application.port.SimilarProductQuery.SeedRanking;
import com.prompthub.product.grpc.GetSimilarProductsRequest;
import com.prompthub.product.grpc.GetSimilarProductsResponse;
import com.prompthub.product.grpc.ProductQueryServiceGrpc;
import com.prompthub.product.grpc.RecommendedProduct;
import com.prompthub.product.grpc.SimilarProductRanking;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AI 유사 상품 gRPC client")
class SimilarProductQueryClientTest {

    private static final UUID SEED = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID RECOMMENDED = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");
    private static final UUID SELLER = UUID.fromString("cccccccc-0000-0000-0000-000000000001");

    private final AtomicReference<GetSimilarProductsRequest> received = new AtomicReference<>();

    private Server server;
    private ManagedChannel channel;

    @AfterEach
    void tearDown() throws Exception {
        if (channel != null) {
            channel.shutdownNow().awaitTermination(1, TimeUnit.SECONDS);
        }
        if (server != null) {
            server.shutdownNow().awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("기준 목록과 후보 수를 그대로 보내고, 응답을 포트 타입으로 옮긴다")
    void sendsSeedsAndMapsResponse() throws Exception {
        SimilarProductQueryClient client = clientFor(new ProductQueryServiceGrpc.ProductQueryServiceImplBase() {
            @Override
            public void getSimilarProducts(
                    GetSimilarProductsRequest request, StreamObserver<GetSimilarProductsResponse> observer) {
                received.set(request);
                observer.onNext(GetSimilarProductsResponse.newBuilder()
                        .addRankings(SimilarProductRanking.newBuilder()
                                .setSeedProductId(SEED.toString())
                                .addProducts(RecommendedProduct.newBuilder()
                                        .setProductId(RECOMMENDED.toString())
                                        .setTitle("비슷한 상품")
                                        .setProductType("PROMPT")
                                        .setModel("GPT-5")
                                        .setAmount(15000)
                                        .setRating(4.5)
                                        .setSalesCount(7)
                                        .setSellerId(SELLER.toString())
                                        .setDescription("설명")
                                        .setThumbnailUrl("https://example.com/t.png")
                                        .addTags("취업")
                                        .build())
                                .build())
                        .build());
                observer.onCompleted();
            }
        });

        List<SeedRanking> rankings = client.findSimilarProducts(List.of(SEED), 8);

        assertThat(received.get().getSeedProductIdsList()).containsExactly(SEED.toString());
        assertThat(received.get().getLimitPerSeed()).isEqualTo(8);

        assertThat(rankings).hasSize(1);
        assertThat(rankings.get(0).seedProductId()).isEqualTo(SEED);
        assertThat(rankings.get(0).products()).singleElement().satisfies(product -> {
            assertThat(product.id()).isEqualTo(RECOMMENDED);
            assertThat(product.title()).isEqualTo("비슷한 상품");
            assertThat(product.sellerId()).isEqualTo(SELLER);
            assertThat(product.tags()).containsExactly("취업");
        });
    }

    @Test
    @DisplayName("빈 문자열로 온 필드는 null로 되돌린다 — proto3는 null을 담지 못한다")
    void mapsEmptyStringsBackToNull() throws Exception {
        SimilarProductQueryClient client = clientFor(new ProductQueryServiceGrpc.ProductQueryServiceImplBase() {
            @Override
            public void getSimilarProducts(
                    GetSimilarProductsRequest request, StreamObserver<GetSimilarProductsResponse> observer) {
                observer.onNext(GetSimilarProductsResponse.newBuilder()
                        .addRankings(SimilarProductRanking.newBuilder()
                                .setSeedProductId(SEED.toString())
                                .addProducts(RecommendedProduct.newBuilder()
                                        .setProductId(RECOMMENDED.toString())
                                        .setTitle("제목만 있는 상품")
                                        .build())
                                .build())
                        .build());
                observer.onCompleted();
            }
        });

        var product = client.findSimilarProducts(List.of(SEED), 4).get(0).products().get(0);

        assertThat(product.productType()).isNull();
        assertThat(product.model()).isNull();
        assertThat(product.sellerId()).isNull();
        assertThat(product.desc()).isNull();
        assertThat(product.thumbnailUrl()).isNull();
        assertThat(product.tags()).isEmpty();
    }

    @Test
    @DisplayName("상품 서비스가 죽어 있어도 예외 없이 빈 목록을 준다 — 추천이 멈춰도 구매는 되어야 한다")
    void degradesToEmptyWhenProductServiceFails() throws Exception {
        SimilarProductQueryClient client = clientFor(new ProductQueryServiceGrpc.ProductQueryServiceImplBase() {
            @Override
            public void getSimilarProducts(
                    GetSimilarProductsRequest request, StreamObserver<GetSimilarProductsResponse> observer) {
                observer.onError(Status.UNAVAILABLE.asRuntimeException());
            }
        });

        assertThat(client.findSimilarProducts(List.of(SEED), 4)).isEmpty();
    }

    private SimilarProductQueryClient clientFor(
            ProductQueryServiceGrpc.ProductQueryServiceImplBase service) throws Exception {
        String serverName = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(service)
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
        return new SimilarProductQueryClient(ProductQueryServiceGrpc.newBlockingStub(channel));
    }
}
