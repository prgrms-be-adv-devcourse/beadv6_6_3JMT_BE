package com.prompthub.order.infra.grpc.server;

import com.prompthub.order.grpc.GetOrderPaymentInfoRequest;
import com.prompthub.order.grpc.GetOrderPaymentInfoResponse;
import com.prompthub.order.grpc.OrderQueryServiceGrpc;
import com.prompthub.order.application.dto.OrderForPaymentResult;
import com.prompthub.order.application.service.order.OrderService;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

class OrderQueryGrpcServerTest {

    private OrderService orderService;
    private Server server;
    private ManagedChannel channel;
    private OrderQueryServiceGrpc.OrderQueryServiceBlockingStub blockingStub;

    @BeforeEach
    void setUp() throws IOException {
        orderService = Mockito.mock(OrderService.class);
        String serverName = InProcessServerBuilder.generateName();

        server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new OrderQueryGrpcServer(orderService))
                .build()
                .start();

        channel = InProcessChannelBuilder.forName(serverName)
                .directExecutor()
                .usePlaintext()
                .build();

        blockingStub = OrderQueryServiceGrpc.newBlockingStub(channel);
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
    void getOrderForPayment_existingOrder_returnsMappedResponse() {
        UUID orderId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();
        LocalDateTime createdAt = LocalDateTime.of(2026, 7, 13, 12, 30);
        given(orderService.getOrderForPayment(orderId))
                .willReturn(new OrderForPaymentResult(orderId, buyerId, 15000, createdAt));

        GetOrderPaymentInfoResponse response = blockingStub.getOrderForPayment(request(orderId.toString()));

        assertThat(response.getOrderId()).isEqualTo(orderId.toString());
        assertThat(response.getBuyerId()).isEqualTo(buyerId.toString());
        assertThat(response.getTotalAmount()).isEqualTo(15000);
        assertThat(response.getCreatedAt()).isEqualTo(createdAt.toString());
        then(orderService).should().getOrderForPayment(orderId);
    }

    @Test
    void getOrderForPayment_missingCreatedAt_returnsEmptyString() {
        UUID orderId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();
        given(orderService.getOrderForPayment(orderId))
                .willReturn(new OrderForPaymentResult(orderId, buyerId, 15000, null));

        GetOrderPaymentInfoResponse response = blockingStub.getOrderForPayment(request(orderId.toString()));

        assertThat(response.getCreatedAt()).isEmpty();
    }

    @Test
    void getOrderForPayment_missingOrder_returnsNotFound() {
        UUID orderId = UUID.randomUUID();
        given(orderService.getOrderForPayment(orderId))
                .willThrow(new OrderException(ErrorCode.ORDER_NOT_FOUND));

        assertStatusCode(request(orderId.toString()), Status.Code.NOT_FOUND);
    }

    @Test
    void getOrderForPayment_invalidUuid_returnsInvalidArgument() {
        assertStatusCode(request("invalid-uuid-string"), Status.Code.INVALID_ARGUMENT);

        then(orderService).should(never()).getOrderForPayment(Mockito.any());
    }

    @Test
    void getOrderForPayment_unexpectedFailure_returnsInternal() {
        UUID orderId = UUID.randomUUID();
        given(orderService.getOrderForPayment(orderId))
                .willThrow(new IllegalArgumentException("database credentials must stay private"));

        assertThatThrownBy(() -> blockingStub.getOrderForPayment(request(orderId.toString())))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(exception -> {
                    StatusRuntimeException statusException = (StatusRuntimeException) exception;
                    assertThat(statusException.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
                    assertThat(statusException.getStatus().getDescription()).isEqualTo("서버 내부 오류가 발생했습니다.");
                });
    }

    @Test
    void getOrderForPayment_preservesWireMethodName() {
        assertThat(OrderQueryServiceGrpc.getGetOrderForPaymentMethod().getFullMethodName())
                .isEqualTo("prompthub.order.OrderQueryService/GetOrderForPayment");
    }

    private GetOrderPaymentInfoRequest request(String orderId) {
        return GetOrderPaymentInfoRequest.newBuilder()
                .setOrderId(orderId)
                .build();
    }

    private void assertStatusCode(GetOrderPaymentInfoRequest request, Status.Code expectedCode) {
        assertThatThrownBy(() -> blockingStub.getOrderForPayment(request))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(exception -> {
                    StatusRuntimeException statusException = (StatusRuntimeException) exception;
                    assertThat(statusException.getStatus().getCode()).isEqualTo(expectedCode);
                });
    }
}
