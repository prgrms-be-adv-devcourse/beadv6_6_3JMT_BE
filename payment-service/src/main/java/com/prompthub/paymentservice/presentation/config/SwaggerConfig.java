package com.prompthub.paymentservice.presentation.config;

import com.prompthub.paymentservice.presentation.dto.response.ConfirmPaymentResponse;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.context.annotation.Configuration;

@OpenAPIDefinition(
        info = @Info(
                title = "PromptHub Payment Service API",
                version = "v1",
                description = "결제 승인 및 환불 처리 API"
        ),
        servers = @Server(url = "/", description = "payment-service")
)
@Configuration
public class SwaggerConfig {

    @Schema(description = "결제 승인 성공 응답")
    public static class ConfirmPaymentApiResult {
        @Schema(example = "true")
        public boolean success;
        public ConfirmPaymentResponse data;
        @Schema(example = "success")
        public String message;
    }
}
