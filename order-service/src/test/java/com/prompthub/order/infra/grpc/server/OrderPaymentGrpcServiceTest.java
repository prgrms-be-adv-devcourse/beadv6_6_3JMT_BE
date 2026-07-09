package com.prompthub.order.infra.grpc.server;

import com.prompthub.grpc.order.v1.GetOrderForPaymentRequest;
import com.prompthub.grpc.order.v1.GetOrderForPaymentResponse;
import com.prompthub.grpc.order.v1.OrderLookupStatus;
import com.prompthub.grpc.order.v1.OrderPaymentServiceGrpc;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.repository.OrderRepository;
import com.prompthub.order.fixture.OrderFixture;
import io.grpc.ManagedChannel;
import io.grpc.Server;
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

        GetOrderForPaymentRequest request = GetOrderForPaymentRequest.newBuilder()
                .setOrderId(orderId.toString())
                .build();

        // when
        GetOrderForPaymentResponse response = blockingStub.getOrderForPayment(request);

        // then
        assertThat(response.getStatus()).isEqualTo(OrderLookupStatus.FOUND);
        assertThat(response.getOrderId()).isEqualTo(orderId.toString());
        assertThat(response.getBuyerId()).isEqualTo(order.getBuyerId().toString());
        assertThat(response.getOrderNumber()).isEqualTo(order.getOrderNumber());
        assertThat(response.getTotalAmount()).isEqualTo(order.getTotalOrderAmount());
        assertThat(response.getOrderStatus()).isEqualTo(order.getOrderStatus().name());
    }

    @Test
    void getOrderForPayment_notExists_shouldReturnNotFound() {
        // given
        UUID orderId = UUID.randomUUID();
        when(orderRepository.findByIdWithOrderProducts(orderId)).thenReturn(Optional.empty());

        GetOrderForPaymentRequest request = GetOrderForPaymentRequest.newBuilder()
                .setOrderId(orderId.toString())
                .build();

        // when
        GetOrderForPaymentResponse response = blockingStub.getOrderForPayment(request);

        // then
        assertThat(response.getStatus()).isEqualTo(OrderLookupStatus.NOT_FOUND);
        assertThat(response.getOrderId()).isEmpty();
    }

    @Test
    void getOrderForPayment_invalidUuid_shouldReturnNotFound() {
        // given
        GetOrderForPaymentRequest request = GetOrderForPaymentRequest.newBuilder()
                .setOrderId("invalid-uuid-string")
                .build();

        // when
        GetOrderForPaymentResponse response = blockingStub.getOrderForPayment(request);

        // then
        assertThat(response.getStatus()).isEqualTo(OrderLookupStatus.NOT_FOUND);
    }

    @Test
    void getOrderForPayment_otherStatuses_shouldReturnFound() {
        // given
        Order order = OrderFixture.createPaidOrderWithProducts(); // status PAID
        UUID orderId = order.getId();
        when(orderRepository.findByIdWithOrderProducts(orderId)).thenReturn(Optional.of(order));

        GetOrderForPaymentRequest request = GetOrderForPaymentRequest.newBuilder()
                .setOrderId(orderId.toString())
                .build();

        // when
        GetOrderForPaymentResponse response = blockingStub.getOrderForPayment(request);

        // then
        assertThat(response.getStatus()).isEqualTo(OrderLookupStatus.FOUND);
        assertThat(response.getOrderStatus()).isEqualTo("PAID");
    }
}
