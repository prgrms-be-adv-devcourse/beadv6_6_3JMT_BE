package com.prompthub.order.infra.grpc.server;

import com.prompthub.order.grpc.GetOrderRequest;
import com.prompthub.order.grpc.GetOrderResponse;
import com.prompthub.order.grpc.GetSettleableLinesRequest;
import com.prompthub.order.grpc.GetSettleableLinesResponse;
import com.prompthub.order.grpc.OrderQueryServiceGrpc;
import com.prompthub.order.application.dto.OrderForPaymentResult;
import com.prompthub.order.application.dto.SettleableLineResult;
import com.prompthub.order.application.usecase.OrderQueryUseCase;
import com.prompthub.order.application.usecase.SettlementOrderQueryUseCase;
import com.prompthub.order.domain.enums.SettlementLineType;
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
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

class OrderQueryGrpcServerTest {

    private OrderQueryUseCase orderQueryUseCase;
    private SettlementOrderQueryUseCase settlementOrderQueryUseCase;
    private Server server;
    private ManagedChannel channel;
    private OrderQueryServiceGrpc.OrderQueryServiceBlockingStub blockingStub;

    @BeforeEach
    void setUp() throws IOException {
        orderQueryUseCase = Mockito.mock(OrderQueryUseCase.class);
        settlementOrderQueryUseCase = Mockito.mock(SettlementOrderQueryUseCase.class);
        String serverName = InProcessServerBuilder.generateName();

        server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new OrderQueryGrpcServer(orderQueryUseCase, settlementOrderQueryUseCase))
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
    void getOrder_existingOrder_returnsMappedResponse() {
        UUID orderId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();
        LocalDateTime createdAt = LocalDateTime.of(2026, 7, 13, 12, 30);
        given(orderQueryUseCase.getOrderForPayment(orderId))
                .willReturn(new OrderForPaymentResult(orderId, buyerId, 15000, createdAt));

        GetOrderResponse response = blockingStub.getOrder(request(orderId.toString()));

        assertThat(response.getOrderId()).isEqualTo(orderId.toString());
        assertThat(response.getBuyerId()).isEqualTo(buyerId.toString());
        assertThat(response.getTotalAmount()).isEqualTo(15000);
        assertThat(response.getCreatedAt()).isEqualTo(createdAt.toString());
        then(orderQueryUseCase).should().getOrderForPayment(orderId);
    }

    @Test
    void getOrder_missingCreatedAt_returnsEmptyString() {
        UUID orderId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();
        given(orderQueryUseCase.getOrderForPayment(orderId))
                .willReturn(new OrderForPaymentResult(orderId, buyerId, 15000, null));

        GetOrderResponse response = blockingStub.getOrder(request(orderId.toString()));

        assertThat(response.getCreatedAt()).isEmpty();
    }

    @Test
    void getOrder_missingOrder_returnsNotFound() {
        UUID orderId = UUID.randomUUID();
        given(orderQueryUseCase.getOrderForPayment(orderId))
                .willThrow(new OrderException(ErrorCode.ORDER_NOT_FOUND));

        assertStatusCode(request(orderId.toString()), Status.Code.NOT_FOUND);
    }

    @Test
    void getOrder_invalidUuid_returnsInvalidArgument() {
        assertStatusCode(request("invalid-uuid-string"), Status.Code.INVALID_ARGUMENT);

        then(orderQueryUseCase).should(never()).getOrderForPayment(Mockito.any());
    }

