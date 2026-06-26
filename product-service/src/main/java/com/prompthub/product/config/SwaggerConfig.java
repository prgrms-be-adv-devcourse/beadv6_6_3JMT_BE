package com.prompthub.product.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.context.annotation.Configuration;

@OpenAPIDefinition(
	info = @Info(
		title = "PromptHub Product Service API",
		version = "v1",
		description = "상품·리뷰·카테고리 기능을 제공하는 Product Service API"
	),
	security = @SecurityRequirement(name = "Bearer"),
	servers = @Server(url = "/", description = "product-service")
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
}
