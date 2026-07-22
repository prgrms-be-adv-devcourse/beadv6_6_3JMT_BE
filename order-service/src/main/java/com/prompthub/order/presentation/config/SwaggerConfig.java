package com.prompthub.order.presentation.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.context.annotation.Configuration;

@OpenAPIDefinition(
    info = @Info(
        title = "PromptHub Order Service API",
        version = "v1",
        description = "주문·장바구니·구매 콘텐츠·환불 요청 기능을 제공하는 Order Service API"
    ),
    security = @SecurityRequirement(name = "Bearer"),
    servers = @Server(url = "/", description = "order-service")
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
