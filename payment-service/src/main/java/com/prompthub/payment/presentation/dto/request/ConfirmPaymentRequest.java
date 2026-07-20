package com.prompthub.payment.presentation.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

@Schema(description = "결제 승인 요청")
public record ConfirmPaymentRequest(
    @Schema(description = "토스페이먼츠 SDK 결제 키", example = "tossPayments_key_abc123",
        requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank String paymentKey,

    @Schema(description = "결제할 주문 ID", example = "660e8400-e29b-41d4-a716-446655440001",
        requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull UUID orderId,

    @Schema(description = "결제 요청 금액 — 주문 실제 금액과 다르면 400(PAY012)", example = "50000",
        requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull @Positive Integer amount
) {}
