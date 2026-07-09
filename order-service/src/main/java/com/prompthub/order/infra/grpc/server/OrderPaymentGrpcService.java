package com.prompthub.order.infra.grpc.server;

import com.prompthub.grpc.order.v1.GetOrderForPaymentRequest;
import com.prompthub.grpc.order.v1.GetOrderForPaymentResponse;
import com.prompthub.grpc.order.v1.OrderLookupStatus;
import com.prompthub.grpc.order.v1.OrderPaymentServiceGrpc;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.repository.OrderRepository;
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
            GetOrderForPaymentRequest request,
            StreamObserver<GetOrderForPaymentResponse> responseObserver
    ) {
        try {
            UUID orderId = UUID.fromString(request.getOrderId());
            Optional<Order> orderOpt = orderRepository.findByIdWithOrderProducts(orderId);

            if (orderOpt.isEmpty()) {
                responseObserver.onNext(GetOrderForPaymentResponse.newBuilder()
                        .setStatus(OrderLookupStatus.NOT_FOUND)
                        .build());
                responseObserver.onCompleted();
                return;
            }

            Order order = orderOpt.get();
            responseObserver.onNext(GetOrderForPaymentResponse.newBuilder()
                    .setStatus(OrderLookupStatus.FOUND)
                    .setOrderId(order.getId().toString())
                    .setBuyerId(order.getBuyerId().toString())
                    .setOrderNumber(order.getOrderNumber())
                    .setTotalAmount(order.getTotalOrderAmount())
                    .setOrderStatus(order.getOrderStatus().name())
                    .setCreatedAt(order.getCreatedAt() != null ? order.getCreatedAt().toString() : "")
                    .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("GetOrderForPayment failed. orderId={}", request.getOrderId(), e);
            responseObserver.onNext(GetOrderForPaymentResponse.newBuilder()
                    .setStatus(OrderLookupStatus.NOT_FOUND)
                    .build());
            responseObserver.onCompleted();
        }
    }
}