    @Test
    void getOrder_unexpectedFailure_returnsInternal() {
        UUID orderId = UUID.randomUUID();
        given(orderQueryUseCase.getOrderForPayment(orderId))
                .willThrow(new IllegalArgumentException("database credentials must stay private"));

        assertThatThrownBy(() -> blockingStub.getOrder(request(orderId.toString())))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(exception -> {
                    StatusRuntimeException statusException = (StatusRuntimeException) exception;
                    assertThat(statusException.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
                    assertThat(statusException.getStatus().getDescription()).isEqualTo("서버 내부 오류가 발생했습니다.");
                });
    }

    @Test
    void getOrder_preservesWireMethodName() {
        assertThat(OrderQueryServiceGrpc.getGetOrderMethod().getFullMethodName())
                .isEqualTo("prompthub.order.OrderQueryService/GetOrder");
    }

    @Test
    void getSettleableLines_validPeriod_returnsMappedProductLines() {
        UUID orderId = UUID.randomUUID();
        UUID orderProductId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        LocalDateTime occurredAt = LocalDateTime.of(2026, 7, 15, 12, 30);
        given(settlementOrderQueryUseCase.getSettleableLines(YearMonth.of(2026, 7)))
                .willReturn(List.of(new SettleableLineResult(
                        SettlementLineType.REFUND,
                        orderId,
                        orderProductId,
                        sellerId,
                        15_000,
                        occurredAt
                )));

        GetSettleableLinesResponse response = blockingStub.getSettleableLines(settleableRequest("2026-07"));

        assertThat(response.getLinesList()).singleElement().satisfies(line -> {
            assertThat(line.getLineType()).isEqualTo("REFUND");
            assertThat(line.getOrderId()).isEqualTo(orderId.toString());
            assertThat(line.getOrderProductId()).isEqualTo(orderProductId.toString());
            assertThat(line.getSellerId()).isEqualTo(sellerId.toString());
            assertThat(line.getLineAmount()).isEqualTo(15_000);
            assertThat(line.getOccurredAt()).isEqualTo(occurredAt.toString());
        });
        then(settlementOrderQueryUseCase).should().getSettleableLines(YearMonth.of(2026, 7));
    }

    @Test
    void getSettleableLines_emptyResult_returnsEmptyResponse() {
        given(settlementOrderQueryUseCase.getSettleableLines(YearMonth.of(2026, 7)))
                .willReturn(List.of());

        GetSettleableLinesResponse response = blockingStub.getSettleableLines(settleableRequest("2026-07"));

        assertThat(response.getLinesList()).isEmpty();
    }

    @Test
    void getSettleableLines_invalidPeriods_returnInvalidArgument() {
        for (String period : List.of("", "2026-7", "2026-13")) {
            assertSettleableStatusCode(period, Status.Code.INVALID_ARGUMENT);
        }

        then(settlementOrderQueryUseCase).should(never()).getSettleableLines(Mockito.any());
    }

    @Test
    void getSettleableLines_unexpectedFailure_returnsInternalWithoutExposingMessage() {
        given(settlementOrderQueryUseCase.getSettleableLines(YearMonth.of(2026, 7)))
                .willThrow(new IllegalStateException("database credentials must stay private"));

        assertThatThrownBy(() -> blockingStub.getSettleableLines(settleableRequest("2026-07")))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(exception -> {
                    StatusRuntimeException statusException = (StatusRuntimeException) exception;
                    assertThat(statusException.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
                    assertThat(statusException.getStatus().getDescription()).isEqualTo("서버 내부 오류가 발생했습니다.");
                    assertThat(statusException.getMessage()).doesNotContain("database credentials");
                });
    }

    @Test
    void getSettleableLines_preservesWireMethodName() {
        assertThat(OrderQueryServiceGrpc.getGetSettleableLinesMethod().getFullMethodName())
                .isEqualTo("prompthub.order.OrderQueryService/GetSettleableLines");
    }

    private GetOrderRequest request(String orderId) {
        return GetOrderRequest.newBuilder()
                .setOrderId(orderId)
                .build();
    }

    private GetSettleableLinesRequest settleableRequest(String period) {
        return GetSettleableLinesRequest.newBuilder()
                .setPeriod(period)
                .build();
    }

    private void assertStatusCode(GetOrderRequest request, Status.Code expectedCode) {
        assertThatThrownBy(() -> blockingStub.getOrder(request))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(exception -> {
                    StatusRuntimeException statusException = (StatusRuntimeException) exception;
                    assertThat(statusException.getStatus().getCode()).isEqualTo(expectedCode);
                });
    }

    private void assertSettleableStatusCode(String period, Status.Code expectedCode) {
        assertThatThrownBy(() -> blockingStub.getSettleableLines(settleableRequest(period)))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(exception -> {
                    StatusRuntimeException statusException = (StatusRuntimeException) exception;
                    assertThat(statusException.getStatus().getCode()).isEqualTo(expectedCode);
                });
    }
}
