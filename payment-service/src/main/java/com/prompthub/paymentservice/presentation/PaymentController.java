package com.prompthub.paymentservice.presentation;

import com.prompthub.paymentservice.application.dto.command.ConfirmPaymentCommand;
import com.prompthub.paymentservice.application.dto.command.RefundPaymentCommand;
import com.prompthub.paymentservice.application.dto.result.PaymentResult;
import com.prompthub.paymentservice.application.usecase.ConfirmPaymentUseCase;
import com.prompthub.paymentservice.application.usecase.RefundPaymentUseCase;
import com.prompthub.paymentservice.presentation.dto.request.ConfirmPaymentRequest;
import com.prompthub.paymentservice.presentation.dto.response.ConfirmPaymentResponse;
import com.prompthub.presentation.dto.ApiResult;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final ConfirmPaymentUseCase confirmPaymentUseCase;
    private final RefundPaymentUseCase refundPaymentUseCase;

    @PostMapping("/confirm")
    public ResponseEntity<ApiResult<ConfirmPaymentResponse>> confirm(
        @RequestHeader("X-User-Id") UUID userId,
        @Valid @RequestBody ConfirmPaymentRequest request
    ) {
        ConfirmPaymentCommand command = new ConfirmPaymentCommand(
            request.paymentKey(), request.orderId(), request.amount(), userId
        );
        PaymentResult result = confirmPaymentUseCase.confirm(command);
        return ResponseEntity.ok(ApiResult.success(new ConfirmPaymentResponse(result.paymentId())));
    }

    @PostMapping("/{paymentId}/refund")
    public ResponseEntity<ApiResult<Void>> refund(
        @RequestHeader("X-User-Id") UUID userId,
        @PathVariable UUID paymentId
    ) {
        refundPaymentUseCase.refund(new RefundPaymentCommand(paymentId, userId));
        return ResponseEntity
            .status(HttpStatus.ACCEPTED)
            .body(ApiResult.success(null));
    }
}
