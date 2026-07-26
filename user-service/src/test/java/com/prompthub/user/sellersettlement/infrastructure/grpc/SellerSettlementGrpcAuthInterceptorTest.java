package com.prompthub.user.sellersettlement.infrastructure.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.prompthub.user.grpc.sellersettlement.GetSettlementSummaryRequest;
import com.prompthub.user.grpc.sellersettlement.GetSettlementSummaryResponse;
import com.prompthub.user.grpc.sellersettlement.SellerSettlementQueryServiceGrpc;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("판매자 정산 gRPC metadata 인증")
class SellerSettlementGrpcAuthInterceptorTest {

    private static final String EXPECTED_TOKEN = "expected-internal-token";

    private final AtomicReference<UUID> receivedActor = new AtomicReference<>();
    private Server server;
    private ManagedChannel channel;

    @BeforeEach
    void setUp() throws IOException {
        SellerSettlementGrpcAuthInterceptor interceptor =
                new SellerSettlementGrpcAuthInterceptor(
                        new SellerSettlementGrpcSecurityProperties(EXPECTED_TOKEN));
        SellerSettlementQueryServiceGrpc.SellerSettlementQueryServiceImplBase service =
                new SellerSettlementQueryServiceGrpc.SellerSettlementQueryServiceImplBase() {
                    @Override
                    public void getSettlementSummary(
                            GetSettlementSummaryRequest request,
                            StreamObserver<GetSettlementSummaryResponse> responseObserver) {
                        receivedActor.set(SellerSettlementGrpcMetadata.ACTOR_ID.get());
                        responseObserver.onNext(GetSettlementSummaryResponse.getDefaultInstance());
                        responseObserver.onCompleted();
                    }
                };
        String serverName = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(ServerInterceptors.intercept(service, interceptor))
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(serverName)
                .directExecutor()
                .build();
    }

    @AfterEach
    void tearDown() {
        if (channel != null) {
            channel.shutdown();
        }
        if (server != null) {
            server.shutdown();
        }
    }

    @Test
    @DisplayName("올바른 token과 actor UUID면 context로 actor를 전달한다")
    void passesValidatedActorToContext() {
        UUID actorId = UUID.randomUUID();

        stub(EXPECTED_TOKEN, actorId.toString())
                .getSettlementSummary(GetSettlementSummaryRequest.getDefaultInstance());

        assertThat(receivedActor).hasValue(actorId);
    }

    @Test
    @DisplayName("잘못된 token은 malformed actor보다 먼저 안전하게 차단한다")
    void rejectsWrongTokenBeforeMalformedActor() {
        String suppliedToken = "wrong-internal-token";
        String suppliedActor = "malformed-actor";

        assertUnauthenticatedWithoutSecrets(
                () -> stub(suppliedToken, suppliedActor)
                        .getSettlementSummary(GetSettlementSummaryRequest.getDefaultInstance()),
                suppliedToken,
                suppliedActor);
        assertThat(receivedActor).hasValue(null);
    }

    @Test
    @DisplayName("올바른 token이어도 actor UUID가 잘못되면 안전하게 차단한다")
    void rejectsMalformedActorWithoutExposure() {
        String suppliedActor = "not-a-user-uuid";

        assertUnauthenticatedWithoutSecrets(
                () -> stub(EXPECTED_TOKEN, suppliedActor)
                        .getSettlementSummary(GetSettlementSummaryRequest.getDefaultInstance()),
                EXPECTED_TOKEN,
                suppliedActor);
        assertThat(receivedActor).hasValue(null);
    }

    private SellerSettlementQueryServiceGrpc.SellerSettlementQueryServiceBlockingStub stub(
            String token,
            String actorId) {
        Metadata metadata = new Metadata();
        metadata.put(SellerSettlementGrpcMetadata.INTERNAL_TOKEN, token);
        metadata.put(SellerSettlementGrpcMetadata.USER_ID, actorId);
        return SellerSettlementQueryServiceGrpc.newBlockingStub(ClientInterceptors.intercept(
                channel, MetadataUtils.newAttachHeadersInterceptor(metadata)));
    }

    private void assertUnauthenticatedWithoutSecrets(
            Runnable invocation,
            String token,
            String actorId) {
        assertThatThrownBy(invocation::run)
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(exception -> {
                    StatusRuntimeException statusException = (StatusRuntimeException) exception;
                    assertThat(statusException.getStatus().getCode())
                            .isEqualTo(Status.Code.UNAUTHENTICATED);
                    assertThat(statusException.getMessage())
                            .doesNotContain(token)
                            .doesNotContain(actorId);
                });
    }
}
