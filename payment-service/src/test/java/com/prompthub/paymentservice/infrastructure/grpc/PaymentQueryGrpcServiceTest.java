package com.prompthub.paymentservice.infrastructure.grpc;

import com.prompthub.exception.BusinessException;
import com.prompthub.paymentservice.application.dto.command.GetRefundCommand;
import com.prompthub.paymentservice.application.dto.result.RefundQueryResult;
import com.prompthub.paymentservice.application.exception.PaymentErrorCode;
import com.prompthub.paymentservice.application.usecase.GetRefundUseCase;
import com.prompthub.payment.grpc.GetRefundRequest;
import com.prompthub.payment.grpc.GetRefundResponse;
import com.prompthub.payment.grpc.PaymentQueryServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class PaymentQueryGrpcServiceTest {

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

    private PaymentQueryServiceGrpc.PaymentQueryServiceBlockingStub stubWith(GetRefundUseCase useCase) throws Exception {
        String serverName = UUID.randomUUID().toString();
        server = InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(new PaymentQueryGrpcService(useCase))
            .build()
            .start();
        channel = InProcessChannelBuilder.forName(serverName)
            .directExecutor()
            .build();
        return PaymentQueryServiceGrpc.newBlockingStub(channel);
    }

    @Test
    void 정상_조회_시_GetRefundResponse_반환() throws Exception {
        UUID paymentId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID orderProductId = UUID.randomUUID();
        OffsetDateTime refundedAt = OffsetDateTime.now();

        GetRefundUseCase useCase = Mockito.mock(GetRefundUseCase.class);
        when(useCase.getRefund(new GetRefundCommand(paymentId, orderProductId))).thenReturn(new RefundQueryResult(
            paymentId, orderId, userId, orderProductId, 4_000, "PARTIAL_REFUNDED", "COMPLETED", refundedAt));

        PaymentQueryServiceGrpc.PaymentQueryServiceBlockingStub stub = stubWith(useCase);

        GetRefundResponse response = stub.getRefund(GetRefundRequest.newBuilder()
            .setPaymentId(paymentId.toString())
            .setOrderProductId(orderProductId.toString())
            .build());

        assertThat(response.getPaymentId()).isEqualTo(paymentId.toString());
        assertThat(response.getOrderId()).isEqualTo(orderId.toString());
        assertThat(response.getUserId()).isEqualTo(userId.toString());
        assertThat(response.getOrderProductId()).isEqualTo(orderProductId.toString());
        assertThat(response.getAmount()).isEqualTo(4_000);
        assertThat(response.getPaymentStatus()).isEqualTo("PARTIAL_REFUNDED");
        assertThat(response.getRefundStatus()).isEqualTo("COMPLETED");
        assertThat(response.getRefundedAt()).isEqualTo(refundedAt.toString());
    }

    @Test
    void PAYMENT_NOT_FOUND_예외_시_NOT_FOUND_status() throws Exception {
        GetRefundUseCase useCase = Mockito.mock(GetRefundUseCase.class);
        when(useCase.getRefund(any())).thenThrow(new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND));

        PaymentQueryServiceGrpc.PaymentQueryServiceBlockingStub stub = stubWith(useCase);

        assertThatThrownBy(() -> stub.getRefund(GetRefundRequest.newBuilder()
                .setPaymentId(UUID.randomUUID().toString())
                .setOrderProductId(UUID.randomUUID().toString())
                .build()))
            .isInstanceOf(StatusRuntimeException.class)
            .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
            .isEqualTo(Status.Code.NOT_FOUND);
    }

    @Test
    void REFUND_NOT_FOUND_예외_시_NOT_FOUND_status() throws Exception {
        GetRefundUseCase useCase = Mockito.mock(GetRefundUseCase.class);
        when(useCase.getRefund(any())).thenThrow(new BusinessException(PaymentErrorCode.REFUND_NOT_FOUND));

        PaymentQueryServiceGrpc.PaymentQueryServiceBlockingStub stub = stubWith(useCase);

        assertThatThrownBy(() -> stub.getRefund(GetRefundRequest.newBuilder()
                .setPaymentId(UUID.randomUUID().toString())
                .setOrderProductId(UUID.randomUUID().toString())
                .build()))
            .isInstanceOf(StatusRuntimeException.class)
            .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
            .isEqualTo(Status.Code.NOT_FOUND);
    }
}
