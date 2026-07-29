package com.prompthub.user.sellersettlement.infrastructure.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.prompthub.user.grpc.sellersettlement.command.RegisterSellerSettlementRequest;
import com.prompthub.user.grpc.sellersettlement.command.RegisterSellerSettlementResponse;
import com.prompthub.user.grpc.sellersettlement.command.SellerSettlementCommandServiceGrpc;
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
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SellerSettlementCommandGrpcAuthInterceptorTest {

    private static final String EXPECTED_TOKEN = "settlement-command-token";

    private final AtomicBoolean invoked = new AtomicBoolean();
    private Server server;
    private ManagedChannel channel;

    @BeforeEach
    void setUp() throws IOException {
        SellerSettlementCommandGrpcAuthInterceptor interceptor =
                new SellerSettlementCommandGrpcAuthInterceptor(
                        new SellerSettlementCommandGrpcSecurityProperties(EXPECTED_TOKEN));
        var service = new SellerSettlementCommandServiceGrpc
                .SellerSettlementCommandServiceImplBase() {
            @Override
            public void registerSellerSettlement(
                    RegisterSellerSettlementRequest request,
                    StreamObserver<RegisterSellerSettlementResponse> observer) {
                invoked.set(true);
                observer.onNext(RegisterSellerSettlementResponse.getDefaultInstance());
                observer.onCompleted();
            }
        };
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name)
                .directExecutor()
                .addService(ServerInterceptors.intercept(service, interceptor))
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    }

    @AfterEach
    void tearDown() {
        channel.shutdown();
        server.shutdown();
    }

    @Test
    @DisplayName("Command RPC는 전용 토큰만으로 x-user-id 없이 호출할 수 있다")
    void acceptsDedicatedTokenWithoutUserId() {
        stub(EXPECTED_TOKEN).registerSellerSettlement(
                RegisterSellerSettlementRequest.getDefaultInstance());

        assertThat(invoked).isTrue();
    }

    @Test
    @DisplayName("전용 토큰이 다르면 Command RPC를 차단한다")
    void rejectsWrongToken() {
        assertThatThrownBy(() -> stub("wrong-token").registerSellerSettlement(
                RegisterSellerSettlementRequest.getDefaultInstance()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(error -> assertThat(
                        ((StatusRuntimeException) error).getStatus().getCode())
                        .isEqualTo(Status.Code.UNAUTHENTICATED));
        assertThat(invoked).isFalse();
    }

    private SellerSettlementCommandServiceGrpc
            .SellerSettlementCommandServiceBlockingStub stub(String token) {
        Metadata metadata = new Metadata();
        metadata.put(SellerSettlementGrpcMetadata.INTERNAL_TOKEN, token);
        return SellerSettlementCommandServiceGrpc.newBlockingStub(
                ClientInterceptors.intercept(
                        channel,
                        MetadataUtils.newAttachHeadersInterceptor(metadata)));
    }
}
