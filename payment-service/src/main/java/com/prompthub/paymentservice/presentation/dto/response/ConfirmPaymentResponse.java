package com.prompthub.paymentservice.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "결제 승인 응답")
public record ConfirmPaymentResponse(
    @Schema(description = "생성된 Payment ID", example = "550e8400-e29b-41d4-a716-446655440000")
    UUID paymentId
) {}
