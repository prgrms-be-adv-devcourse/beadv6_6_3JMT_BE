package com.prompthub.notification.presentation.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.context.annotation.Configuration;

@OpenAPIDefinition(
    info = @Info(
        title = "PromptHub Notification Service API",
        version = "v2",
        description = "주문 결제·환불 알림 조회와 실시간 알림 기능을 제공하는 Notification Service API"
    ),
    security = @SecurityRequirement(name = "Bearer"),
    servers = @Server(url = "/", description = "notification-service")
)
@SecurityScheme(
    name = "Bearer",
    type = SecuritySchemeType.HTTP,
    scheme = "bearer",
    bearerFormat = "JWT",
    description = "API Gateway가 검증할 JWT. Authorization 헤더에 'Bearer {accessToken}' 형식으로 입력"
)
@Configuration
public class SwaggerConfig {
}
