package com.prompthub.payment.presentation.config;

import com.prompthub.payment.presentation.dto.response.ConfirmPaymentResponse;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.context.annotation.Configuration;

@OpenAPIDefinition(
        info = @Info(
                title = "PromptHub Payment Service API",
                version = "v1",
                description = "결제 승인 및 환불 처리 API"
        ),
        security = @SecurityRequirement(name = "Bearer"),
        servers = @Server(url = "/", description = "payment-service")
)
@SecurityScheme(
        name = "Bearer",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT",
        description = "API Gateway가 검증한 JWT. Authorization 헤더에 'Bearer {accessToken}' 형식으로 입력"
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
