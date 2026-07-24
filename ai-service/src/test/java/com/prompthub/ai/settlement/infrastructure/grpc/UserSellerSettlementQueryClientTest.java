package com.prompthub.ai.settlement.infrastructure.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.prompthub.ai.global.exception.AiErrorCode;
import com.prompthub.ai.global.exception.AiException;
import com.prompthub.ai.settlement.application.port.SellerSettlementAnalysisQuery.SettlementSummaryResult;
import com.prompthub.user.grpc.sellersettlement.GetSettlementSummaryRequest;
import com.prompthub.user.grpc.sellersettlement.GetSettlementSummaryResponse;
import com.prompthub.user.grpc.sellersettlement.SellerSettlementQueryServiceGrpc;
import com.prompthub.user.grpc.sellersettlement.SettlementAggregate;
import com.prompthub.user.grpc.sellersettlement.SettlementPeriodType;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AI User 판매자 정산 gRPC client")
class UserSellerSettlementQueryClientTest {

    private static final String TOKEN = "internal-test-token";
    private static final Metadata.Key<String> USER_ID = Metadata.Key.of(
            "x-user-id", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> INTERNAL_TOKEN = Metadata.Key.of(
            "x-internal-service-token", Metadata.ASCII_STRING_MARSHALLER);

    private final AtomicReference<String> receivedActorId = new AtomicReference<>();
    private final AtomicReference<String> receivedToken = new AtomicReference<>();
    private final AtomicReference<io.grpc.Deadline> receivedDeadline = new AtomicReference<>();
    private final AtomicInteger callCount = new AtomicInteger();
    private final AtomicReference<Status> failure = new AtomicReference<>();

    private Server server;
    private ManagedChannel channel;
    private UserSellerSettlementQueryClient client;

    @BeforeEach
    void setUp() throws Exception {
        String serverName = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(io.grpc.ServerInterceptors.intercept(service(), metadataInterceptor()))
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
        client = new UserSellerSettlementQueryClient(
                SellerSettlementQueryServiceGrpc.newBlockingStub(channel),
                Duration.ofSeconds(3),
                TOKEN,
                new SimpleMeterRegistry());
    }

    @AfterEach
    void tearDown() {
        if (channel != null) {
            channel.shutdownNow();
        }
        if (server != null) {
            server.shutdownNow();
        }
    }

    @Test
    @DisplayName("actor와 token은 metadata에만 넣고 3초 deadline으로 safe result를 매핑한다")
    void mapsSafeSummaryWithServerMetadataAndDeadline() {
        UUID actorId = UUID.randomUUID();

        SettlementSummaryResult result = client.getSummary(actorId, "MONTH", "2026-07");

        assertThat(receivedActorId).hasValue(actorId.toString());
        assertThat(receivedToken).hasValue(TOKEN);
        assertThat(receivedDeadline.get()).isNotNull();
        assertThat(receivedDeadline.get().timeRemaining(TimeUnit.MILLISECONDS)).isBetween(1L, 3000L);
        assertThat(result.periodType()).isEqualTo("MONTH");
        assertThat(result.requestedPeriod()).isEqualTo("2026-07");
        assertThat(result.aggregate().grossSaleAmount()).isEqualTo("123456.78");
        assertThat(result.aggregate().saleCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("gRPC 실패를 재시도하거나 raw status를 cause로 보존하지 않는다")
    void mapsGrpcFailureWithoutRetryOrRawCause() {
        failure.set(Status.UNAVAILABLE.withDescription("raw-sensitive-description"));

        assertThatThrownBy(() -> client.getSummary(UUID.randomUUID(), "MONTH", "2026-07"))
                .isInstanceOf(AiException.class)
                .satisfies(exception -> {
                    AiException aiException = (AiException) exception;
                    assertThat(aiException.getErrorCode())
                            .isEqualTo(AiErrorCode.SETTLEMENT_DATA_UNAVAILABLE);
                    assertThat(aiException.getCause()).isNull();
                    assertThat(aiException.getMessage()).doesNotContain("raw-sensitive-description");
                });
        assertThat(callCount).hasValue(1);
    }

    private SellerSettlementQueryServiceGrpc.SellerSettlementQueryServiceImplBase service() {
        return new SellerSettlementQueryServiceGrpc.SellerSettlementQueryServiceImplBase() {
            @Override
            public void getSettlementSummary(
                    GetSettlementSummaryRequest request,
                    StreamObserver<GetSettlementSummaryResponse> responseObserver
            ) {
                callCount.incrementAndGet();
                receivedDeadline.set(Context.current().getDeadline());
                Status configuredFailure = failure.get();
                if (configuredFailure != null) {
                    responseObserver.onError(configuredFailure.asRuntimeException());
                    return;
                }
                assertThat(request.getAllFields().values())
                        .doesNotContain(receivedActorId.get(), receivedToken.get());
                responseObserver.onNext(GetSettlementSummaryResponse.newBuilder()
                        .setPeriodType(SettlementPeriodType.MONTH)
                        .setRequestedPeriod(request.getPeriod())
                        .setAggregate(SettlementAggregate.newBuilder()
                                .setIncludedStartDate("2026-07-01")
                                .setIncludedEndDate("2026-07-31")
                                .setDataThrough("2026-07-19")
                                .setPartial(true)
                                .setSaleCount(3)
                                .setRefundCount(1)
                                .setGrossSaleAmount("123456.78")
                                .setGrossRefundAmount("10000")
                                .setSaleFeeAmount("1200")
                                .setRefundedFeeAmount("100")
                                .setNetFeeAmount("1100")
                                .setPayoutAmount("112356.78"))
                        .build());
                responseObserver.onCompleted();
            }
        };
    }

    private ServerInterceptor metadataInterceptor() {
        return new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                    ServerCall<ReqT, RespT> call,
                    Metadata headers,
                    ServerCallHandler<ReqT, RespT> next
            ) {
                receivedActorId.set(headers.get(USER_ID));
                receivedToken.set(headers.get(INTERNAL_TOKEN));
                return Contexts.interceptCall(Context.current(), call, headers, next);
            }
        };
    }
}
