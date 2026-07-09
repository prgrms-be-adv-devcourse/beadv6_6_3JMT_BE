package com.prompthub.order.infra.grpc.server;

import com.prompthub.grpc.order.v1.GetOrderPaymentInfoRequest;
import com.prompthub.grpc.order.v1.GetOrderPaymentInfoResponse;
import com.prompthub.grpc.order.v1.OrderPaymentServiceGrpc;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.repository.OrderRepository;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderPaymentGrpcService extends OrderPaymentServiceGrpc.OrderPaymentServiceImplBase {

    private final OrderRepository orderRepository;

    @Override
    public void getOrderForPayment(
            GetOrderPaymentInfoRequest request,
            StreamObserver<GetOrderPaymentInfoResponse> responseObserver
    ) {
        try {
            UUID orderId = UUID.fromString(request.getOrderId());
            Optional<Order> orderOpt = orderRepository.findByIdWithOrderProducts(orderId);

            if (orderOpt.isEmpty()) {
                responseObserver.onError(new StatusRuntimeException(Status.NOT_FOUND.withDescription("Order not found")));
                return;
            }

            Order order = orderOpt.get();
            responseObserver.onNext(GetOrderPaymentInfoResponse.newBuilder()
                    .setOrderId(order.getId().toString())
                    .setBuyerId(order.getBuyerId().toString())
                    .setTotalAmount(order.getTotalOrderAmount())
                    .setCreatedAt(order.getCreatedAt() != null ? order.getCreatedAt().toString() : "")
                    .build());
            responseObserver.onCompleted();
        } catch (IllegalArgumentException e) {
            log.error("Invalid orderId format: {}", request.getOrderId(), e);
            responseObserver.onError(new StatusRuntimeException(Status.INVALID_ARGUMENT.withDescription("Invalid order ID format")));
        } catch (Exception e) {
            log.error("GetOrderForPayment failed. orderId={}", request.getOrderId(), e);
            responseObserver.onError(new StatusRuntimeException(Status.INTERNAL.withDescription("Internal server error")));
        }
    }
}
