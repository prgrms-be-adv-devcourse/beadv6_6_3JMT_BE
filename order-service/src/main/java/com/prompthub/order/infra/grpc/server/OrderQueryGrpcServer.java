package com.prompthub.order.infra.grpc.server;

import com.prompthub.order.grpc.GetOrderRequest;
import com.prompthub.order.grpc.GetOrderResponse;
import com.prompthub.order.grpc.OrderQueryServiceGrpc;
import com.prompthub.order.application.dto.OrderForPaymentResult;
import com.prompthub.order.application.service.order.OrderService;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderQueryGrpcServer extends OrderQueryServiceGrpc.OrderQueryServiceImplBase {

    private final OrderService orderService;

    @Override
    public void getOrder(
            GetOrderRequest request,
            StreamObserver<GetOrderResponse> responseObserver
    ) {
        try {
            UUID orderId = parseOrderId(request.getOrderId());
            OrderForPaymentResult result = orderService.getOrderForPayment(orderId);

            responseObserver.onNext(toResponse(result));
            responseObserver.onCompleted();
        } catch (OrderException exception) {
            handleOrderException(request.getOrderId(), exception, responseObserver);
        } catch (Exception exception) {
            log.error("결제용 주문 조회 중 서버 오류가 발생했습니다. orderId={}", request.getOrderId(), exception);
            responseObserver.onError(internalServerError());
        }
    }

    private UUID parseOrderId(String orderIdStr) {
        try {
            return UUID.fromString(orderIdStr);
        } catch (IllegalArgumentException exception) {
            log.warn("올바르지 않은 주문 ID 형식: {}", orderIdStr);
            throw new OrderException(ErrorCode.INVALID_INPUT_VALUE);
        }
    }

    private GetOrderResponse toResponse(OrderForPaymentResult result) {
        return GetOrderResponse.newBuilder()
                .setOrderId(result.orderId().toString())
                .setBuyerId(result.buyerId().toString())
                .setTotalAmount(result.totalAmount())
                .setCreatedAt(result.createdAt() == null ? "" : result.createdAt().toString())
                .build();
    }

    private void handleOrderException(
            String orderId,
            OrderException exception,
            StreamObserver<GetOrderResponse> responseObserver
    ) {
        if (exception.getErrorCode() == ErrorCode.ORDER_NOT_FOUND) {
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription("주문을 찾을 수 없습니다.")
                    .asRuntimeException());
            return;
        }
        if (exception.getErrorCode() == ErrorCode.INVALID_INPUT_VALUE) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("올바르지 않은 주문 ID 형식입니다.")
                    .asRuntimeException());
            return;
        }

        log.error("결제용 주문 조회 중 주문 예외 발생. orderId={}, errorCode={}",
                orderId, exception.getErrorCode(), exception);
        responseObserver.onError(internalServerError());
    }

    private RuntimeException internalServerError() {
        return Status.INTERNAL
                .withDescription("서버 내부 오류가 발생했습니다.")
                .asRuntimeException();
    }
}
