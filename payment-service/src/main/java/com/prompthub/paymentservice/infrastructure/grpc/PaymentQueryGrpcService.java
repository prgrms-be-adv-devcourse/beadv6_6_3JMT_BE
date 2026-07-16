package com.prompthub.paymentservice.infrastructure.grpc;

import com.prompthub.exception.BusinessException;
import com.prompthub.payment.grpc.GetPaymentRequest;
import com.prompthub.payment.grpc.GetPaymentResponse;
import com.prompthub.payment.grpc.GetRefundRequest;
import com.prompthub.payment.grpc.GetRefundResponse;
import com.prompthub.payment.grpc.PaymentQueryServiceGrpc;
import com.prompthub.paymentservice.application.dto.command.GetPaymentCommand;
import com.prompthub.paymentservice.application.dto.command.GetRefundCommand;
import com.prompthub.paymentservice.application.dto.result.PaymentQueryResult;
import com.prompthub.paymentservice.application.dto.result.RefundQueryResult;
import com.prompthub.paymentservice.application.usecase.GetPaymentUseCase;
import com.prompthub.paymentservice.application.usecase.GetRefundUseCase;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.grpc.server.service.GrpcService;

@Slf4j
@GrpcService
public class PaymentQueryGrpcService extends PaymentQueryServiceGrpc.PaymentQueryServiceImplBase {

    private final GetRefundUseCase getRefundUseCase;
    private final GetPaymentUseCase getPaymentUseCase;

    public PaymentQueryGrpcService(GetRefundUseCase getRefundUseCase, GetPaymentUseCase getPaymentUseCase) {
        this.getRefundUseCase = getRefundUseCase;
        this.getPaymentUseCase = getPaymentUseCase;
    }

    @Override
    public void getRefund(GetRefundRequest request, StreamObserver<GetRefundResponse> responseObserver) {
        try {
            GetRefundCommand command = new GetRefundCommand(
                UUID.fromString(request.getPaymentId()),
                UUID.fromString(request.getOrderProductId()));

            RefundQueryResult result = getRefundUseCase.getRefund(command);

            GetRefundResponse.Builder response = GetRefundResponse.newBuilder()
                .setPaymentId(result.paymentId().toString())
                .setOrderId(result.orderId().toString())
                .setUserId(result.userId().toString())
                .setOrderProductId(result.orderProductId().toString())
                .setAmount(result.amount())
                .setPaymentStatus(result.paymentStatus())
                .setRefundStatus(result.refundStatus());
            if (result.refundedAt() != null) {
                response.setRefundedAt(result.refundedAt().toString());
            }
            responseObserver.onNext(response.build());
            responseObserver.onCompleted();
        } catch (BusinessException e) {
            log.warn("환불 조회 gRPC 실패 — paymentId={}, orderProductId={}, code={}",
                request.getPaymentId(), request.getOrderProductId(), e.getErrorCode().getCode());
            Status status = e.getErrorCode().getStatus().is4xxClientError() ? Status.NOT_FOUND : Status.INTERNAL;
            responseObserver.onError(new StatusRuntimeException(status.withDescription(e.getMessage())));
        }
    }

    @Override
    public void getPayment(GetPaymentRequest request, StreamObserver<GetPaymentResponse> responseObserver) {
        try {
            GetPaymentCommand command = new GetPaymentCommand(UUID.fromString(request.getOrderId()));

            PaymentQueryResult result = getPaymentUseCase.getPayment(command);

            GetPaymentResponse.Builder response = GetPaymentResponse.newBuilder()
                .setPaymentId(result.paymentId().toString())
                .setOrderId(result.orderId().toString())
                .setUserId(result.userId().toString())
                .setStatus(result.status());
            if (result.amount() != null) {
                response.setAmount(result.amount());
            }
            if (result.approvedAt() != null) {
                response.setApprovedAt(result.approvedAt().toString());
            }
            if (result.failedAt() != null) {
                response.setFailedAt(result.failedAt().toString());
            }
            responseObserver.onNext(response.build());
            responseObserver.onCompleted();
        } catch (BusinessException e) {
            log.warn("결제 조회 gRPC 실패 — orderId={}, code={}",
                request.getOrderId(), e.getErrorCode().getCode());
            Status status = e.getErrorCode().getStatus().is4xxClientError() ? Status.NOT_FOUND : Status.INTERNAL;
            responseObserver.onError(new StatusRuntimeException(status.withDescription(e.getMessage())));
        }
    }
}
