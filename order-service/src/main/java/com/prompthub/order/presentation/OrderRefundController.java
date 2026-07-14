package com.prompthub.order.presentation;

import com.prompthub.order.application.dto.CreateOrderRefundCommand;
import com.prompthub.order.application.dto.OrderRefundResult;
import com.prompthub.order.application.usecase.CreateOrderRefundUseCase;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import com.prompthub.order.global.web.AuthHeaders;
import com.prompthub.order.presentation.dto.request.CreateOrderRefundRequest;
import com.prompthub.order.presentation.dto.response.OrderRefundResponse;
import com.prompthub.presentation.dto.ApiResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v2/orders")
@RequiredArgsConstructor
public class OrderRefundController {

    private final CreateOrderRefundUseCase createOrderRefundUseCase;

    @PostMapping("/{orderId}/refund")
    public ResponseEntity<ApiResult<OrderRefundResponse>> refund(
        @RequestHeader(AuthHeaders.USER_ID) UUID buyerId,
        @PathVariable UUID orderId,
        @Valid @RequestBody CreateOrderRefundRequest request
    ) {
        OrderRefundResult result = createOrderRefundUseCase.create(new CreateOrderRefundCommand(
            buyerId,
            orderId,
            request.paymentId(),
            request.orderProductIds()
        ));
        HttpStatus responseStatus = switch (result.status()) {
            case REQUESTED, PROCESSING -> HttpStatus.ACCEPTED;
            case COMPLETED -> HttpStatus.OK;
            case UNKNOWN -> throw new OrderException(ErrorCode.ORDER_REFUND_RESULT_UNKNOWN);
            case FAILED -> throw new OrderException(ErrorCode.INTERNAL_SERVER_ERROR);
        };

        return ResponseEntity.status(responseStatus)
            .body(ApiResult.success(OrderRefundResponse.from(result)));
    }
}
