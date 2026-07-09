package com.prompthub.order.infra.grpc.server;

import com.prompthub.grpc.order.v1.GetOrderPaymentInfoRequest;
import com.prompthub.grpc.order.v1.GetOrderPaymentInfoResponse;
import com.prompthub.grpc.order.v1.OrderPaymentServiceGrpc;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.repository.OrderRepository;
import com.prompthub.order.fixture.OrderFixture;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

class OrderPaymentGrpcServiceTest {

    private OrderRepository orderRepository;
    private Server server;
    private ManagedChannel channel;
    private OrderPaymentServiceGrpc.OrderPaymentServiceBlockingStub blockingStub;

    @BeforeEach
    void setUp() throws IOException {
        orderRepository = Mockito.mock(OrderRepository.class);
        String serverName = InProcessServerBuilder.generateName();

        server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new OrderPaymentGrpcService(orderRepository))
                .build()
                .start();

        channel = InProcessChannelBuilder.forName(serverName)
                .directExecutor()
                .usePlaintext()
                .build();

        blockingStub = OrderPaymentServiceGrpc.newBlockingStub(channel);
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
    void getOrderForPayment_exists_shouldReturnFound() {
        // given
        Order order = OrderFixture.createPendingOrder();
        UUID orderId = order.getId();
        when(orderRepository.findByIdWithOrderProducts(orderId)).thenReturn(Optional.of(order));

        GetOrderPaymentInfoRequest request = GetOrderPaymentInfoRequest.newBuilder()
                .setOrderId(orderId.toString())
                .build();

        // when
        GetOrderPaymentInfoResponse response = blockingStub.getOrderForPayment(request);

        // then
        assertThat(response.getOrderId()).isEqualTo(orderId.toString());
        assertThat(response.getBuyerId()).isEqualTo(order.getBuyerId().toString());
        assertThat(response.getTotalAmount()).isEqualTo(order.getTotalOrderAmount());
        assertThat(response.getCreatedAt()).isEqualTo(order.getCreatedAt().toString());
    }

    @Test
    void getOrderForPayment_notExists_shouldReturnNotFound() {
        // given
        UUID orderId = UUID.randomUUID();
        when(orderRepository.findByIdWithOrderProducts(orderId)).thenReturn(Optional.empty());

        GetOrderPaymentInfoRequest request = GetOrderPaymentInfoRequest.newBuilder()
                .setOrderId(orderId.toString())
                .build();

        // when & then
        assertThatThrownBy(() -> blockingStub.getOrderForPayment(request))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> {
                    StatusRuntimeException statusException = (StatusRuntimeException) e;
                    assertThat(statusException.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
                });
    }

    @Test
    void getOrderForPayment_invalidUuid_shouldReturnInvalidArgument() {
        // given
        GetOrderPaymentInfoRequest request = GetOrderPaymentInfoRequest.newBuilder()
                .setOrderId("invalid-uuid-string")
                .build();

        // when & then
        assertThatThrownBy(() -> blockingStub.getOrderForPayment(request))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> {
                    StatusRuntimeException statusException = (StatusRuntimeException) e;
                    assertThat(statusException.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
                });
    }
}
