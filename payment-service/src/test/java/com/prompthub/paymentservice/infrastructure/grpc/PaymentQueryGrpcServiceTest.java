package com.prompthub.paymentservice.infrastructure.grpc;

import com.prompthub.exception.BusinessException;
import com.prompthub.paymentservice.application.dto.command.GetPaymentCommand;
import com.prompthub.paymentservice.application.dto.result.PaymentQueryResult;
import com.prompthub.paymentservice.application.exception.PaymentErrorCode;
import com.prompthub.paymentservice.application.usecase.GetPaymentUseCase;
import com.prompthub.payment.grpc.GetPaymentRequest;
import com.prompthub.payment.grpc.GetPaymentResponse;
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

    private PaymentQueryServiceGrpc.PaymentQueryServiceBlockingStub stubWith(
        GetPaymentUseCase paymentUseCase
    ) throws Exception {
        String serverName = UUID.randomUUID().toString();
        server = InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(new PaymentQueryGrpcService(paymentUseCase))
            .build()
            .start();
        channel = InProcessChannelBuilder.forName(serverName)
            .directExecutor()
            .build();
        return PaymentQueryServiceGrpc.newBlockingStub(channel);
    }

    @Test
    void 정상_조회_시_GetPaymentResponse_반환() throws Exception {
        UUID paymentId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        OffsetDateTime approvedAt = OffsetDateTime.now();

        GetPaymentUseCase paymentUseCase = Mockito.mock(GetPaymentUseCase.class);
        when(paymentUseCase.getPayment(new GetPaymentCommand(orderId))).thenReturn(new PaymentQueryResult(
            paymentId, orderId, userId, "PAID", 10_000, approvedAt, null));

        PaymentQueryServiceGrpc.PaymentQueryServiceBlockingStub stub = stubWith(paymentUseCase);

        GetPaymentResponse response = stub.getPayment(GetPaymentRequest.newBuilder()
            .setOrderId(orderId.toString())
            .build());

        assertThat(response.getPaymentId()).isEqualTo(paymentId.toString());
        assertThat(response.getOrderId()).isEqualTo(orderId.toString());
        assertThat(response.getUserId()).isEqualTo(userId.toString());
        assertThat(response.getStatus()).isEqualTo("PAID");
        assertThat(response.getAmount()).isEqualTo(10_000);
        assertThat(response.getApprovedAt()).isEqualTo(approvedAt.toString());
        assertThat(response.getFailedAt()).isEmpty();
    }

    @Test
    void GetPayment_PAYMENT_NOT_FOUND_예외_시_NOT_FOUND_status() throws Exception {
        GetPaymentUseCase paymentUseCase = Mockito.mock(GetPaymentUseCase.class);
        when(paymentUseCase.getPayment(any())).thenThrow(new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND));

        PaymentQueryServiceGrpc.PaymentQueryServiceBlockingStub stub = stubWith(paymentUseCase);

        assertThatThrownBy(() -> stub.getPayment(GetPaymentRequest.newBuilder()
                .setOrderId(UUID.randomUUID().toString())
                .build()))
            .isInstanceOf(StatusRuntimeException.class)
            .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
            .isEqualTo(Status.Code.NOT_FOUND);
    }
}
