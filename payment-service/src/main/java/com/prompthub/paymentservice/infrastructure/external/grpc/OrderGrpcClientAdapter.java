package com.prompthub.paymentservice.infrastructure.external.grpc;

import com.prompthub.exception.BusinessException;
import com.prompthub.grpc.order.v1.GetOrderPaymentInfoRequest;
import com.prompthub.grpc.order.v1.GetOrderPaymentInfoResponse;
import com.prompthub.grpc.order.v1.OrderInternalServiceGrpc;
import com.prompthub.paymentservice.application.exception.PaymentErrorCode;
import com.prompthub.paymentservice.application.gateway.external.OrderGateway;
import com.prompthub.paymentservice.application.gateway.external.OrderPaymentInfo;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class OrderGrpcClientAdapter implements OrderGateway {

    // 이벤트 createdAt(LocalDateTime)과 동일한 KST 표기 관례 — 존 없는 gRPC 문자열에 부여할 기본 존
    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    private final OrderInternalServiceGrpc.OrderInternalServiceBlockingStub stub;
    private final int deadlineMs;

    public OrderGrpcClientAdapter(
        OrderInternalServiceGrpc.OrderInternalServiceBlockingStub stub,
        @Value("${prompthub.grpc.order.deadline-ms:2000}") int deadlineMs
    ) {
        this.stub = stub;
        this.deadlineMs = deadlineMs;
    }

    @Override
    public OrderPaymentInfo getOrderPaymentInfo(UUID orderId) {
        try {
            GetOrderPaymentInfoResponse response = stub
                .withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
                .getOrderPaymentInfo(GetOrderPaymentInfoRequest.newBuilder()
                    .setOrderId(orderId.toString())
                    .build());
            return new OrderPaymentInfo(
                UUID.fromString(response.getOrderId()),
                UUID.fromString(response.getBuyerId()),
                response.getTotalAmount(),
                parseCreatedAt(response.getCreatedAt())
            );
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
                throw new BusinessException(PaymentErrorCode.ORDER_NOT_FOUND);
            }
            log.error("주문 정보 gRPC 조회 실패 — orderId={}, status={}", orderId, e.getStatus(), e);
            throw new BusinessException(PaymentErrorCode.ORDER_INFO_UNAVAILABLE);
        }
    }

    // ISO 8601 문자열. 오프셋이 있으면 그대로, 없으면 KST로 간주.
    private OffsetDateTime parseCreatedAt(String value) {
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException e) {
            return LocalDateTime.parse(value).atOffset(KST);
        }
    }
}
