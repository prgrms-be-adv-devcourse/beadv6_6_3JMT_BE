package com.prompthub.paymentservice.infrastructure.external.grpc;

import com.prompthub.exception.BusinessException;
import com.prompthub.order.grpc.GetOrderPaymentInfoRequest;
import com.prompthub.order.grpc.GetOrderPaymentInfoResponse;
import com.prompthub.order.grpc.OrderQueryServiceGrpc;
import com.prompthub.paymentservice.application.exception.PaymentErrorCode;
import com.prompthub.paymentservice.application.gateway.external.OrderPaymentInfo;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderGrpcClientAdapterTest {

    private Server server;
    private ManagedChannel channel;

    @AfterEach
    void tearDown() {
        if (channel != null) {
            channel.shutdownNow();
        }
        if (server != null) {
            server.shutdownNow();
        }
    }

    private OrderGrpcClientAdapter adapterWith(OrderQueryServiceGrpc.OrderQueryServiceImplBase serviceImpl) throws Exception {
        String serverName = UUID.randomUUID().toString();
        server = InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(serviceImpl)
            .build()
            .start();
        channel = InProcessChannelBuilder.forName(serverName)
            .directExecutor()
            .build();
        return new OrderGrpcClientAdapter(OrderQueryServiceGrpc.newBlockingStub(channel));
    }

    @Test
    void 오프셋_포함_응답_시_그대로_파싱() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();
        OffsetDateTime createdAt = OffsetDateTime.of(2026, 7, 9, 10, 0, 0, 0, ZoneOffset.UTC);

        OrderGrpcClientAdapter adapter = adapterWith(new OrderQueryServiceGrpc.OrderQueryServiceImplBase() {
            @Override
            public void getOrderForPayment(GetOrderPaymentInfoRequest request,
                    StreamObserver<GetOrderPaymentInfoResponse> responseObserver) {
                responseObserver.onNext(GetOrderPaymentInfoResponse.newBuilder()
                    .setOrderId(orderId.toString())
                    .setBuyerId(buyerId.toString())
                    .setTotalAmount(10_000)
                    .setCreatedAt(createdAt.toString())
                    .build());
                responseObserver.onCompleted();
            }
        });

        OrderPaymentInfo result = adapter.getOrderPaymentInfo(orderId);

        assertThat(result.orderId()).isEqualTo(orderId);
        assertThat(result.buyerId()).isEqualTo(buyerId);
        assertThat(result.totalAmount()).isEqualTo(10_000);
        assertThat(result.orderCreatedAt()).isEqualTo(createdAt);
    }

    @Test
    void 오프셋_없는_응답_시_KST로_간주() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();

        OrderGrpcClientAdapter adapter = adapterWith(new OrderQueryServiceGrpc.OrderQueryServiceImplBase() {
            @Override
            public void getOrderForPayment(GetOrderPaymentInfoRequest request,
                    StreamObserver<GetOrderPaymentInfoResponse> responseObserver) {
                responseObserver.onNext(GetOrderPaymentInfoResponse.newBuilder()
                    .setOrderId(orderId.toString())
                    .setBuyerId(buyerId.toString())
                    .setTotalAmount(5_000)
                    .setCreatedAt("2026-07-09T10:00:00")
                    .build());
                responseObserver.onCompleted();
            }
        });

        OrderPaymentInfo result = adapter.getOrderPaymentInfo(orderId);

        assertThat(result.orderCreatedAt()).isEqualTo(OffsetDateTime.of(2026, 7, 9, 10, 0, 0, 0, ZoneOffset.ofHours(9)));
    }

    @Test
    void 주문_없음_응답_시_ORDER_NOT_FOUND_예외() throws Exception {
        UUID orderId = UUID.randomUUID();

        OrderGrpcClientAdapter adapter = adapterWith(new OrderQueryServiceGrpc.OrderQueryServiceImplBase() {
            @Override
            public void getOrderForPayment(GetOrderPaymentInfoRequest request,
                    StreamObserver<GetOrderPaymentInfoResponse> responseObserver) {
                responseObserver.onError(Status.NOT_FOUND.withDescription("주문 없음").asRuntimeException());
            }
        });

        assertThatThrownBy(() -> adapter.getOrderPaymentInfo(orderId))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getErrorCode())
            .isEqualTo(PaymentErrorCode.ORDER_NOT_FOUND);
    }

    @Test
    void 그외_gRPC_오류_시_ORDER_INFO_UNAVAILABLE_예외() throws Exception {
        UUID orderId = UUID.randomUUID();

        OrderGrpcClientAdapter adapter = adapterWith(new OrderQueryServiceGrpc.OrderQueryServiceImplBase() {
            @Override
            public void getOrderForPayment(GetOrderPaymentInfoRequest request,
                    StreamObserver<GetOrderPaymentInfoResponse> responseObserver) {
                responseObserver.onError(Status.UNAVAILABLE.withDescription("주문 서비스 다운").asRuntimeException());
            }
        });

        assertThatThrownBy(() -> adapter.getOrderPaymentInfo(orderId))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getErrorCode())
            .isEqualTo(PaymentErrorCode.ORDER_INFO_UNAVAILABLE);
    }
}
